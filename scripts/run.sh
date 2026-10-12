#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

FEEDER="stud.g01.runner.PuzzleFeeder"

usage() {
  cat <<EOF
用法：$(basename "$0") <问题文件> <搜索引擎> [启发函数列表]

搜索引擎：astar | ida | nida | dijkstra
启发函数列表：逗号分隔，默认见下方各阶段配置

示例：
  $(basename "$0") resources/problems-stage1.txt astar MISPLACED,MANHATTAN
  $(basename "$0") resources/problems-stage2.txt astar MANHATTAN
  $(basename "$0") resources/problems-stage3.txt nida DISJOINT_PATTERN
EOF
}

if [[ $# -lt 2 || $# -gt 3 ]]; then
  usage >&2
  exit 1
fi

PROBLEMS="$1"
ENGINE="$2"
HEURISTICS="${3:-}"

if [[ ! -f "$PROBLEMS" ]]; then
  PROBLEMS="$PROJECT_ROOT/resources/$1"
  if [[ ! -f "$PROBLEMS" ]]; then
    echo "错误：问题文件不存在：$1" >&2
    usage >&2
    exit 1
  fi
fi

"$SCRIPT_DIR/compile.sh"
cd "$PROJECT_ROOT"
java ${JAVA_OPTS:-} -cp out core.runner.SearchTester "$PROBLEMS" "$FEEDER" "$ENGINE" "$HEURISTICS"
