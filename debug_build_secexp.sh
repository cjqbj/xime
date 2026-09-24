#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

# 清理残留环境变量，避免旧 shell 变量覆盖脚本中写死的默认值。
unset APPLICATION_ID VERSION_CODE VERSION_NAME APP_NAME BUILD_ABIS

SECEXP="${SECEXP:-1}"
APPLICATION_ID="${APPLICATION_ID:-com.qgb.xime}"
VERSION_CODE="${VERSION_CODE:-20260915}"
VERSION_NAME="${VERSION_NAME:-${VERSION_CODE}SECEXP=${SECEXP}长度17混合}"
APP_NAME="${VERSION_CODE: -4}输入法"

BUILD_ABIS="${BUILD_ABIS:-arm64-v8a}"
OUT_DIR="${OUT_DIR:-$PROJECT_DIR/out/debug-secexp}"
CACHE_DIR="${CACHE_DIR:-$PROJECT_DIR/../.cache}"

# 任何一次变更都必须显式传入；禁止把 SECEXP 或构建环境写进版本名/包名。
export APP_NAME APPLICATION_ID VERSION_CODE VERSION_NAME BUILD_ABIS SECEXP

mkdir -p "$OUT_DIR" "$CACHE_DIR"

# 1)定位 Android SDK（兼容 build.sh 的默认路径）
ANDROID_HOME_DEFAULT=""
for candidate in \
    "${ANDROID_HOME:-}" \
    "${ANDROID_SDK_ROOT:-}" \
    "$PROJECT_DIR/../.cache/briefcase/tools/android_sdk" \
    "$PROJECT_DIR/../.buildozer/android/platform/android-sdk" \
    "$PROJECT_DIR/../sdk"; do
    if [[ -n "$candidate" && -f "$candidate/platforms/android-36/android.jar" ]]; then
        ANDROID_HOME_DEFAULT="$(cd "$candidate" && pwd)"
        break
    fi
done

if [[ -z "$ANDROID_HOME_DEFAULT" ]]; then
    platform_jar="$(find / -type f -path '*/platforms/android-36/android.jar' -print -quit 2>/dev/null || true)"
    if [[ -n "$platform_jar" ]]; then
        ANDROID_HOME_DEFAULT="$(cd "$(dirname "$(dirname "$(dirname "$platform_jar")")")" && pwd)"
    fi
fi

if [[ -z "$ANDROID_HOME_DEFAULT" ]]; then
    echo "错误: 未找到 Android SDK，请先设置 ANDROID_HOME 或确保存在 android-36 平台。" >&2
    exit 1
fi
export ANDROID_HOME="$ANDROID_HOME_DEFAULT"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME_DEFAULT}"


# ============================================================
# 子模块同步：只认 .gitmodules 里的 path/url，rsync 权威覆盖
# ============================================================
SUBMODULE_SECTION="[submodule \"app/src/main/python/multi_mqtt\"]"
SUBMODULE_PATH_VALUE=""
SUBMODULE_URL_VALUE=""
current_section=""

if [[ -f "$PWD/.gitmodules" ]]; then
    while IFS= read -r line; do
        if [[ "$line" =~ ^\[submodule\ \".*\"\]$ ]]; then
            current_section="$line"
        fi
        if [[ "$current_section" == "$SUBMODULE_SECTION" ]]; then
            if [[ "$line" =~ ^[[:space:]]*path[[:space:]]*=[[:space:]]*(.*)$ ]]; then
                SUBMODULE_PATH_VALUE="${line##*=}"
                SUBMODULE_PATH_VALUE="${SUBMODULE_PATH_VALUE//[[:space:]]/}"
            elif [[ "$line" =~ ^[[:space:]]*url[[:space:]]*=[[:space:]]*(.*)$ ]]; then
                SUBMODULE_URL_VALUE="${line##*=}"
                SUBMODULE_URL_VALUE="${SUBMODULE_URL_VALUE//[[:space:]]/}"
            fi
        fi
    done < "$PWD/.gitmodules"
fi

if [[ -z "$SUBMODULE_PATH_VALUE" || -z "$SUBMODULE_URL_VALUE" ]]; then
    echo "错误: 无法从 .gitmodules 解析 app/src/main/python/multi_mqtt 的 path/url，构建中止。" >&2
    exit 1
fi

target_dir="$PWD/$SUBMODULE_PATH_VALUE"
source_dir="$SUBMODULE_URL_VALUE"
# 相对路径统一转成绝对路径（相对脚本所在目录）
if [[ "$source_dir" != /* ]]; then
    source_dir="$PWD/$source_dir"
fi
# 规范化路径（去掉 ../ 之类）
if [[ -d "$source_dir" ]]; then
    source_dir="$(cd "$source_dir" && pwd)"
fi

if [[ -d "$source_dir" ]]; then
    echo "同步目录: $source_dir -> $target_dir"
    mkdir -p "$target_dir"
    # 清掉目标里可能残留的 .git（旧的 submodule 痕迹），否则它还是个 git 仓库
    rm -rf "$target_dir/.git"
    rsync --delete --delete-excluded \
          --exclude=.git --exclude=.github --exclude=.venv \
          -a "$source_dir/" "$target_dir/" \
        || echo "警告: rsync 返回非零，忽略并继续构建。" >&2
else
    echo "警告: 同步源目录不存在: $source_dir，跳过 rsync。" >&2
fi
[ ! -f "$PWD/app/src/main/python/aliyun_git.py" ] && wget -O "$PWD/app/src/main/python/aliyun_git.py" https://github.com/QGB/git.bat/raw/refs/heads/master/aliyun_git.py
# ============================================================


# 2) 清理旧产物，确保输出是这个 secexp 对应的新包
find "$OUT_DIR" -maxdepth 1 -type f -name 'Xime-*.apk' -delete 2>/dev/null || true

# 3) 先清理并编译 debug APK，确保同一输入输出一致。
rm -rf "$PROJECT_DIR/app/build/outputs/apk/debug" "$PROJECT_DIR/out/debug-secexp"
mkdir -p "$PROJECT_DIR/out/debug-secexp"
./gradlew --no-daemon assembleDebug \
  --quiet \
  -PappName="$APP_NAME" \
  -PapplicationId="$APPLICATION_ID" \
  -PversionCode="$VERSION_CODE" \
  -PversionName="$VERSION_NAME" \
  -PbuildAbis="$BUILD_ABIS"

# 4) 找到编译出的 APK
APK_PATH="$(find "$PROJECT_DIR/app/build/outputs/apk/debug" -maxdepth 1 -type f -name "Xime-${VERSION_CODE}-${BUILD_ABIS}.apk" -print -quit)"
if [[ -z "$APK_PATH" ]]; then
    APK_PATH="$(find "$PROJECT_DIR/app/build/outputs/apk/debug" -maxdepth 1 -type f -name '*.apk' -print -quit)"
fi
if [[ -z "$APK_PATH" ]]; then
    echo "错误: 未找到 debug APK，编译失败或输出命名和预期不一致。" >&2
    exit 1
fi

# 5) 先把 ZIP 元数据归一化，消除时间戳和文件顺序造成的差异，再签名
REPACKED_APK="$PROJECT_DIR/app/build/outputs/apk/debug/repacked-${VERSION_CODE}-${BUILD_ABIS}.apk"
python3 - "$APK_PATH" "$REPACKED_APK" <<'PY'
import sys
import zipfile
from pathlib import Path

src = Path(sys.argv[1])
dst = Path(sys.argv[2])

with zipfile.ZipFile(src, 'r') as zin:
    members = sorted(zin.infolist(), key=lambda i: i.filename)
    with zipfile.ZipFile(dst, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
        for info in members:
            data = zin.read(info.filename)
            new_info = zipfile.ZipInfo(info.filename)
            new_info.compress_type = info.compress_type
            new_info.date_time = (1980, 1, 1, 0, 0, 0)
            new_info.extra = b''
            new_info.comment = b''
            new_info.create_system = 3
            new_info.external_attr = info.external_attr
            new_info.internal_attr = info.internal_attr
            zout.writestr(new_info, data)
PY

APK_PATH="$REPACKED_APK"

# 6) 使用 apk_sign.py 通过 secexp 确定性生成签名密钥并签名
python3 "$PROJECT_DIR/apk_sign.py" "$APK_PATH" \
  --mode secexp \
  --secexp "$SECEXP" \
  --sdk "$ANDROID_HOME_DEFAULT"

SIGNED_APK="${APK_PATH%.apk}-signed.apk"
if [[ ! -f "$SIGNED_APK" ]]; then
    echo "错误: 签名后 APK 未生成：$SIGNED_APK" >&2
    exit 1
fi

# 7) 再复制一份到导出目录，方便直接取用，并保留到全局缓存目录用于真实文件对比
OUT_APK="$OUT_DIR/Xime-${VERSION_CODE}-${BUILD_ABIS}.apk"
#CACHE_APK="$CACHE_DIR/Xime-${VERSION_CODE}-${BUILD_ABIS}-SECEXP-${SECEXP}.apk"
mv -f "$SIGNED_APK" "$OUT_APK"
#cp -f "$SIGNED_APK" "$CACHE_APK"

APKSIGNER_BIN="$(find "$ANDROID_HOME_DEFAULT/build-tools" -type f -name apksigner -print 2>/dev/null | sort | tail -n 1)"
AAPT_BIN="$(find "$ANDROID_HOME_DEFAULT/build-tools" -type f -name aapt -print 2>/dev/null | sort | tail -n 1)"
if [[ -z "$APKSIGNER_BIN" || ! -x "$APKSIGNER_BIN" ]]; then
    echo "错误: 未找到可执行的 apksigner，SDK 路径: $ANDROID_HOME_DEFAULT" >&2
    exit 1
fi
if [[ -z "$AAPT_BIN" || ! -x "$AAPT_BIN" ]]; then
    echo "错误: 未找到可执行的 aapt，SDK 路径: $ANDROID_HOME_DEFAULT" >&2
    exit 1
fi

echo
echo "=== 已生成 APK ==="
ls -l "$OUT_APK"

echo
echo "=== apksigner verify --print-certs ==="
"$APKSIGNER_BIN" verify --print-certs "$OUT_APK" 2>&1 | sed -n '1,40p'

echo
echo "=== aapt badging ==="
"$AAPT_BIN" dump badging "$OUT_APK" | grep -E 'package:|versionCode|versionName' || true

echo
echo "SECEXP=$SECEXP"
echo "APK=$OUT_APK"
#echo "CACHE_APK=$CACHE_APK"
