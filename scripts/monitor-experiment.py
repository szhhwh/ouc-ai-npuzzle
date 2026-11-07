#!/usr/bin/env python3
"""Linux /proc and /sys experiment sampler; all unavailable readings are null."""
import argparse
import glob
import json
import os
import threading
import time


def text(path):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read().strip()
    except (OSError, ValueError):
        return None


def number(path, scale=1):
    value = text(path)
    try:
        return int(value) * scale
    except (TypeError, ValueError):
        return None


def key_values(path, multiplier=1):
    raw = text(path)
    if raw is None:
        return None
    result = {}
    for line in raw.splitlines():
        parts = line.replace(':', '').split()
        if len(parts) >= 2:
            try:
                result[parts[0]] = int(parts[1]) * multiplier
            except ValueError:
                pass
    return result


def cpu_snapshot():
    raw = text('/proc/stat')
    cpus, ctxt = {}, None
    for line in (raw or '').splitlines():
        fields = line.split()
        if fields and (fields[0] == 'cpu' or fields[0][0:3] == 'cpu' and fields[0][3:].isdigit()):
            values = [int(x) for x in fields[1:]]
            # guest and guest_nice are already included in user and nice.
            cpus[fields[0]] = (sum(values[:8]), sum(values[3:5]))
        elif fields and fields[0] == 'ctxt':
            ctxt = int(fields[1])
    return cpus, ctxt


def process(pid):
    if not pid:
        return None
    raw = text(f'/proc/{pid}/stat')
    if not raw:
        return None
    try:
        fields = raw[raw.rfind(')') + 2:].split()
        status = key_values(f'/proc/{pid}/status', 1024) or {}
        io = key_values(f'/proc/{pid}/io')
        return {'cpu_ticks': int(fields[11]) + int(fields[12]),
                'rss_bytes': status.get('VmRSS'), 'peak_rss_bytes': status.get('VmHWM'),
                'io': io}
    except (ValueError, IndexError):
        return None


def disks():
    raw = text('/proc/diskstats')
    if raw is None:
        return None
    result = {}
    for line in raw.splitlines():
        f = line.split()
        if len(f) >= 14:
            try:
                result[f[2]] = {'read_bytes': int(f[5]) * 512, 'write_bytes': int(f[9]) * 512}
            except ValueError:
                pass
    return result


def sensors():
    frequencies = {p.split('/')[5]: number(p, 1000) for p in glob.glob('/sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq')}
    temperatures = {}
    for p in glob.glob('/sys/class/thermal/thermal_zone*/temp'):
        label = text(p[:-4] + 'type')
        temperatures[p.split('/')[-2] + ':' + str(label)] = number(p, 0.001)
    for p in glob.glob('/sys/class/hwmon/hwmon*/temp[0-9]*_input'):
        base = p[:-6]
        label = text(base + '_label') or os.path.basename(base)
        name = text(os.path.dirname(p) + '/name') or os.path.basename(os.path.dirname(p))
        temperatures[os.path.basename(os.path.dirname(p)) + ':' + name + ':' + label] = number(p, 0.001)
    return frequencies or None, temperatures or None


class Monitor:
    def __init__(self, filename, interval=1.0):
        self.file = open(filename, 'a', encoding='utf-8', buffering=1)
        self.interval = interval
        self.lock = threading.Lock()
        self.pid = self.run_id = None
        self.counts = {}
        self.previous = None
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self.loop, daemon=True)

    def associate(self, pid, run_id):
        with self.lock:
            self.pid, self.run_id = pid, run_id

    def count(self, run_id):
        with self.lock:
            return self.counts.get(run_id, 0)

    def sample(self):
        with self.lock:
            pid, run_id = self.pid, self.run_id
            if run_id is not None:
                self.counts[run_id] = self.counts.get(run_id, 0) + 1
        now, epoch = time.monotonic(), time.time_ns()
        cpus, ctxt = cpu_snapshot()
        proc = process(pid)
        disk = disks()
        freq, temp = sensors()
        mem = key_values('/proc/meminfo', 1024) or {}
        load = text('/proc/loadavg')
        sample = {'epoch_ns': epoch, 'monotonic_ns': int(now * 1e9), 'pid': pid, 'run_id': run_id,
                  'system_cpu_percent': None, 'per_cpu_percent': {}, 'process_cpu_percent': None,
                  'frequency_hz': freq, 'temperature_c': temp,
                  'memory_total_bytes': mem.get('MemTotal'), 'memory_available_bytes': mem.get('MemAvailable'),
                  'memory_used_bytes': (mem['MemTotal'] - mem['MemAvailable']) if 'MemTotal' in mem and 'MemAvailable' in mem else None,
                  'swap_used_bytes': (mem['SwapTotal'] - mem['SwapFree']) if 'SwapTotal' in mem and 'SwapFree' in mem else None,
                  'process': proc, 'disk_devices': disk, 'disk_bytes_per_second': None,
                  'loadavg': load, 'context_switches_total': ctxt, 'context_switches_per_second': None}
        if self.previous:
            oldtime, oldcpus, oldproc, oldpid, olddisk, oldctxt = self.previous
            elapsed = now - oldtime
            if elapsed > 0:
                for key, (total, idle) in cpus.items():
                    if key in oldcpus:
                        dt, di = total - oldcpus[key][0], idle - oldcpus[key][1]
                        value = 100 * (dt - di) / dt if dt > 0 else None
                        if key == 'cpu':
                            sample['system_cpu_percent'] = value
                        else:
                            sample['per_cpu_percent'][key] = value
                if proc and oldproc and pid == oldpid:
                    sample['process_cpu_percent'] = 100 * (proc['cpu_ticks'] - oldproc['cpu_ticks']) / os.sysconf('SC_CLK_TCK') / elapsed
                if disk is not None and olddisk is not None:
                    sample['disk_bytes_per_second'] = {k: {n: (v[n] - olddisk[k][n]) / elapsed for n in ('read_bytes', 'write_bytes')} for k, v in disk.items() if k in olddisk}
                if ctxt is not None and oldctxt is not None:
                    sample['context_switches_per_second'] = (ctxt - oldctxt) / elapsed
        self.previous = (now, cpus, proc, pid, disk, ctxt)
        self.file.write(json.dumps(sample, separators=(',', ':')) + '\n')

    def loop(self):
        while not self.stop_event.wait(self.interval):
            self.sample()

    def start(self):
        self.sample()
        self.thread.start()

    def close(self):
        self.stop_event.set()
        self.thread.join()
        self.sample()
        self.file.close()


def main():
    parser = argparse.ArgumentParser(description='Sample Linux hardware; interrupt to stop.')
    parser.add_argument('--output', required=True)
    parser.add_argument('--pid', type=int)
    args = parser.parse_args()
    monitor = Monitor(args.output)
    monitor.associate(args.pid, None)
    monitor.start()
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        monitor.close()


if __name__ == '__main__':
    main()
