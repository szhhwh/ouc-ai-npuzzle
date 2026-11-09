#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

FEEDER="stud.g01.runner.PuzzleFeeder"

usage() {
  cat <<EOF
用法：$(basename "$0") <问题文件> <搜索引擎> [启发函数列表]

搜索引擎：astar | ida | nida | dijkstra
启发函数列表：逗号分隔，默认 MANHATTAN

示例：
  $(basename "$0") resources/problems-stage1.txt astar MISPLACED,MANHATTAN
  $(basename "$0") resources/problems-teacher-astar.txt astar MANHATTAN
  $(basename "$0") resources/problems-stage3.txt nida DISJOINT_PATTERN
EOF
}

if [[ $# -lt 2 || $# -gt 3 ]]; then
  usage >&2
  exit 1
fi

PROBLEMS="$1"
ENGINE="$2"
HEURISTICS="${3-MANHATTAN}"

# Prefer caller-relative paths, then repository paths and resource basenames.
if [[ ! -f "$PROBLEMS" ]]; then
  if [[ -f "$PROJECT_ROOT/$1" ]]; then
    PROBLEMS="$PROJECT_ROOT/$1"
  elif [[ -f "$PROJECT_ROOT/resources/$1" ]]; then
    PROBLEMS="$PROJECT_ROOT/resources/$1"
  else
    echo "错误：问题文件不存在：$1" >&2
    usage >&2
    exit 1
  fi
fi
PROBLEMS="$(cd "$(dirname "$PROBLEMS")" && pwd)/$(basename "$PROBLEMS")"

# JAVA_OPTS remains a whitespace-separated option list; do not evaluate shell code.
read -r -a JAVA_OPTIONS <<< "${JAVA_OPTS:-}"
"$SCRIPT_DIR/compile.sh"
cd "$PROJECT_ROOT"
exec java "${JAVA_OPTIONS[@]}" -cp target/classes core.runner.SearchTester "$PROBLEMS" "$FEEDER" "$ENGINE" "$HEURISTICS"
