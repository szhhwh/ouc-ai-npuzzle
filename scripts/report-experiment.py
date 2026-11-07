#!/usr/bin/env python3
"""Create a readable, reproducible report from experiment summary and raw records."""
import argparse
import csv
import json
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path
import statistics

RSS_METRICS = ('rss_start_bytes', 'rss_end_bytes', 'rss_peak_kernel_bytes', 'rss_peak_bytes')
RSS_PEAK_METHODS = ('linux_reset_vm_hwm', 'kernel_reset_vm_hwm')


def rss_stats(records):
    result = {}
    for metric in RSS_METRICS:
        values = [r[metric] for r in records if isinstance(r.get(metric), (int, float))
                  and (metric not in ('rss_peak_bytes', 'rss_peak_kernel_bytes') or r.get('rss_peak_method') in RSS_PEAK_METHODS)]
        result[metric] = {'count': len(values), 'median': median(values),
                          'max': max(values) if values else None}
    return {'distributions': result,
            'kernel_peak_runs': result['rss_peak_bytes']['count'],
            'peak_method_counts': dict(Counter(r.get('rss_peak_method') or 'unavailable' for r in records))}


def result_analysis(summary, highest):
    lines = ['', '## 结果分析', '']
    timed_out = [g for g in summary['groups'] if g['status_counts'].get('timeout', 0)]
    if timed_out and {(g['size'], g['heuristic']) for g in timed_out} == {(4, 'MISPLACED')}:
        lines += ['本批超时只出现在四阶错位数启发函数组合。其成功耗时仅覆盖能在限制内完成的实例，因此不能将该配置较小的成功耗时中位数解释为总体更快。', '']
    for size, depth in sorted(highest.items()):
        comparisons = [p for p in summary['pairs'] if p['size'] == size and p['depth'] == depth
                       and p['paired_instances'] and p['right_over_left_distribution']['median'] is not None]
        heuristic_pairs = [p for p in comparisons if p['comparison'] == 'heuristic'
                           and p['left'][1] == 'MANHATTAN' and p['right'][1] == 'DISJOINT_PATTERN']
        if len(heuristic_pairs) == 3 and all(p['right_over_left_distribution']['median'] < 1 for p in heuristic_pairs):
            lines += [f'{size} 阶最高难度层（最短解长 {depth}）中，三个算法的 PDB 均比各自的曼哈顿配置具有更低的同实例配对耗时中位数。', '']
        algorithm_pairs = [p for p in comparisons if p['comparison'] == 'algorithm'
                           and p['left'][1] == 'DISJOINT_PATTERN']
        wins = Counter()
        for pair in algorithm_pairs:
            ratio = pair['right_over_left_distribution']['median']
            if ratio != 1:
                wins[pair['right'][0] if ratio < 1 else pair['left'][0]] += 1
        winners = [algorithm for algorithm, count in wins.items() if count == 2]
        if len(algorithm_pairs) == 3 and len(winners) == 1:
            lines += [f'在该层固定 PDB 的算法比较中，`{winners[0]}` 对另外两种算法的配对耗时中位数均更低；这一结论限于本批样本和所记录的实现配置。', '']
    lines += ['四阶 PDB 已统一为 6-6-3，因此这里的四阶 `nida` 结果不代表其默认 7-8 增量 PDB 的性能。不同配置的成功覆盖范围、短搜索计时噪声及 JVM 状态均应随比较一起考虑。']
    return lines


def jsonl(path):
    if path.exists():
        with path.open(encoding='utf-8') as handle:
            for line in handle:
                yield json.loads(line)


def median(values):
    return statistics.median(values) if values else None


def fmt(value, digits=3):
    return '—' if value is None else f'{value:.{digits}f}'


def ms(value):
    return None if value is None else value / 1_000_000


def iso(value):
    return '—' if value is None else datetime.fromtimestamp(value / 1e9).astimezone().isoformat(timespec='seconds')


def write_csv(path, fields, rows):
    with path.open('w', encoding='utf-8-sig', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', default='result/experiment')
    args = parser.parse_args()
    root = Path(args.directory)
    summary = json.loads((root / 'summary.json').read_text())
    config = json.loads((root / 'config.json').read_text())
    metadata = json.loads((root / 'instance_metadata.json').read_text())
    pilot = json.loads((root / 'pilot-analysis.json').read_text())
    environment = json.loads((root / 'environment.json').read_text())
    records = list(jsonl(root / 'results.jsonl'))
    status = Counter(r['status'] for r in records)
    records_by_group = defaultdict(list)
    for record in records:
        records_by_group[tuple(record[k] for k in ('size', 'depth', 'algorithm', 'heuristic'))].append(record)
    run_fields = ['run_id', 'size', 'depth', 'instance_id', 'algorithm', 'heuristic', 'repetition',
                  'status', 'reason', 'wall_ns', 'controller_wall_ns', 'cpu_ns', *RSS_METRICS, 'rss_peak_method']
    write_csv(root / 'runs.csv', run_fields, [{key: r.get(key) for key in run_fields} for r in records])
    highest = {size: max(g['depth'] for g in summary['groups'] if g['size'] == size)
               for size in {g['size'] for g in summary['groups']}}
    overall_rss = summary.get('all_run_rss') or rss_stats(records)
    group_rows = []
    for group in summary['groups']:
        dist = group['instance_median_wall_ns_distribution']
        metrics = group['metric_distributions']
        row = {'size': group['size'], 'depth': group['depth'], 'algorithm': group['algorithm'],
               'heuristic': group['heuristic'], 'planned': group['planned_repetitions'],
               **{key: group['status_counts'].get(key, 0) for key in ('success', 'timeout', 'oom', 'error', 'pending')},
               'success_rate': group['success_rate'], 'instances_with_incomplete_repeats': group['incomplete_instances'],
               'successful_instance_count': dist['count'], 'wall_ms_q1': ms(dist['q1']),
               'wall_ms_median': ms(dist['median']), 'wall_ms_q3': ms(dist['q3'])}
        for metric in ('cpu_ns', 'nodes_generated', 'nodes_expanded', 'peak_frontier', 'peak_depth',
                       'bound_pruned', 'cycle_pruned', 'duplicate_pruned', 'heuristic_evaluations',
                       'specialized_evaluations', 'gc_count', 'gc_time_ms', 'expanded_per_second', 'generated_per_second'):
            row[metric + '_instance_median'] = metrics[metric]['median']
        rss_summary = group.get('all_run_rss') or rss_stats(records_by_group[
            tuple(group[k] for k in ('size', 'depth', 'algorithm', 'heuristic'))])
        row['kernel_rss_peak_runs'] = rss_summary['kernel_peak_runs']
        row['rss_peak_method_counts'] = json.dumps(rss_summary['peak_method_counts'], sort_keys=True)
        for metric in RSS_METRICS:
            for stat in ('count', 'median', 'max'):
                row[f'{metric}_all_runs_{stat}'] = rss_summary['distributions'][metric][stat]
        group_rows.append(row)
    write_csv(root / 'groups.csv', list(group_rows[0]), group_rows)

    pair_rows = []
    for p in summary['pairs']:
        row = {'size': p['size'], 'depth': p['depth'], 'comparison': p['comparison'],
               'left_algorithm': p['left'][0], 'left_heuristic': p['left'][1],
               'right_algorithm': p['right'][0], 'right_heuristic': p['right'][1],
               'paired_instances': p['paired_instances'],
               'median_right_over_left': p['right_over_left_distribution']['median'],
               'q1_right_over_left': p['right_over_left_distribution']['q1'],
               'q3_right_over_left': p['right_over_left_distribution']['q3'],
               'median_right_minus_left_ms': ms(p['right_minus_left_ns_distribution']['median'])}
        pair_rows.append(row)
    write_csv(root / 'pairs.csv', list(pair_rows[0]), pair_rows)

    abnormal = [{key: r.get(key) for key in ('run_id', 'size', 'depth', 'algorithm', 'heuristic',
                                               'repetition', 'status', 'reason', 'wall_ns', 'depth_delta', 'path_valid')}
                for r in records if r['status'] != 'success' or r.get('depth_delta') not in (None, 0) or r.get('path_valid') is False]
    write_csv(root / 'abnormal-runs.csv', ['run_id', 'size', 'depth', 'algorithm', 'heuristic', 'repetition',
                                            'status', 'reason', 'wall_ns', 'depth_delta', 'path_valid'], abnormal)
    instances = defaultdict(list)
    for item in summary['instance_medians']:
        if item['median_success_wall_ns'] is not None:
            instances[(item['size'], item['algorithm'], item['heuristic'])].append(item['median_success_wall_ns'])
    rec_groups = defaultdict(list)
    for r in records:
        rec_groups[(r['size'], r['algorithm'], r['heuristic'])].append(r)

    hardware_samples = 0
    system_cpu, cpu_temp, available, rss = [], [], [], []
    for sample in jsonl(root / 'hardware.jsonl'):
        hardware_samples += 1
        if sample.get('system_cpu_percent') is not None:
            system_cpu.append(sample['system_cpu_percent'])
        if sample.get('memory_available_bytes') is not None:
            available.append(sample['memory_available_bytes'])
        for name, value in (sample.get('temperature_c') or {}).items():
            if name.endswith('coretemp:Package id 0') and value is not None:
                cpu_temp.append(value)
        value = (sample.get('process') or {}).get('rss_bytes')
        if value is not None:
            rss.append(value)
    insufficient = sum(bool(r.get('sampling_insufficient')) for r in records)
    bad_paths = sum(r.get('path_valid') is False for r in records)
    nonoptimal = [r for r in records if r.get('depth_delta') not in (None, 0)]
    beginnings = [r['start_epoch_ns'] for r in records if r.get('start_epoch_ns') is not None]
    endings = [r['end_epoch_ns'] for r in records if r.get('end_epoch_ns') is not None]
    timeout_durations = [r['wall_ns'] / 1e9 for r in records
                         if r['status'] == 'timeout' and r.get('wall_ns') is not None]
    lines = [
        '# N-Puzzle 搜索实验结果', '',
        f"正式搜索：计划 {summary['planned']} 次，已记录 {summary['completed']} 次；"
        f"成功 {status['success']}、超时 {status['timeout']}、超内存 {status['oom']}、异常 {status['error']}。",
        f"搜索时间范围：{iso(min(beginnings) if beginnings else None)} 至 {iso(max(endings) if endings else None)}。",
        f"路径校验失败 {bad_paths} 次；与认证最短解长度不一致 {len(nonoptimal)} 次。", '',
        '## 环境与方法', '',
        f"- 720 个实例：三阶和四阶各 360 个，每个深度层 30 个；样本种子 {metadata['seed']}，运行顺序种子 {config['fixed']['seed']}。",
        '- 三阶从目标盘面完整 BFS 后按层随机抽样；四阶随机游走后以 6-6-3 PDB 的 IDA* 认证，层内并非均匀分布。',
        f"- 每个实例的九种配置各重复三次；单次搜索墙钟上限 {config['fixed']['timeout_seconds']} 秒，Linux cgroup 内存上限 16 GiB、交换空间 0，JVM 堆上限 12 GiB。",
        (f'- 10 秒是搜索截止阈值；每 4096 次扩展检查一次，垃圾回收和系统调度可能延后退出。超时搜索实际经过时间中位数为 {fmt(median(timeout_durations), 6)} 秒，最大为 {fmt(max(timeout_durations), 6)} 秒；原始记录保留实际值。' if timeout_durations and config['fixed']['timeout_seconds'] == 10 else '- 超时搜索保留实际经过时间，不用阈值替换测量值。'),
        '- 各配置和棋盘规模按相同规则预热；正式计时不含 JVM 启动和 PDB 首次建表。四阶 PDB 比较统一使用 6-6-3；`nida` 默认 7-8 增量 PDB 不在本次四阶比较中。',
        f"- 主机：{environment['machine']}；Java：{config['java_version'].splitlines()[0]}。完整 CPU、内存、字节码与 PDB 哈希见 `environment.json`。",
        f"- 搜索失败独立记录；不将超时上限当成成功耗时。每个实例先取成功重复的中位数，三次并非全部成功的实例另见 `summary.json` 的 `anomalous_instances`。", '',
        '- 生成节点数不含各轮根节点，包含随后剪枝的合法后继；扩展节点数包含实际展开的根节点。A* 的重复剪枝计数只覆盖已扩展状态，不包含候选队列拒绝的重复状态；A* 候选队列峰值按有效状态数计算，不含延迟清除的旧队列项。IDA* 的环剪枝和 NIDA* 的环／置换表剪枝口径不同。',
        '- 三种实现的峰值深度口径略有区别：A* 按已生成子节点，通用 IDA* 按访问的节点，专用 IDA* 按访问及已估值候选；跨算法对比时应注意。', '',
        '## 采集开销预实验', '',
        f"同一顺序的 {pilot['planned']} 次搜索：开启采集成功 {pilot['on_status'].get('success', 0)} 次、超时 {pilot['on_status'].get('timeout', 0)} 次；关闭采集成功 {pilot['off_status'].get('success', 0)} 次、超时 {pilot['off_status'].get('timeout', 0)} 次。"
        f"{pilot['common_successes']} 次共同成功中，节点计数或解长不一致的有 {pilot['mismatched_node_counts']} 次。关闭采集时耗时至少 1 ms 的 {pilot['baseline_over_1ms_count']} 次配对，开启/关闭耗时比中位数为 {fmt(pilot['baseline_over_1ms_median_on_over_off'], 4)}。"
        '单次运行较短，结果会受 JVM 编译、调度与系统噪声影响。', '',
        '## 按棋盘与配置汇总', '',
        '下表在各阶的全部深度层中汇总。耗时是有成功结果的实例的“实例内成功重复中位数”的中位数；有超时的配置存在删失，不能直接把这些中位数解释为完整难度分布上的速度。', '',
        '| 阶数 | 算法 | 启发函数 | 成功 / 已运行 | 超时 | 有成功结果的实例 | 耗时中位数 (ms) |',
        '| ---: | --- | --- | ---: | ---: | ---: | ---: |']
    for size in (3, 4):
        for algorithm in ('astar', 'ida', 'nida'):
            for heuristic in ('MISPLACED', 'MANHATTAN', 'DISJOINT_PATTERN'):
                key = (size, algorithm, heuristic)
                values = rec_groups[key]
                count = Counter(r['status'] for r in values)
                lines.append(f'| {size} | {algorithm} | {heuristic} | {count["success"]} / {len(values)} | {count["timeout"]} | {len(instances[key])} | {fmt(ms(median(instances[key])))} |')
    lines += ['', '## 最高难度层', '',
              '分别展示各阶最高计划深度的结果；中位数只包含有成功重复的实例。', '',
              '| 阶数 | 最短解长 | 算法 | 启发函数 | 成功 / 计划 | 成功实例数 | 耗时中位数 (ms) | 扩展节点数中位数 |',
              '| ---: | ---: | --- | --- | ---: | ---: | ---: | ---: |']
    for row in group_rows:
        if row['depth'] != highest[row['size']]:
            continue
        nodes = row['nodes_expanded_instance_median']
        lines.append(f"| {row['size']} | {row['depth']} | {row['algorithm']} | {row['heuristic']} | {row['success']} / {row['planned']} | {row['successful_instance_count']} | {fmt(row['wall_ms_median'])} | {fmt(nodes, 0)} |")
    lines += ['', '最高难度层的配对比较如下。每个比值先在同一实例上计算右侧配置耗时除以左侧配置耗时，再对共同成功的实例取中位数；小于 1 表示右侧较快。只选择至少各有一次成功重复的实例，超时会影响样本覆盖。', '',
              '| 阶数 | 深度 | 左侧配置 | 右侧配置 | 共同成功实例 | 耗时比中位数 |',
              '| ---: | ---: | --- | --- | ---: | ---: |']
    for row in pair_rows:
        if row['depth'] != highest[row['size']]:
            continue
        algorithm_pair = row['comparison'] == 'algorithm' and row['left_heuristic'] == 'DISJOINT_PATTERN'
        heuristic_pair = row['comparison'] == 'heuristic' and row['left_heuristic'] == 'MANHATTAN' and row['right_heuristic'] == 'DISJOINT_PATTERN'
        if algorithm_pair or heuristic_pair:
            lines.append(f"| {row['size']} | {row['depth']} | {row['left_algorithm']} / {row['left_heuristic']} | {row['right_algorithm']} / {row['right_heuristic']} | {row['paired_instances']} | {fmt(row['median_right_over_left'])} |")
    lines += result_analysis(summary, highest)
    lines += ['', '## 监测覆盖', '',
              f'全程硬件采样 {hardware_samples} 次；{insufficient} / {len(records)} 次搜索按运行时长和采样数标记为采样不足。'
              '对不足的搜索不把单个采样解释为完整过程；墙钟、进程 CPU 时间与计数器在搜索边界单独记录，短搜索的进程 CPU 时间可能因计时分辨率而显示为 0。',
              f'全程系统总体 CPU 利用率采样均值 {fmt(statistics.mean(system_cpu) if system_cpu else None, 1)}%；'
              f'处理器封装最高采样温度 {fmt(max(cpu_temp) if cpu_temp else None, 1)} °C；'
              f'系统最低可用内存 {fmt(min(available) / 1024**3 if available else None, 2)} GiB；'
              f'工作进程最高采样 RSS {fmt(max(rss) / 1024**3 if rss else None, 2)} GiB。',
              '上述 RSS 是全程 1 秒采样曲线的离散观测，不能替代每次搜索的内核区间高水位或 cgroup 硬上限。',
              f"每次搜索的内核区间 RSS 峰值已记录 {overall_rss['kernel_peak_runs']} / {summary['completed']} 次（包括 timeout）；"
              f"全体已记录运行的区间峰值最大值为 {fmt(overall_rss['distributions']['rss_peak_bytes']['max'] / 1024**3 if overall_rss['distributions']['rss_peak_bytes']['max'] is not None else None, 3)} GiB。",
              '`rss_start_bytes`、`rss_end_bytes` 来自搜索边界的 `/proc/self/smaps_rollup` 页表扫描；`rss_peak_kernel_bytes` 来自每次搜索前向 `/proc/self/clear_refs` 写入 5 清零后读取的 `VmHWM`。'
              '`rss_peak_bytes` 取内核高水位、起点和终点读数的最大值，避免内核 RSS 记账误差导致报告峰值小于边界值；方法标识为 `linux_reset_vm_hwm`（也接受 `kernel_reset_vm_hwm`）。缺失值保持为空，不用 1 秒采样最大值补齐。',
              '该区间峰值结合内核高水位和精确边界扫描，仍是页级精度，不是连续逐字节的瞬时曲线。RSS 保留已加载 PDB、JVM 基线及搜索期间的全部驻留占用；长驻 JVM 的已提交堆和历史搜索也会影响后续起点 RSS，因此不能直接把跨配置 RSS 差异归因于算法本身，也不能减去起点 RSS 后把差值当作搜索内存开销。', '',
              '## 明细文件', '',
              '- `groups.csv`：各棋盘、深度、算法、启发函数组的成功率、成功耗时分布与指标，以及包括超时的全部运行 RSS 起点、终点、内核区间峰值的样本数、中位数、最大值和方法计数。',
              '- `runs.csv`：所有已记录运行（包括 timeout）的状态、耗时、CPU 时间及 RSS start/end/kernel peak/peak 字节值和峰值方法；缺失值留空。watchdog 强制结束时保留 `controller_wall_ns`，不把控制器等待时间填入搜索 `wall_ns`。',
              '- `pairs.csv`：同一实例的算法或启发函数配对比较，按阶数与深度分层，明确共同成功实例数和耗时比。',
              '- `abnormal-runs.csv`：所有失败、路径异常或解长不符的搜索。',
              '- `summary.json`：每个实例的重复状态、分层分布、逐实例配对数据及所有运行 RSS 分布；`run-hardware-summary.jsonl` 同时保留边界 RSS / 内核区间峰值和按搜索区间汇总的离散采样，两者分别报告。',
              '- `instances.jsonl`、`instance_candidates.jsonl`、`schedule.jsonl`、`results.jsonl`、`hardware.jsonl`、`jvm-memory.jsonl`：样本、认证、运行顺序及原始搜索和硬件时序。',
              '- `pilot-analysis.json`、`environment.json`、`config.json`：预实验、环境与资源限制。',
              '- `validation.json`、`manifest.json`：完整性核验结果和文件 SHA-256 清单；`worker.jar` 保存与正式启动前字节码哈希一致的 Maven 构建包，`source-snapshot.tar.gz` 保存源码与实验工具。', '']
    (root / '实验结果.md').write_text('\n'.join(lines), encoding='utf-8')
    print(f"Report: {root / '实验结果.md'}; {len(group_rows)} groups, {len(pair_rows)} comparisons, {len(abnormal)} abnormal runs")


if __name__ == '__main__':
    main()
