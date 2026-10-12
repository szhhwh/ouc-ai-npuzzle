#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

"$SCRIPT_DIR/compile.sh"
cd "$PROJECT_ROOT"
java -cp out core.runner.SearchTester \
  resources/problems-stage1.txt NPUZZLE 1 stud.g01.runner.PuzzleFeeder
