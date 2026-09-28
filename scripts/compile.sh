#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="$PROJECT_ROOT/out"

command -v java >/dev/null 2>&1 || {
  echo "错误：未找到 java，请安装项目规定的 JDK。" >&2
  exit 1
}
command -v javac >/dev/null 2>&1 || {
  echo "错误：未找到 javac，请安装完整 JDK，而不是仅安装 JRE。" >&2
  exit 1
}

echo "使用 Java：$(java -version 2>&1 | head -n 1)"
rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

find "$PROJECT_ROOT/src" -name '*.java' -print0 \
  | xargs -0 javac -encoding UTF-8 -d "$OUTPUT_DIR"

echo "编译成功：$OUTPUT_DIR"
