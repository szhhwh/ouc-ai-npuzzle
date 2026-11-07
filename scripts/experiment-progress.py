#!/usr/bin/env python3
"""Read the persisted experiment progress without interacting with the running worker."""
import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path
import time


def records(path):
    if not path.exists():
        return
    with path.open(encoding='utf-8') as handle:
        for line in handle:
            if not line.endswith('\n'):
                break  # A concurrent writer may not yet have flushed its final line.
            yield json.loads(line)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', default='result/experiment')
    args = parser.parse_args()
    root = Path(args.directory)
    config_path = root / 'config.json'
    if not config_path.exists():
        parser.exit(1, f'尚未找到实验配置：{config_path}\n')
    config = json.loads(config_path.read_text())
    counts, done = Counter(), set()
    rss_count = 0
    last_end = config['created_epoch_ns']
    for row in records(root / 'results.jsonl'):
        done.add(row['run_id'])
        counts[row['status']] += 1
        rss_count += isinstance(row.get('rss_peak_bytes'), (int, float))
        last_end = max(last_end, row.get('end_epoch_ns') or last_end)
    current = None
    for row in records(root / 'starts.jsonl'):
        if row['run_id'] not in done:
            current = row
    launches = sum(1 for _ in records(root / 'worker-launches.jsonl'))
    total = config['fixed']['planned_runs']
    end = last_end if len(done) == total else time.time_ns()
    elapsed = max(0, end - config['created_epoch_ns']) / 1e9
    print(f'更新时间：{datetime.now().astimezone().isoformat(timespec="seconds")}')
    print(f'完成：{len(done):,} / {total:,}（{len(done) / total * 100:.2f}%）')
    print('状态：' + '，'.join(f'{name}={counts[name]}' for name in ('success', 'timeout', 'oom', 'error')))
    print(f'逐次 RSS 已记录：{rss_count:,} / {len(done):,}')
    print(f'搜索超时：{config["fixed"]["timeout_seconds"]:g} 秒；内存上限：{config["fixed"]["memory_max_bytes"] / 1024**3:g} GiB')
    print(f'本批经过：{elapsed / 60:.1f} 分钟；JVM 启动次数：{launches}')
    if len(done) == total:
        report = root / '实验结果.md'
        print(f'全部搜索与报告已完成：{report.resolve()}' if report.exists()
              else '全部搜索已记录；汇总文件由运行命令继续生成。')
    elif current:
        pid = current.get('pid')
        alive = pid is not None and Path(f'/proc/{pid}').exists()
        state = '进程存在' if alive else '进程已退出，等待控制器记录或恢复'
        start = current.get('start_epoch_ns') or current.get('dispatch_epoch_ns')
        seconds = max(0, time.time_ns() - start) / 1e9 if start else None
        duration = f'，已经过 {seconds:.1f} 秒' if seconds is not None else ''
        print(f'当前：{current["run_id"]}，PID={pid}，{state}{duration}')
    else:
        print('当前：搜索间隔、预热或控制器尚未派发下一次搜索。')
    print(f'数据目录：{root.resolve()}')


if __name__ == '__main__':
    main()
