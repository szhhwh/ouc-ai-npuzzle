#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"
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
./mvnw -B -ntp compile
REVISION="$(git rev-parse HEAD)"
if ! git diff --quiet HEAD -- src || [ -n "$(git ls-files --others --exclude-standard src)" ]; then REVISION="${REVISION}-dirty"; fi
java -Xmx2g "-Dnpuzzle.revision=$REVISION" -cp target/classes stud.g01.runner.SolutionExporter \
  "${1:-resources/problems-stage1.txt}" "${2:-results.json}" \
  "${3:-astar}" "${4:-MANHATTAN}" "${@:5}"
