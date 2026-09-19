#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

BUILD_VARIANT="debug"
case "${1:-}" in
    release|debug)
        BUILD_VARIANT="$1"
        shift
        ;;
esac

SECEXP_EXPR=""
PEM_PATH=""
SDK_PATH=""
GRADLE_EXTRA_ARGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        secexp=*)
            SECEXP_EXPR="${1#secexp=}"
            ;;
        pem=*)
            PEM_PATH="${1#pem=}"
            ;;
        sdk=*)
            SDK_PATH="${1#sdk=}"
            ;;
        *)
            GRADLE_EXTRA_ARGS+=("$1")
            ;;
    esac
    shift
done

if [[ "$BUILD_VARIANT" == "release" ]]; then
    if [[ -n "$SECEXP_EXPR" ]]; then
        SECEXP_VALUE="$(python3 - "$SECEXP_EXPR" <<'PY'
import sys
expr = sys.argv[1]
allowed = {
    "__builtins__": {},
    "abs": abs,
    "bin": bin,
    "hex": hex,
    "int": int,
    "oct": oct,
    "pow": pow,
}
try:
    value = eval(expr, allowed, {})
except Exception as exc:
    raise SystemExit(f"错误: secexp 表达式无效: {exc}") from exc
if isinstance(value, bool) or not isinstance(value, int):
    raise SystemExit("错误: secexp 必须求值为整数")
print(value)
PY
)" || exit 1
    else
        SIGNING_KEY="${PEM_PATH:-$HOME/.ssh/NIST256p.pem}"
        if [[ -n "$PEM_PATH" && ! -f "$PEM_PATH" ]]; then
            echo "错误: 必须存在签名私钥 $PEM_PATH" >&2
            exit 1
        fi
        if [[ -z "$PEM_PATH" && ! -f "$SIGNING_KEY" ]]; then
            echo "错误: 必须存在签名私钥 $SIGNING_KEY" >&2
            exit 1
        fi
    fi
fi

# 清理残留环境变量，避免旧 shell 变量覆盖脚本中写死的默认值。
unset APPLICATION_ID VERSION_CODE VERSION_NAME APP_NAME BUILD_ABIS

APP_NAME="${APP_NAME:-点击使用中文输入法}"
APPLICATION_ID="${APPLICATION_ID:-com.kingzcheung.xime}"
VERSION_CODE="${VERSION_CODE:-20260914}"
VERSION_NAME="${VERSION_NAME:-最多19个英语ABCDEFGHIJKLMNOPQRS最多12个中文版本号字符串安装界面最多显示超过会用省略号表示长度17个字符android规范合法的是1024}"
APP_NAME="${VERSION_CODE: -4}输入法"

# Xime_rpc 的上级目录，保存项目级缓存和 Android 构建工具
BUILD_HOME="$(cd .. && pwd)"

ANDROID_HOME_DEFAULT=""
for candidate in \
    "${ANDROID_HOME:-}" \
    "${ANDROID_SDK_ROOT:-}" \
    "$BUILD_HOME/.cache/briefcase/tools/android_sdk" \
    "$BUILD_HOME/.buildozer/android/platform/android-sdk" \
    "$BUILD_HOME/sdk"; do
    if [[ -n "$candidate" && -f "$candidate/platforms/android-36/android.jar" ]]; then
        ANDROID_HOME_DEFAULT="$(cd "$candidate" && pwd)"
        break
    fi
done

if [[ -z "$ANDROID_HOME_DEFAULT" ]]; then
    echo "常规位置没有 SDK，正在通过 sudo find 全盘搜索 SDK 根目录。" >&2
    if sudo -n true 2>/dev/null; then
        platform_jar="$(sudo -n find / -type f -path '*/platforms/android-36/android.jar' -print -quit 2>/dev/null || true)"
    else
        platform_jar="$(find / -type f -path '*/platforms/android-36/android.jar' -print -quit 2>/dev/null || true)"
    fi
    if [[ -n "$platform_jar" ]]; then
        ANDROID_HOME_DEFAULT="$(cd "$(dirname "$(dirname "$(dirname "$platform_jar")")")" && pwd)"
    fi
fi

if [[ -z "$ANDROID_HOME_DEFAULT" ]]; then
    echo "找不到 Android SDK，请检查 $BUILD_HOME/.buildozer/android/platform/android-sdk" >&2
    exit 1
fi

# 让 Gradle 在没有 shell 环境变量的情况下也能定位 SDK。
LOCAL_PROPERTIES="$PWD/local.properties"
if [[ ! -f "$LOCAL_PROPERTIES" ]]; then
    cat > "$LOCAL_PROPERTIES" <<EOF
sdk.dir=$ANDROID_HOME_DEFAULT
ndk.dir=$ANDROID_HOME_DEFAULT/ndk/29.0.14206865
EOF
fi

echo "使用 Android SDK: $ANDROID_HOME_DEFAULT"

export ANDROID_HOME="$ANDROID_HOME_DEFAULT"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
ANDROID_NDK_DEFAULT="$ANDROID_HOME/ndk/29.0.14206865"
if [[ ! -d "$ANDROID_NDK_DEFAULT" && -d "$BUILD_HOME/.buildozer/android/platform/android-ndk-r25b" ]]; then
    ANDROID_NDK_DEFAULT="$BUILD_HOME/.buildozer/android/platform/android-ndk-r25b"
fi
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_NDK_DEFAULT}"
export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-$ANDROID_NDK_HOME}"
if [[ -z "${GRADLE_USER_HOME:-}" ]]; then
    GRADLE_USER_HOME="$BUILD_HOME/.gradle"
fi
if ! mkdir -p "$GRADLE_USER_HOME" 2>/dev/null; then
    echo "Gradle 缓存目录不可用: $GRADLE_USER_HOME，回退到 $HOME/.gradle" >&2
    GRADLE_USER_HOME="$HOME/.gradle"
    mkdir -p "$GRADLE_USER_HOME"
fi
export GRADLE_USER_HOME
export PATH="$BUILD_HOME/.local/bin:$BUILD_HOME/.gradle/wrapper/dists/gradle-8.14.3-all/h9bud5ffjflfoe91ghcb596uv/gradle-8.14.3/bin:$PATH"
BUILD_ABIS="${BUILD_ABIS:-arm64-v8a}"

if [[ -d /usr/lib/jvm/java-17-openjdk-amd64 ]]; then
    export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
fi

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
# ============================================================

ensure_native_dependency() {
    local repository="$1"
    local destination="$2"
    local marker="$destination/CMakeLists.txt"
    if [[ -f "$marker" ]]; then
        return
    fi

    local temporary_directory
    temporary_directory="$(mktemp -d)"
    trap 'rm -rf "$temporary_directory"' RETURN
    echo "缺少 native 依赖，正在下载: $repository"
    git clone --depth 1 --recurse-submodules "$repository" "$temporary_directory/source"
    mkdir -p "$destination"
    cp -a "$temporary_directory/source/." "$destination/"
    trap - RETURN
    rm -rf "$temporary_directory"
}

ensure_native_dependency "https://github.com/rime/librime.git" "app/src/main/jni/librime" || true
ensure_native_dependency "https://github.com/google/snappy.git" "app/src/main/jni/snappy" || true

ensure_signing_python_dep() {
    python3 - <<'PY'
import importlib.util
import sys
if importlib.util.find_spec("cryptography") is not None:
    raise SystemExit(0)
PY
    if [[ $? -eq 0 ]]; then
        return
    fi
    echo "缺少签名依赖 cryptography，正在安装..."
    python3 -m pip install --user cryptography
}

if [[ "$BUILD_VARIANT" == "release" ]]; then
    ensure_signing_python_dep || true
fi

# 使用数组传参，避免续行符问题
gradle_args=(
    "-PappName=$APP_NAME"
    "-PapplicationId=$APPLICATION_ID"
    "-PversionCode=$VERSION_CODE"
    "-PversionName=$VERSION_NAME"
    "-PbuildAbis=$BUILD_ABIS"
)

gradlew_cmd=(./gradlew --no-daemon)

if [[ "$BUILD_VARIANT" == "release" ]]; then
    "${gradlew_cmd[@]}" assembleRelease --quiet "${gradle_args[@]}" "${GRADLE_EXTRA_ARGS[@]}"

    release_dir="$PWD/app/build/outputs/apk/release"
    mapfile -t release_apks < <(find "$release_dir" -maxdepth 1 -type f -name '*.apk' ! -name '*-signed.apk' -print | sort)
    if [[ "${#release_apks[@]}" -eq 0 ]]; then
        echo "错误: 未找到 release APK: $release_dir" >&2
        exit 1
    fi

    for apk in "${release_apks[@]}"; do
        if [[ -n "${SECEXP_VALUE:-}" ]]; then
            python3 "$PWD/apk_sign.py" "$apk" --mode secexp --secexp "$SECEXP_VALUE" $( [[ -n "$SDK_PATH" ]] && printf '%s' "--sdk $SDK_PATH" )
        elif [[ -n "$PEM_PATH" ]]; then
            python3 "$PWD/apk_sign.py" "$apk" --pem "$PEM_PATH" $( [[ -n "$SDK_PATH" ]] && printf '%s' "--sdk $SDK_PATH" )
        else
            python3 "$PWD/apk_sign.py" "$apk" $( [[ -n "$SDK_PATH" ]] && printf '%s' "--sdk $SDK_PATH" )
        fi
    done
else
    "${gradlew_cmd[@]}" assembleDebug --quiet "${gradle_args[@]}" "${GRADLE_EXTRA_ARGS[@]}"
fi

echo "生成的 APK："
if [[ "$BUILD_VARIANT" == "release" ]]; then
    find "$PWD/app/build/outputs/apk/release" -maxdepth 1 -type f -name '*-signed.apk' -print
else
    find "$PWD/app/build/outputs/apk/debug" -maxdepth 1 -type f -name '*.apk' ! -name '*-signed.apk' -print
fi