#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
PROJECT_ROOT="$PWD"
TEST_HEAP="${PDB_TEST_HEAP:-2g}"
mvn -q test
mkdir -p target/pdb-tests
javac -encoding UTF-8 -cp target/classes -d target/pdb-tests \
  tests/stud/g01/problem/npuzzle/PdbOptionsTest.java \
  tests/stud/g01/problem/npuzzle/HeuristicCompositionTest.java

# Alternate-goal tests must not replace the user's standard-goal caches.
RUN_DIR="$(mktemp -d "$PROJECT_ROOT/target/pdb-tests/run-XXXXXX")"
mkdir -p "$RUN_DIR/resources/pdb-cache"
for cache in resources/pdb-cache/pdb-3-*.bin; do
  if [[ -f "$cache" ]]; then cp "$cache" "$RUN_DIR/resources/pdb-cache/"; fi
done
if [[ "${1:-}" == "--component" ]]; then
  # A cold 7-8 build needs substantially more heap than loading existing tables.
  TEST_HEAP="${PDB_TEST_HEAP:-12g}"
  for cache in \
    resources/pdb-cache/component-le-4-0-7-1-2-3-4-5-6-7.bin \
    resources/pdb-cache/component-le-4-1-8-8-9-10-11-12-13-14-15.bin; do
    if [[ -f "$cache" ]]; then
      # A reflink is an independent file; rebuilding cannot overwrite the source cache.
      cp --reflink=auto "$cache" "$RUN_DIR/resources/pdb-cache/" 2>/dev/null \
        || cp "$cache" "$RUN_DIR/resources/pdb-cache/"
    fi
  done
fi
cd "$RUN_DIR"
java "-Xmx$TEST_HEAP" -cp "$PROJECT_ROOT/target/classes:$PROJECT_ROOT/target/pdb-tests" \
  stud.g01.problem.npuzzle.HeuristicCompositionTest
java "-Xmx$TEST_HEAP" -cp "$PROJECT_ROOT/target/classes:$PROJECT_ROOT/target/pdb-tests" \
  stud.g01.problem.npuzzle.PdbOptionsTest "$@"
