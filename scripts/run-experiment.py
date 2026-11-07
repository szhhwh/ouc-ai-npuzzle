#!/usr/bin/env python3
"""Run/resume all 19,440 searches sequentially in a cgroup-limited warm JVM.

Formal searches default to a 10-second cooperative deadline plus a 5-second controller
watchdog grace. The deadline can be overridden. RSS peaks come from the worker's
per-search Linux VmHWM reset, independently of the 1-second hardware sampler.
Results are fsynced individually; dispatch/start journals are flushed without an
extra fsync. config.json links the append-only worker launch configuration log,
which records each actual command and cgroup boundary without rewriting config.
"""
import argparse
from contextlib import ExitStack
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import queue
import random
import signal
import subprocess
import sys
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location('experiment_monitor', ROOT / 'scripts/monitor-experiment.py')
MONITOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MONITOR)
ALGORITHMS = ('astar', 'ida', 'nida')
HEURISTICS = ('MISPLACED', 'MANHATTAN', 'DISJOINT_PATTERN')
DEPTHS = {3: (6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 30),
          4: (5, 8, 10, 12, 15, 18, 20, 22, 25, 30, 35, 40)}
LIMIT = 16 * 1024**3
HEAP_LIMIT = 12 * 1024**3
JVM_OPTIONS = ('-Xmx12g',)
WATCHDOG_GRACE_SECONDS = 5
RSS_PEAK_METHOD = 'linux_reset_vm_hwm'
METRICS = ('wall_ns', 'cpu_ns', 'nodes_generated', 'nodes_expanded', 'solution_length',
           'path_valid', 'depth_delta', 'peak_frontier', 'peak_depth', 'iterations',
           'bound_pruned', 'cycle_pruned', 'duplicate_pruned', 'heuristic_evaluations',
           'specialized_evaluations', 'gc_count', 'gc_time_ms', 'rss_start_bytes',
           'rss_end_bytes', 'rss_peak_bytes', 'rss_peak_method')


def append(stream, item, durable=False):
    stream.write(json.dumps(item, ensure_ascii=False, separators=(',', ':')) + '\n')
    stream.flush()
    if durable:
        os.fsync(stream.fileno())


def atomic_json(path, item):
    tmp = path.with_name(path.name + '.tmp')
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(item, f, ensure_ascii=False, indent=2)
        f.write('\n')
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def read_jsonl(path):
    if not path.exists():
        return []
    rows = []
    with open(path, encoding='utf-8') as f:
        for n, line in enumerate(f, 1):
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise RuntimeError(f'{path}:{n}: invalid JSON; repair truncated record before resuming') from exc
    return rows


def samples(path):
    rows = read_jsonl(path)
    ids, boards, layers = set(), set(), {}
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError('each sample must be an object')
        size, depth, board, identifier = (row.get(k) for k in ('size', 'depth', 'board', 'instance_id'))
        if type(size) is not int or type(depth) is not int or size not in DEPTHS or depth not in DEPTHS[size]:
            raise ValueError(f'invalid sample layer: {row}')
        if not isinstance(identifier, str) or not identifier or identifier in ids:
            raise ValueError(f'invalid or duplicate instance_id: {identifier}')
        if not isinstance(board, list) or any(type(x) is not int for x in board) or sorted(board) != list(range(size * size)):
            raise ValueError(f'invalid board: {identifier}')
        key = (size, tuple(board))
        if key in boards:
            raise ValueError(f'duplicate board: {identifier}')
        ids.add(identifier)
        boards.add(key)
        layers[(size, depth)] = layers.get((size, depth), 0) + 1
    if len(rows) != 720 or any(layers.get((s, d)) != 30 for s, ds in DEPTHS.items() for d in ds):
        raise ValueError('expected exactly 30 samples in each of the 24 layers (720 total)')
    metadata_path = path.with_name('instance_metadata.json')
    metadata = json.loads(metadata_path.read_text(encoding='utf-8'))
    if (metadata.get('complete') is not True or metadata.get('instance_count') != len(rows)
            or metadata.get('instances_sha256') != hashlib.sha256(path.read_bytes()).hexdigest()):
        raise ValueError('sample certification metadata is incomplete or does not match instances')
    certified_layers = metadata.get('four_by_four', {}).get('layers', {})
    if any(certified_layers.get(str(depth), {}).get('accepted') != 30 for depth in DEPTHS[4]):
        raise ValueError('sample certification does not contain all 30 instances in each 4x4 layer')
    return rows


def create_schedule(instances, seed):
    jobs = [{'run_id': f'{i["instance_id"]}:{a}:{h}:{r}', 'instance_id': i['instance_id'],
             'size': i['size'], 'depth': i['depth'], 'board': i['board'], 'algorithm': a,
             'heuristic': h, 'repetition': r}
            for i in instances for a in ALGORITHMS for h in HEURISTICS for r in (1, 2, 3)]
    random.Random(seed).shuffle(jobs)
    return jobs


def limits(pid):
    cgroup = Path(f'/proc/{pid}/cgroup').read_text().strip().split('::', 1)[1]
    base = Path('/sys/fs/cgroup') / cgroup.lstrip('/')
    chain = []
    current = base
    while str(current).startswith('/sys/fs/cgroup'):
        try:
            mem = (current / 'memory.max').read_text().strip()
            swap = (current / 'memory.swap.max').read_text().strip()
            chain.append({'path': str(current), 'memory_max': mem, 'memory_swap_max': swap})
        except OSError:
            pass
        if current == Path('/sys/fs/cgroup'):
            break
        current = current.parent
    if not chain or chain[0]['memory_max'] == 'max' or int(chain[0]['memory_max']) > LIMIT or chain[0]['memory_swap_max'] != '0':
        raise RuntimeError(f'worker cgroup does not enforce required memory and swap limits: {chain}')
    return base, chain


def event_values(path):
    try:
        return {k: int(v) for k, v in (line.split() for line in (path / 'memory.events').read_text().splitlines())}
    except (OSError, ValueError):
        return None


class Worker:
    def __init__(self, directory, startup_timeout, metrics_off=False, monitor=True):
        self.events = queue.Queue()
        self.stderr = open(directory / 'worker-stderr.log', 'a', encoding='utf-8', buffering=1)
        self.unit = 'npuzzle-experiment-' + uuid.uuid4().hex
        self.active_file = directory / 'active-worker.json'
        atomic_json(self.active_file, {'unit': self.unit, 'created_epoch_ns': time.time_ns()})
        self.command = ['systemd-run', '--user', '--scope', '--quiet', '--unit=' + self.unit,
                        '--property=MemoryMax=16G', '--property=MemorySwapMax=0',
                        'java', *JVM_OPTIONS]
        if metrics_off:
            self.command.append('-Dexperiment.metrics.off=true')
        self.command += ['-cp', str(ROOT / 'target/classes'), 'stud.g01.experiment.ExperimentWorker',
                         str(directory / 'jvm-memory.jsonl')]
        if not monitor:
            self.command.append('--no-sampler')
        self.proc = subprocess.Popen(self.command, cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=self.stderr, text=True, bufsize=1, start_new_session=True)
        self.reader_thread = threading.Thread(target=self.reader, daemon=True)
        self.reader_thread.start()
        event = self.next_event(startup_timeout)
        if event.get('event') != 'ready' or not isinstance(event.get('pid'), int):
            self.close()
            raise RuntimeError(f'worker failed before ready: {event}')
        self.pid = event['pid']
        try:
            self.cgroup, self.boundaries = limits(self.pid)
        except Exception:
            self.close()
            raise

    def reader(self):
        for line in self.proc.stdout:
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                event = {'event': 'protocol_error', 'line': line.rstrip()}
            self.events.put(event)
        self.events.put({'event': 'eof'})

    def next_event(self, timeout):
        try:
            return self.events.get(timeout=max(0, timeout))
        except queue.Empty:
            return {'event': 'deadline'}

    def send(self, job, timeout_ns):
        request = {k: job[k] for k in ('run_id', 'algorithm', 'heuristic', 'size', 'board', 'depth', 'repetition')}
        request['timeout_ns'] = timeout_ns
        self.proc.stdin.write(json.dumps(request, separators=(',', ':')) + '\n')
        self.proc.stdin.flush()

    def close(self):
        if self.proc.poll() is None:
            try:
                os.killpg(self.proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        self.proc.wait()
        self.reader_thread.join()
        self.proc.stdin.close()
        self.proc.stdout.close()
        self.stderr.close()
        self.active_file.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--instances', default='result/experiment/instances.jsonl')
    parser.add_argument('--output', default='result/experiment')
    parser.add_argument('--seed', type=int, required=True)
    parser.add_argument('--timeout', type=float, default=10, help='uniform per-search wall time limit in seconds (default: 10)')
    parser.add_argument('--startup-timeout', type=float, default=900)
    parser.add_argument('--pilot', type=int, help='run only first N randomized searches in a separate output directory')
    parser.add_argument('--no-monitor', action='store_true', help='pilot only: measure monitoring overhead')
    parser.add_argument('--metrics-off', action='store_true', help='pilot only: disable extra node statistics')
    args = parser.parse_args()
    if args.timeout <= 0 or args.startup_timeout <= 0 or args.pilot is not None and not 1 <= args.pilot <= 19440:
        parser.error('timeouts must be positive and pilot must be 1..19440')
    timeout_ns = int(args.timeout * 1e9)
    if timeout_ns < 1:
        parser.error('timeout must be at least one nanosecond')
    if (args.no_monitor or args.metrics_off) and args.pilot is None:
        parser.error('--no-monitor and --metrics-off are only permitted for pilot runs')
    out = Path(args.output).resolve()
    if args.pilot is not None and out == (ROOT / 'result/experiment').resolve():
        parser.error('pilot runs require a separate --output directory')
    out.mkdir(parents=True, exist_ok=True)
    lock = open(out / '.controller.lock', 'a')
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise RuntimeError('another controller owns this output directory')
    instance_path = Path(args.instances).resolve()
    instances = samples(instance_path)
    digest = hashlib.sha256(instance_path.read_bytes()).hexdigest()
    jobs = create_schedule(instances, args.seed)
    if args.pilot is not None:
        jobs = jobs[:args.pilot]
    config_path, schedule_path = out / 'config.json', out / 'schedule.jsonl'
    fixed = {'instances_sha256': digest, 'seed': args.seed, 'timeout_seconds': args.timeout,
             'startup_timeout_seconds': args.startup_timeout, 'pilot': args.pilot, 'monitor': not args.no_monitor,
             'extra_metrics': not args.metrics_off,
             'memory_max_bytes': LIMIT, 'memory_swap_max_bytes': 0, 'java_heap_max_bytes': HEAP_LIMIT,
             'jvm_options': list(JVM_OPTIONS), 'sample_interval_seconds': 1, 'planned_runs': len(jobs),
             'timeout_mechanism': 'worker cooperative search deadline; controller watchdog after fixed grace',
             'request_timeout_ns': timeout_ns, 'watchdog_grace_seconds': WATCHDOG_GRACE_SECONDS,
             'rss_peak_method': RSS_PEAK_METHOD,
             'rss_peak_interval': 'reset /proc/self/clear_refs with 5 at search start; read VmHWM at search end',
             'four_by_four_nida_pdb': '6-6-3 generic predictor for controlled comparison; differs from default 7-8 native NIDA'}
    if config_path.exists():
        config = json.loads(config_path.read_text())
        if config['fixed'] != fixed:
            raise RuntimeError('resume configuration does not match existing configuration')
        if read_jsonl(schedule_path) != jobs:
            raise RuntimeError('saved schedule differs from deterministic schedule')
    else:
        if schedule_path.exists():
            raise RuntimeError('schedule exists without configuration; refusing to overwrite')
        with open(schedule_path, 'x', encoding='utf-8') as f:
            for job in jobs:
                f.write(json.dumps(job, separators=(',', ':')) + '\n')
            f.flush()
            os.fsync(f.fileno())
        java_version = subprocess.run(['java', '-version'], capture_output=True, text=True, check=True).stderr.strip()
        config = {'fixed': fixed, 'instance_file': str(instance_path), 'java_version': java_version,
                  'platform': platform.platform(), 'python_version': sys.version,
                  'cpu_count': os.cpu_count(), 'created_epoch_ns': time.time_ns(),
                  'worker_launch_log': 'worker-launches.jsonl'}
        atomic_json(config_path, config)
    active_file = out / 'active-worker.json'
    if active_file.exists():
        active = json.loads(active_file.read_text())
        unit = active['unit']
        if not unit.startswith('npuzzle-experiment-') or not unit[len('npuzzle-experiment-'):].isalnum():
            raise RuntimeError('invalid persisted worker unit')
        stopped = subprocess.run(['systemctl', '--user', 'stop', unit + '.scope'], capture_output=True, text=True)
        if stopped.returncode and 'not loaded' not in stopped.stderr and 'not found' not in stopped.stderr:
            raise RuntimeError(f'cannot stop previous worker scope: {stopped.stderr}')
        active_file.unlink()
    result_path, journal_path = out / 'results.jsonl', out / 'starts.jsonl'
    results = read_jsonl(result_path)
    valid_ids = {j['run_id'] for j in jobs}
    done = {r['run_id'] for r in results}
    if len(done) != len(results) or not done <= valid_ids:
        raise RuntimeError('duplicate or unknown result run_id')
    starts = read_jsonl(journal_path)
    pending = {r['run_id']: r for r in starts if r['run_id'] not in done}
    by_id = {j['run_id']: j for j in jobs}
    with ExitStack() as streams:
        result_log = streams.enter_context(open(result_path, 'a', encoding='utf-8'))
        start_log = streams.enter_context(open(journal_path, 'a', encoding='utf-8'))
        launch_log = streams.enter_context(open(out / config['worker_launch_log'], 'a', encoding='utf-8'))
        for rid, started in pending.items():
            if rid not in by_id:
                raise RuntimeError('unknown start run_id')
            append(result_log, {**by_id[rid], **dict.fromkeys(METRICS), 'status': 'error', 'reason': 'controller_interrupted',
                                'pid': started.get('pid'), 'start_epoch_ns': started.get('start_epoch_ns'),
                                'end_epoch_ns': None, 'metrics_available': False, 'hardware_sample_count': 0,
                                'sampling_insufficient': True}, durable=True)
            done.add(rid)
        if len(done) == len(jobs):
            return
        monitor = None if args.no_monitor else MONITOR.Monitor(out / 'hardware.jsonl')
        worker = None
        try:
            if monitor:
                monitor.start()
                time.sleep(2)
            for job in jobs:
                if job['run_id'] in done:
                    continue
                if worker is None:
                    worker = Worker(out, args.startup_timeout, args.metrics_off, not args.no_monitor)
                    append(launch_log, {'pid': worker.pid, 'unit': worker.unit,
                                        'epoch_ns': time.time_ns(), 'cgroup_chain': worker.boundaries,
                                        'command': worker.command, 'cwd': str(ROOT)}, durable=True)
                    if monitor:
                        monitor.associate(worker.pid, None)
                before = event_values(worker.cgroup)
                append(start_log, {'event': 'dispatch', 'run_id': job['run_id'], 'pid': worker.pid,
                                   'start_epoch_ns': None, 'dispatch_epoch_ns': time.time_ns()})
                try:
                    worker.send(job, timeout_ns)
                    started = worker.next_event(args.startup_timeout)
                except (BrokenPipeError, OSError) as exc:
                    started = {'event': 'error', 'detail': str(exc)}
                start_ok = started.get('event') == 'start' and started.get('run_id') == job['run_id'] and started.get('pid') == worker.pid
                if start_ok:
                    append(start_log, started)
                    if monitor:
                        monitor.associate(worker.pid, job['run_id'])
                    elapsed_ns = max(0, time.time_ns() - started['start_epoch_ns'])
                    start_clock = time.monotonic_ns() - elapsed_ns
                    deadline = start_clock + timeout_ns + WATCHDOG_GRACE_SECONDS * 1_000_000_000
                    response = worker.next_event((deadline - time.monotonic_ns()) / 1e9)
                    end_epoch = time.time_ns()
                    if monitor:
                        monitor.associate(worker.pid, None)
                else:
                    start_clock, end_epoch = None, time.time_ns()
                    response = started
                after = event_values(worker.cgroup)
                oom = before is not None and after is not None and after.get('oom_kill', 0) > before.get('oom_kill', 0)
                if response.get('event') == 'result' and response.get('run_id') == job['run_id'] and response.get('status') in ('success', 'timeout', 'oom', 'error'):
                    result = {**job, **response, 'pid': worker.pid,
                              'start_epoch_ns': response.get('search_start_epoch_ns', started.get('start_epoch_ns'))}
                    result.pop('event', None)
                    result['metrics_available'] = not response.get('metrics_unavailable', False)
                    if response.get('status') == 'oom':
                        worker.close()
                        worker = None
                else:
                    watchdog = response.get('event') == 'deadline' and start_ok
                    status = 'oom' if oom else 'timeout' if watchdog else 'error'
                    result = {**job, **dict.fromkeys(METRICS), 'pid': worker.pid, 'status': status,
                              'reason': 'controller_watchdog_exceeded' if watchdog and not oom else response.get('event', 'invalid_event'),
                              'start_epoch_ns': started.get('start_epoch_ns'), 'end_epoch_ns': end_epoch,
                              'controller_wall_ns': time.monotonic_ns() - start_clock if start_clock is not None else None,
                              'metrics_available': False, 'cgroup_memory_events_before': before,
                              'cgroup_memory_events_after': after}
                    worker.close()
                    worker = None
                for name in METRICS:
                    result.setdefault(name, None)
                result['pdb_configuration'] = ('6-6-3 generic predictor' if job['size'] == 4 and job['heuristic'] == 'DISJOINT_PATTERN' else None)
                result['expanded_per_second'] = (result['nodes_expanded'] * 1e9 / result['wall_ns']
                                                 if result['nodes_expanded'] is not None and result['wall_ns'] else None)
                result['generated_per_second'] = (result['nodes_generated'] * 1e9 / result['wall_ns']
                                                  if result['nodes_generated'] is not None and result['wall_ns'] else None)
                duration = (result.get('end_epoch_ns') or 0) - (result.get('start_epoch_ns') or result.get('end_epoch_ns') or 0)
                count = monitor.count(job['run_id']) if monitor else 0
                result['hardware_sample_count'] = count
                result['sampling_insufficient'] = duration < 1_000_000_000 or count < 2
                append(result_log, result, durable=True)
                done.add(job['run_id'])
        finally:
            if worker:
                worker.close()
            if monitor:
                monitor.associate(None, None)
                time.sleep(2)
                monitor.close()


if __name__ == '__main__':
    main()
