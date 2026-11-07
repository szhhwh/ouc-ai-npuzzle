#!/usr/bin/env python3
"""Summarize repetitions, paired medians, interval RSS and sampled hardware."""
import argparse
import bisect
from collections import Counter, defaultdict
import itertools
import json
from pathlib import Path
import statistics

METRICS = ('wall_ns', 'cpu_ns', 'nodes_generated', 'nodes_expanded', 'peak_frontier',
           'peak_depth', 'bound_pruned', 'cycle_pruned', 'duplicate_pruned',
           'heuristic_evaluations', 'specialized_evaluations', 'gc_count', 'gc_time_ms',
           'expanded_per_second', 'generated_per_second')
RSS_METRICS = ('rss_start_bytes', 'rss_end_bytes', 'rss_peak_kernel_bytes', 'rss_peak_bytes')
RSS_PEAK_METHODS = ('linux_reset_vm_hwm', 'kernel_reset_vm_hwm')


def rss_summary(records):
    records = list(records)
    return {'runs': len(records),
            'status_counts': dict(Counter(r['status'] for r in records)),
            'peak_method_counts': dict(Counter(r.get('rss_peak_method') or 'unavailable' for r in records)),
            'kernel_peak_runs': sum(r.get('rss_peak_method') in RSS_PEAK_METHODS
                                    and isinstance(r.get('rss_peak_bytes'), (int, float)) for r in records),
            'distributions': {metric: distribution([r[metric] for r in records
                              if isinstance(r.get(metric), (int, float))
                              and (metric not in ('rss_peak_bytes', 'rss_peak_kernel_bytes') or r.get('rss_peak_method') in RSS_PEAK_METHODS)])
                              for metric in RSS_METRICS}}


def rows(path):
    if not path.exists():
        return
    with path.open(encoding='utf-8') as f:
        for line in f:
            yield json.loads(line)


def distribution(values):
    values = sorted(values)
    if not values:
        return {'count': 0, 'min': None, 'q1': None, 'median': None, 'q3': None, 'max': None, 'mean': None}
    def quantile(q):
        pos = (len(values) - 1) * q
        left = int(pos)
        return values[left] + (values[min(left + 1, len(values) - 1)] - values[left]) * (pos - left)
    return {'count': len(values), 'min': values[0], 'q1': quantile(.25), 'median': statistics.median(values),
            'q3': quantile(.75), 'max': values[-1], 'mean': statistics.mean(values)}


class Aggregate:
    def __init__(self):
        self.count = 0
        self.stats = {}
        self.first = {}
        self.last = {}

    def add(self, name, value):
        if value is None or not isinstance(value, (int, float)):
            return
        self.first.setdefault(name, value)
        self.last[name] = value
        if name not in self.stats:
            self.stats[name] = [0, 0, value, value]
        s = self.stats[name]
        s[0] += 1
        s[1] += value
        s[2] = min(s[2], value)
        s[3] = max(s[3], value)

    def result(self):
        return {k: {'count': v[0], 'mean': v[1] / v[0], 'min': v[2], 'max': v[3],
                    'first': self.first[k], 'last': self.last[k],
                    'sampled_delta': self.last[k] - self.first[k] if v[0] >= 2 else None}
                for k, v in self.stats.items()}


def add_hardware(a, sample):
    a.count += 1
    for key in ('system_cpu_percent', 'process_cpu_percent', 'memory_available_bytes', 'memory_used_bytes',
                'swap_used_bytes', 'context_switches_total', 'context_switches_per_second'):
        a.add(key, sample.get(key))
    for group in ('per_cpu_percent', 'frequency_hz', 'temperature_c'):
        for key, value in (sample.get(group) or {}).items():
            a.add(group + '.' + key, value)
    for group in ('disk_devices', 'disk_bytes_per_second'):
        for device, fields in (sample.get(group) or {}).items():
            for key, value in fields.items():
                a.add(group + '.' + device + '.' + key, value)
    proc = sample.get('process') or {}
    for key in ('rss_bytes', 'peak_rss_bytes', 'cpu_ticks'):
        a.add('process.' + key, proc.get(key))
    for key, value in (proc.get('io') or {}).items():
        a.add('process.io.' + key, value)
    load = sample.get('loadavg')
    if load:
        try:
            fields = load.split()
            for i, name in enumerate(('load1', 'load5', 'load15')):
                a.add(name, float(fields[i]))
            a.add('running_tasks', int(fields[3].split('/')[0]))
        except (ValueError, IndexError):
            pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', default='result/experiment')
    args = parser.parse_args()
    directory = Path(args.directory)
    config = json.loads((directory / 'config.json').read_text())
    schedule = list(rows(directory / 'schedule.jsonl'))
    records = list(rows(directory / 'results.jsonl'))
    if len({r['run_id'] for r in records}) != len(records):
        raise ValueError('duplicate result run_id')
    by_id = {r['run_id']: r for r in records}
    if not set(by_id) <= {s['run_id'] for s in schedule}:
        raise ValueError('unknown result run_id')
    per_instance = defaultdict(list)
    groups = defaultdict(list)
    for job in schedule:
        key = (job['size'], job['depth'], job['algorithm'], job['heuristic'], job['instance_id'])
        per_instance[key].append(by_id.get(job['run_id'], {**job, 'status': 'pending', 'wall_ns': None}))
    instance_results = []
    anomalies = []
    for key, repeats in sorted(per_instance.items()):
        size, depth, algorithm, heuristic, instance_id = key
        medians = {}
        for metric in METRICS:
            values = [r[metric] for r in repeats if r['status'] == 'success'
                      and isinstance(r.get(metric), (int, float))]
            medians[metric] = statistics.median(values) if values else None
        successes = [r for r in repeats if r['status'] == 'success']
        item = {'size': size, 'depth': depth, 'algorithm': algorithm, 'heuristic': heuristic,
                'instance_id': instance_id, 'successful_repetitions': len(successes),
                'median_success_wall_ns': medians['wall_ns'], 'median_success_metrics': medians,
                'all_run_rss': rss_summary(r for r in repeats if r['status'] != 'pending'),
                'statuses': [{'run_id': r['run_id'], 'repetition': r['repetition'],
                              'status': r['status'], 'reason': r.get('reason')}
                             for r in sorted(repeats, key=lambda x: x['repetition'])]}
        instance_results.append(item)
        groups[key[:4]].append((item, repeats))
        if any(r['status'] != 'success' for r in repeats):
            anomalies.append(item)
    group_results = []
    for key, entries in sorted(groups.items()):
        size, depth, algorithm, heuristic = key
        counts = Counter(r['status'] for _, repeats in entries for r in repeats)
        planned = sum(len(repeats) for _, repeats in entries)
        group_results.append({'size': size, 'depth': depth, 'algorithm': algorithm, 'heuristic': heuristic,
                              'planned_repetitions': planned, 'status_counts': dict(counts),
                              'success_rate': counts['success'] / planned if planned else None,
                              'all_run_rss': rss_summary(r for _, repeats in entries for r in repeats if r['status'] != 'pending'),
                              'instance_median_wall_ns_distribution': distribution([item['median_success_wall_ns'] for item, _ in entries if item['median_success_wall_ns'] is not None]),
                              'metric_distributions': {metric: distribution([item['median_success_metrics'][metric] for item, _ in entries if item['median_success_metrics'][metric] is not None]) for metric in METRICS},
                              'incomplete_instances': sum(any(r['status'] != 'success' for r in repeats) for _, repeats in entries)})
    indexed = {(x['size'], x['depth'], x['instance_id'], x['algorithm'], x['heuristic']): x['median_success_wall_ns'] for x in instance_results}
    pairs = []
    for size, depth in sorted({(x['size'], x['depth']) for x in instance_results}):
        ids = sorted({x['instance_id'] for x in instance_results if x['size'] == size and x['depth'] == depth})
        comparisons = [('algorithm', (a, h), (b, h)) for h in ('MISPLACED', 'MANHATTAN', 'DISJOINT_PATTERN') for a, b in itertools.combinations(('astar', 'ida', 'nida'), 2)]
        comparisons += [('heuristic', (a, h1), (a, h2)) for a in ('astar', 'ida', 'nida') for h1, h2 in itertools.combinations(('MISPLACED', 'MANHATTAN', 'DISJOINT_PATTERN'), 2)]
        for kind, left, right in comparisons:
            matched = []
            for iid in ids:
                x = indexed.get((size, depth, iid, *left))
                y = indexed.get((size, depth, iid, *right))
                if x is not None and y is not None:
                    matched.append({'instance_id': iid, 'left_median_wall_ns': x, 'right_median_wall_ns': y,
                                    'right_minus_left_ns': y - x, 'right_over_left': y / x if x else None})
            pairs.append({'size': size, 'depth': depth, 'comparison': kind, 'left': list(left), 'right': list(right),
                          'paired_instances': len(matched), 'paired': matched,
                          'right_minus_left_ns_distribution': distribution([v['right_minus_left_ns'] for v in matched]),
                          'right_over_left_distribution': distribution([v['right_over_left'] for v in matched if v['right_over_left'] is not None])})
    summary = {'planned': len(schedule), 'completed': len(records), 'groups': group_results, 'pairs': pairs,
               'instance_medians': instance_results, 'anomalous_instances': anomalies,
               'all_run_rss': rss_summary(records),
               'rss_rule': 'all recorded runs including timeout; peak=max(reset kernel VmHWM,start,end); smaps_rollup boundary page scans; includes loaded PDB and JVM baseline; never subtract baseline',
               'pdb_control': config['fixed'].get('four_by_four_nida_pdb'),
               'pairing_rule': 'same instance; at least one successful repetition per configuration; median of successful repetitions',
               'percentiles': 'linear interpolation of sorted per-instance medians'}
    with (directory / 'summary.json').open('w', encoding='utf-8') as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
        f.write('\n')
    intervals = sorted((r['start_epoch_ns'], r['end_epoch_ns'], r['pid'], r['run_id']) for r in records
                       if r.get('start_epoch_ns') is not None and r.get('end_epoch_ns') is not None and r.get('pid') is not None)
    starts = [i[0] for i in intervals]
    hardware, jvm = defaultdict(Aggregate), defaultdict(Aggregate)
    def locate(sample):
        timestamp = sample.get('epoch_ns')
        if not isinstance(timestamp, int):
            return None
        i = bisect.bisect_right(starts, timestamp) - 1
        if i >= 0 and intervals[i][0] <= timestamp <= intervals[i][1] and sample.get('pid') == intervals[i][2]:
            return intervals[i][3]
        return None
    for sample in rows(directory / 'hardware.jsonl'):
        rid = locate(sample)
        if rid:
            add_hardware(hardware[rid], sample)
    for sample in rows(directory / 'jvm-memory.jsonl'):
        rid = locate(sample)
        if rid:
            a = jvm[rid]
            a.count += 1
            for key in ('heap_used_bytes', 'heap_committed_bytes', 'nonheap_used_bytes', 'nonheap_committed_bytes'):
                a.add(key, sample.get(key))
    with (directory / 'run-hardware-summary.jsonl').open('w', encoding='utf-8') as f:
        for r in records:
            rid = r['run_id']
            h, j = hardware[rid], jvm[rid]
            duration = (r.get('end_epoch_ns') or 0) - (r.get('start_epoch_ns') or r.get('end_epoch_ns') or 0)
            obj = {'run_id': rid, 'pid': r.get('pid'), 'hardware_samples': h.count, 'jvm_samples': j.count,
                   'status': r['status'], **{metric: r.get(metric) for metric in RSS_METRICS},
                   'rss_peak_method': r.get('rss_peak_method'),
                   'kernel_rss_peak_available': r.get('rss_peak_method') in RSS_PEAK_METHODS
                                                and isinstance(r.get('rss_peak_bytes'), (int, float)),
                   'sampling_insufficient': duration < 1_000_000_000 or h.count < 2,
                   'jvm_sampling_insufficient': duration < 1_000_000_000 or j.count < 2,
                   'hardware': h.result(), 'jvm': j.result()}
            f.write(json.dumps(obj, ensure_ascii=False, separators=(',', ':')) + '\n')


if __name__ == '__main__':
    main()
