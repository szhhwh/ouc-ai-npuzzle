#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
./mvnw -B -ntp compile
REVISION="$(git rev-parse HEAD)"
if ! git diff --quiet HEAD -- src || [ -n "$(git ls-files --others --exclude-standard src)" ]; then REVISION="${REVISION}-dirty"; fi
java -Xmx2g "-Dnpuzzle.revision=$REVISION" -cp target/classes stud.g01.runner.SearchTraceExporter "${1:-visualization/java-search-traces.js}"
