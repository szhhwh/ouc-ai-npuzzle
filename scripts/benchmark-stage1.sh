#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT"
./mvnw -q -DskipTests test-compile
java -ea -cp "target/classes:target/test-classes" \
  -Dnpuzzle.stage1.file="$PROJECT_ROOT/resources/problems-stage1.txt" \
  stud.g01.benchmark.Stage1PerformanceBenchmark "$@"
