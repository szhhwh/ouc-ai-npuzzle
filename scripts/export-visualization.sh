#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"
if [[ $# -gt 6 ]] || [[ $# -eq 6 && "$6" != "--allow-pdb" ]]; then
  echo '用法：export-visualization.sh [输入 输出 引擎 启发函数 轮数 [--allow-pdb]]' >&2
  exit 1
fi
./mvnw -B -ntp compile
REVISION="$(git rev-parse HEAD)"
if ! git diff --quiet HEAD -- src || [ -n "$(git ls-files --others --exclude-standard src)" ]; then REVISION="${REVISION}-dirty"; fi
java -Xmx2g "-Dnpuzzle.revision=$REVISION" -cp target/classes stud.g01.runner.SolutionExporter \
  "${1:-resources/problems-stage1.txt}" "${2:-visualization/results.json}" \
  "${3:-astar}" "${4:-MANHATTAN,MISPLACED}" "${5:-3}" "${@:6}"
