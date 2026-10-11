#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_DIR="$PROJECT_DIR/scripts/rime_keytest"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

if ! pkg-config --exists rime; then
    echo "缺少 librime 开发依赖：请先安装 librime-dev、pkg-config 和 C++17 编译器。" >&2
    exit 2
fi

cmake -S "$TEST_DIR" -B "$BUILD_DIR/build" -DCMAKE_BUILD_TYPE=Release
cmake --build "$BUILD_DIR/build" --parallel
"$BUILD_DIR/build/rime_key_candidate_test" \
    "$TEST_DIR/data" "$BUILD_DIR/user-data"
