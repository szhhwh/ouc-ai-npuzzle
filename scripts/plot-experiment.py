#!/usr/bin/env python3
"""Plot all controlled-variable experiments in one paper-style SVG/PNG figure."""

import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import statistics

import matplotlib

matplotlib.use("Agg")

from matplotlib import pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.text import Text
from matplotlib.ticker import FixedLocator, LogFormatterMathtext, NullLocator
import numpy as np


REPO = Path(__file__).resolve().parents[1]
ALGORITHMS = (("astar", "A*"), ("ida", "IDA*"), ("nida", "NIDA*"))
HEURISTICS = (
    ("MISPLACED", "Misplaced", "#D55E00", "o", "-"),
    ("MANHATTAN", "Manhattan", "#0072B2", "s", "--"),
    ("DISJOINT_PATTERN", "PDB", "#009E73", "^", "-."),
)


def read_and_validate(root):
    summary = json.loads((root / "summary.json").read_text(encoding="utf-8"))
    raw = {}
    with (root / "results.jsonl").open(encoding="utf-8") as handle:
        for line in handle:
            run = json.loads(line)
            if run["run_id"] in raw:
                raise ValueError(f"Duplicate run ID: {run['run_id']}")
            raw[run["run_id"]] = run
    if len(raw) != summary["completed"]:
        raise ValueError("Raw run count differs from the summary.")
    if not summary["completed"] == summary["planned"]:
        raise ValueError("The experiment is incomplete.")
    covered = set()
    grouped = defaultdict(list)
    for item in summary["instance_medians"]:
        repeats = [raw[r["run_id"]] for r in item["statuses"]]
        if len(repeats) != 3:
            raise ValueError("Expected three repetitions per instance/configuration.")
        for run, status in zip(repeats, item["statuses"]):
            if run["status"] != status["status"]:
                raise ValueError("Per-instance status differs from the raw records.")
            key = ("size", "depth", "instance_id", "algorithm", "heuristic")
            if any(run[field] != item[field] for field in key):
                raise ValueError("Per-instance identity differs from the raw record.")
            if run["run_id"] in covered:
                raise ValueError("Raw run is assigned to multiple instance summaries.")
            covered.add(run["run_id"])
        successes = [run for run in repeats if run["status"] == "success"]
        if len(successes) != item["successful_repetitions"]:
            raise ValueError("Successful repeat count differs from the raw records.")
        expected = statistics.median(r["wall_ns"] for r in successes) if successes else None
        if item["median_success_wall_ns"] != expected:
            raise ValueError("Per-instance median differs from the raw records.")
        grouped[(item["size"], item["depth"], item["algorithm"], item["heuristic"])].append(item)
    if covered != set(raw):
        raise ValueError("Instance summaries do not cover every raw search.")
    for group in summary["groups"]:
        items = grouped[(group["size"], group["depth"], group["algorithm"], group["heuristic"])]
        statuses = Counter(s["status"] for item in items for s in item["statuses"])
        if dict(statuses) != group["status_counts"]:
            raise ValueError("Group statuses differ from the raw records.")
    return summary, grouped


def horizontal_jitter(instance_id):
    value = int.from_bytes(hashlib.sha256(instance_id.encode()).digest()[:4], "big")
    return (value / (2**32 - 1) - 0.5) * 0.30


def check_layout(fig):
    fig.canvas.draw()
    renderer = fig.canvas.get_renderer()
    boxes = []
    for text in fig.findobj(Text):
        if not text.get_visible() or not text.get_text().strip():
            continue
        box = text.get_window_extent(renderer)
        if box.width == 0 or box.height == 0:
            continue
        if (
            box.x0 < fig.bbox.x0 or box.y0 < fig.bbox.y0
            or box.x1 > fig.bbox.x1 or box.y1 > fig.bbox.y1
        ):
            raise RuntimeError(f"Text is clipped: {text.get_text()!r}")
        boxes.append((text.get_text(), box))
    for index, (label, box) in enumerate(boxes):
        for other_label, other_box in boxes[index + 1:]:
            if box.overlaps(other_box):
                raise RuntimeError(f"Text overlaps: {label!r} and {other_label!r}")


def build_figure(summary, grouped):
    plt.rcParams.update({
        "font.family": "serif",
        "font.serif": ["Times New Roman", "Liberation Serif", "DejaVu Serif"],
        "mathtext.fontset": "stix",
        "font.size": 8.5,
        "axes.titlesize": 9.5,
        "axes.labelsize": 9,
        "xtick.labelsize": 8,
        "ytick.labelsize": 8,
        "text.color": "#222222",
        "axes.labelcolor": "#222222",
        "axes.edgecolor": "#777777",
        "axes.linewidth": 0.55,
        "axes.spines.top": False,
        "axes.spines.right": False,
        "xtick.color": "#444444",
        "ytick.color": "#444444",
        "xtick.major.size": 2.5,
        "ytick.major.size": 2.5,
        "xtick.major.width": 0.5,
        "ytick.major.width": 0.5,
        "figure.facecolor": "white",
        "axes.facecolor": "white",
        "svg.fonttype": "path",
        "svg.hashsalt": "npuzzle-controlled-experiments",
        "savefig.facecolor": "white",
    })
    fig, axes = plt.subplots(2, 3, figsize=(7.25, 4.35), sharey=True, dpi=150)
    fig.subplots_adjust(
        left=0.095, right=0.99, bottom=0.12, top=0.865,
        hspace=0.40, wspace=0.16,
    )
    fig.legend(
        handles=[
            Line2D([], [], color=color, linestyle=style, marker=marker,
                   linewidth=1.15, markersize=3.0, label=label)
            for _, label, color, marker, style in HEURISTICS
        ],
        loc="upper center", bbox_to_anchor=(0.55, 0.99),
        ncols=3, frameon=False, fontsize=9,
        handlelength=2.3, columnspacing=2.0, handletextpad=0.6,
    )
    fig.supxlabel("Solution depth", x=0.55, y=0.018, fontsize=10)
    fig.supylabel("Time / ms", x=0.012, y=0.50, fontsize=10)
    rendered_points = 0
    trend_points = 0
    for row, size in enumerate((3, 4)):
        depths = sorted({i["depth"] for i in summary["instance_medians"] if i["size"] == size})
        for column, (algorithm, label) in enumerate(ALGORITHMS):
            ax = axes[row, column]
            ax.set_title(f"{size}×{size}  {label}", pad=6)
            ax.set_yscale("log")
            ax.set_ylim(0.003, 20000)
            ax.yaxis.set_major_locator(FixedLocator([0.01, 1, 100, 10000]))
            ax.yaxis.set_major_formatter(LogFormatterMathtext())
            ax.yaxis.set_minor_locator(NullLocator())
            ax.set_xlim(depths[0] - 1, depths[-1] + 1)
            ax.set_xticks([6, 12, 18, 24, 30] if size == 3 else [5, 10, 20, 30, 40])
            ax.grid(axis="y", color="#DDDDDD", linewidth=0.45, alpha=0.8)
            ax.set_axisbelow(True)
            ax.tick_params(axis="both", pad=3)
            for heuristic, _, color, marker, style in HEURISTICS:
                low, middle, high = [], [], []
                for depth in depths:
                    # Only medians with all three repetitions successful contribute
                    # to the distribution. A timeout never becomes a completion time.
                    items = [
                        item for item in grouped[(size, depth, algorithm, heuristic)]
                        if item["successful_repetitions"] == 3
                    ]
                    values = [item["median_success_wall_ns"] / 1e6 for item in items]
                    if values:
                        q1, median, q3 = np.quantile(values, [0.25, 0.5, 0.75])
                        trend_points += 1
                        ax.scatter(
                            [depth + horizontal_jitter(item["instance_id"]) for item in items],
                            values, color=color, s=2.5, alpha=0.14, edgecolors="none",
                            zorder=2,
                        )
                    else:
                        q1 = median = q3 = np.nan
                    low.append(q1)
                    middle.append(median)
                    high.append(q3)
                    rendered_points += len(values)
                ax.fill_between(
                    depths, low, high, color=color, alpha=0.11, linewidth=0, zorder=1
                )
                ax.plot(
                    depths, middle, color=color, linestyle=style,
                    linewidth=1.1, marker=marker, markersize=2.7,
                    markeredgecolor="white", markeredgewidth=0.35, zorder=3,
                )
    check_layout(fig)
    return fig, rendered_points, trend_points


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=REPO / "result" / "experiment")
    parser.add_argument("--output", type=Path, default=REPO / "result" / "figures")
    parser.add_argument("--dpi", type=int, default=600)
    args = parser.parse_args()
    if args.dpi < 72:
        parser.error("--dpi must be at least 72")
    summary, grouped = read_and_validate(args.input)
    fig, point_count, trend_count = build_figure(summary, grouped)
    args.output.mkdir(parents=True, exist_ok=True)
    for extension in ("svg", "png"):
        path = args.output / f"controlled_experiments.{extension}"
        metadata = {"Creator": "scripts/plot-experiment.py"}
        if extension == "svg":
            metadata["Date"] = None
        fig.savefig(path, dpi=args.dpi, metadata=metadata)
        print(path.resolve(), flush=True)
    plt.close(fig)
    print(
        f"Validated {summary['completed']} raw runs; plotted {point_count} "
        f"instance medians and {trend_count} depth summaries.", flush=True,
    )


if __name__ == "__main__":
    main()
