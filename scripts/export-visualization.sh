#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
usage() {
  echo '用法：export-visualization.sh [输入 输出.json astar|ida|nida|dijkstra 启发函数 [--allow-pdb]]' >&2
  echo '默认：resources/problems-stage1.txt results.json astar MANHATTAN；每条记录仅运行一次搜索。' >&2
}
if [[ $# -gt 5 ]] || [[ $# -eq 5 && "$5" != "--allow-pdb" ]]; then
  usage
  exit 1
fi
for argument in "${@:1:4}"; do
  if [[ "$argument" == --* ]]; then
    usage
    exit 1
  fi
done
INPUT="${1:-$PROJECT_ROOT/resources/problems-stage1.txt}"
OUTPUT="${2:-$PROJECT_ROOT/results.json}"
if [[ ! -f "$INPUT" ]]; then
  if [[ -f "$PROJECT_ROOT/$INPUT" ]]; then
    INPUT="$PROJECT_ROOT/$INPUT"
  elif [[ -f "$PROJECT_ROOT/resources/$INPUT" ]]; then
    INPUT="$PROJECT_ROOT/resources/$INPUT"
  else
    echo "错误：问题文件不存在：$INPUT" >&2
    exit 1
  fi
fi
INPUT="$(cd "$(dirname "$INPUT")" && pwd)/$(basename "$INPUT")"
# Resolve a supplied output relative to the caller, before entering the repository.
OUTPUT="$(cd "$(dirname "$OUTPUT")" && pwd)/$(basename "$OUTPUT")"
read -r -a JAVA_OPTIONS <<< "${JAVA_OPTS:-}"
"$SCRIPT_DIR/compile.sh"
cd "$PROJECT_ROOT"
REVISION="$(git rev-parse HEAD)"
if ! git diff --quiet HEAD -- src || [ -n "$(git ls-files --others --exclude-standard src)" ]; then REVISION="${REVISION}-dirty"; fi
exec java -Xmx2g "${JAVA_OPTIONS[@]}" "-Dnpuzzle.revision=$REVISION" -cp target/classes stud.g01.runner.SolutionExporter \
  "$INPUT" "$OUTPUT" \
  "${3:-astar}" "${4:-MANHATTAN}" "${@:5}"
