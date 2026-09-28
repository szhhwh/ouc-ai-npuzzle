#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TEST_OUTPUT_DIR="$PROJECT_ROOT/out-test"

"$SCRIPT_DIR/compile.sh"
rm -rf "$TEST_OUTPUT_DIR"
mkdir -p "$TEST_OUTPUT_DIR"

find "$PROJECT_ROOT/test" -name '*.java' -print0 \
  | xargs -0 javac -encoding UTF-8 -cp "$PROJECT_ROOT/out" -d "$TEST_OUTPUT_DIR"

java -ea -cp "$PROJECT_ROOT/out:$TEST_OUTPUT_DIR" \
  stud.g01.problem.npuzzle.NPuzzleFoundationTest
