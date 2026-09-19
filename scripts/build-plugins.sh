#!/bin/bash
# 构建所有 Lua 插件 xipk 包
#
# 使用方式：
#   bash scripts/build-plugins.sh
#
# Lua 脚本插件 = 纯文件目录（plugins/<name>/ 含 manifest.yaml），无需 gradle，
# 直接 zip 打包到 build/plugin-release/*.xipk

set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUTPUT_DIR="$PROJECT_DIR/build/plugin-release"

echo "=== 构建 Lua 插件 xipk 包 ==="
echo "输出目录: $OUTPUT_DIR"

mkdir -p "$OUTPUT_DIR"

for plugin_dir in "$PROJECT_DIR"/plugins/*/; do
  manifest="$plugin_dir/manifest.yaml"
  if [ ! -f "$manifest" ]; then
    continue
  fi

  name=$(basename "$plugin_dir")
  version=$(sed -n 's/^[[:space:]]*version:[[:space:]]*//p' "$manifest" | head -1 | tr -d '"')
  version=${version:-0.0.0}
  out="$OUTPUT_DIR/${name}-${version}.xipk"
  rm -f "$out"

  python3 - "$plugin_dir" "$out" <<'PYEOF'
import sys, os, zipfile
src, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(src):
        dirs[:] = [d for d in dirs if not d.startswith('.')]
        for f in files:
            if f.startswith('.'):
                continue
            full = os.path.join(root, f)
            rel = os.path.relpath(full, src)
            z.write(full, rel)
PYEOF
  echo "Lua : $name-$version.xipk"
done

echo ""
echo "=== 完成 ==="
ls -lh "$OUTPUT_DIR"/*.xipk
