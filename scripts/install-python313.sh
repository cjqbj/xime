#!/usr/bin/env bash
# 用法：bash install-python313.sh（自动请求 sudo）
# 下载预编译 standalone；支持 glibc Linux x86_64/aarch64，不在本机编译。
set -Eeuo pipefail

readonly TARGET="/usr/bin/python3.13"
readonly INSTALL_ROOT="/opt/python-build-standalone"
readonly RELEASE_API="https://api.github.com/repos/astral-sh/python-build-standalone/releases/latest"
readonly SCRIPT_PATH="$(readlink -f -- "${BASH_SOURCE[0]}")"

TEMP_DIR=""
STAGING_DIR=""
ARCHIVE=""

report_error() {
    local status=$?
    printf '\n错误：命令执行失败（退出码 %s，脚本第 %s 行）：%s\n' \
        "$status" "${BASH_LINENO[0]:-未知}" "${BASH_COMMAND:-未知命令}" >&2
    exit "$status"
}

cleanup() {
    local status=$?
    trap - EXIT

    if [[ -n "$STAGING_DIR" && -d "$STAGING_DIR" ]]; then
        if ! rm -rf -- "$STAGING_DIR"; then
            printf '错误：无法清理临时目录：%s\n' "$STAGING_DIR" >&2
            status=1
        fi
    fi
    if [[ -n "$TEMP_DIR" && -d "$TEMP_DIR" ]]; then
        if ! rm -rf -- "$TEMP_DIR"; then
            printf '错误：无法清理下载临时目录：%s\n' "$TEMP_DIR" >&2
            status=1
        fi
    fi
    exit "$status"
}

trap report_error ERR
trap cleanup EXIT

printf '%s\n' '============================================================'
printf '%s\n' ' Python 3.13 独立版安装器'
printf '%s\n' ' 仅安装 /usr/bin/python3.13；不替换 /usr/bin/python3'
printf '%s\n' '============================================================'

if [[ "$EUID" -ne 0 ]]; then
    if ! command -v sudo >/dev/null 2>&1; then
        printf '错误：需要 root 权限，但系统没有 sudo。请使用 root 执行：bash %s\n' "$SCRIPT_PATH" >&2
        exit 1
    fi
    printf '需要管理员权限；将通过 sudo 重新运行：%s\n' "$SCRIPT_PATH"
    exec sudo -- bash "$SCRIPT_PATH" "$@"
fi

umask 022

printf '安装目标：%s\n' "$TARGET"
printf '独立运行时目录：%s\n' "$INSTALL_ROOT"
printf '操作范围：仅创建独立运行时文件和目标解释器链接；不修改 PATH、默认 Python、包管理器配置或系统软链接。\n'

if [[ "$(uname -s)" != "Linux" ]]; then
    printf '错误：此安装器仅支持使用 glibc 的 Linux 系统。\n' >&2
    exit 1
fi

case "$(uname -m)" in
    x86_64)
        readonly ASSET_ARCH="x86_64"
        ;;
    aarch64|arm64)
        readonly ASSET_ARCH="aarch64"
        ;;
    *)
        printf '错误：不支持 CPU 架构：%s（仅支持 x86_64 和 aarch64）。\n' "$(uname -m)" >&2
        exit 1
        ;;
esac

if ! command -v getconf >/dev/null 2>&1; then
    printf '错误：找不到 getconf，无法确认系统是否使用 glibc；未进行安装。\n' >&2
    exit 1
fi
if ! GLIBC_VERSION="$(getconf GNU_LIBC_VERSION 2>&1)"; then
    printf '错误：无法确认 glibc 版本（%s）；不支持 musl 等非 glibc 系统。\n' "$GLIBC_VERSION" >&2
    exit 1
fi
if [[ "$GLIBC_VERSION" != glibc* ]]; then
    printf '错误：检测到非 glibc 系统：%s；未进行安装。\n' "$GLIBC_VERSION" >&2
    exit 1
fi

printf '系统：Linux %s，%s，%s\n' "$(uname -r)" "$ASSET_ARCH" "$GLIBC_VERSION"

for required_command in curl tar sha256sum python3 readlink mktemp; do
    if ! command -v "$required_command" >/dev/null 2>&1; then
        printf '错误：缺少必需命令：%s。安装器不会自动更改系统或安装依赖。\n' "$required_command" >&2
        exit 1
    fi
done
if ! tar --version | grep -q 'GNU tar'; then
    printf '错误：需要 GNU tar 才能安全解压预编译包；未进行安装。\n' >&2
    exit 1
fi

if [[ -e "$TARGET" || -L "$TARGET" ]]; then
    if ! CURRENT_VERSION="$("$TARGET" --version 2>&1)"; then
        printf '错误：目标路径已存在但无法运行：%s（%s）。为避免覆盖现有文件，安装已中止。\n' \
            "$TARGET" "$CURRENT_VERSION" >&2
        exit 1
    fi
    if [[ "$CURRENT_VERSION" =~ ^Python[[:space:]]+3\.13\.[0-9]+([[:space:]]|$) ]]; then
        printf '目标路径已存在可运行的 Python 3.13（%s）；不覆盖、不改动任何文件。\n' "$CURRENT_VERSION"
        exit 0
    fi
    printf '错误：目标路径已存在，但不是可识别的 Python 3.13：%s（%s）。为避免覆盖，安装已中止。\n' \
        "$TARGET" "$CURRENT_VERSION" >&2
    exit 1
fi

TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/install-python313.XXXXXX")"
ARCHIVE="$TEMP_DIR/python313.tar.gz"
RELEASE_JSON="$TEMP_DIR/release.json"

printf '\n[1/6] 获取预编译发行版信息：%s\n' "$RELEASE_API"
curl --fail --location --show-error --retry 3 --retry-all-errors \
    --connect-timeout 20 --max-time 120 "$RELEASE_API" --output "$RELEASE_JSON"

METADATA="$(python3 - "$RELEASE_JSON" "$ASSET_ARCH" <<'PY'
import json
import re
import sys

release_path, architecture = sys.argv[1:]
with open(release_path, encoding="utf-8") as release_file:
    release = json.load(release_file)

pattern = re.compile(
    rf"^cpython-(3\.13\.\d+)\+[^+]+-{re.escape(architecture)}"
    r"-unknown-linux-gnu-install_only\.tar\.gz$"
)
candidates = []
for asset in release.get("assets", []):
    match = pattern.fullmatch(asset.get("name", ""))
    if match:
        candidates.append((tuple(map(int, match.group(1).split("."))), match.group(1), asset))

if not candidates:
    raise SystemExit(
        f"错误：发行版 {release.get('tag_name', '(未知)')} 中没有适用于 "
        f"{architecture} 的 Python 3.13 稳定版预编译包。"
    )

_, version, asset = max(candidates, key=lambda item: item[0])
digest = asset.get("digest", "")
if not re.fullmatch(r"sha256:[0-9a-fA-F]{64}", digest):
    raise SystemExit(f"错误：GitHub 未提供 {asset['name']} 的 SHA-256 摘要，拒绝安装未校验文件。")

print(version)
print(asset["name"])
print(asset["browser_download_url"])
print(digest[7:].lower())
print(release.get("tag_name", ""))
PY
)"
mapfile -t METADATA_LINES <<< "$METADATA"
if [[ "${#METADATA_LINES[@]}" -ne 5 ]]; then
    printf '错误：预编译发行版元数据不完整；已中止安装。\n' >&2
    exit 1
fi
VERSION="${METADATA_LINES[0]}"
ASSET_NAME="${METADATA_LINES[1]}"
DOWNLOAD_URL="${METADATA_LINES[2]}"
EXPECTED_SHA256="${METADATA_LINES[3]}"
RELEASE_TAG="${METADATA_LINES[4]}"

if [[ ! "$ASSET_NAME" =~ ^cpython-3\.13\.[0-9]+\+[A-Za-z0-9._-]+-${ASSET_ARCH}-unknown-linux-gnu-install_only\.tar\.gz$ ]]; then
    printf '错误：发行版文件名不符合预期，拒绝解压：%s\n' "$ASSET_NAME" >&2
    exit 1
fi
if [[ "$DOWNLOAD_URL" != https://github.com/astral-sh/python-build-standalone/releases/download/* ]]; then
    printf '错误：预编译包地址不是可信发行版地址，拒绝下载：%s\n' "$DOWNLOAD_URL" >&2
    exit 1
fi
DESTINATION="$INSTALL_ROOT/${ASSET_NAME%.tar.gz}"

printf '发行版：Python %s（standalone 发布 %s）\n' "$VERSION" "$RELEASE_TAG"
printf '预编译包：%s\n' "$ASSET_NAME"
printf '下载地址：%s\n' "$DOWNLOAD_URL"
printf 'SHA-256（可信发行版元数据）：%s\n' "$EXPECTED_SHA256"

printf '\n[2/6] 下载预编译包（不在本机编译）：%s\n' "$ASSET_NAME"
curl --fail --location --show-error --retry 3 --retry-all-errors \
    --connect-timeout 20 --max-time 900 "$DOWNLOAD_URL" --output "$ARCHIVE"

printf '\n[3/6] 校验下载文件 SHA-256\n'
ACTUAL_SHA256="$(sha256sum "$ARCHIVE")"
ACTUAL_SHA256="${ACTUAL_SHA256%% *}"
printf '期望值：%s\n实际值：%s\n' "$EXPECTED_SHA256" "$ACTUAL_SHA256"
if [[ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]]; then
    printf '错误：SHA-256 校验失败，拒绝安装。\n' >&2
    exit 1
fi

printf '\n[4/6] 解压到临时目录并验证解释器\n'
install -d -m 0755 -- "$INSTALL_ROOT"
STAGING_DIR="$(mktemp -d "$INSTALL_ROOT/.staging.XXXXXX")"
STAGED_PYTHON="$STAGING_DIR/python/bin/python3.13"
tar --extract --gzip --file "$ARCHIVE" --directory "$STAGING_DIR" --no-same-owner --no-same-permissions
if [[ ! -x "$STAGED_PYTHON" ]]; then
    printf '错误：预编译包中缺少可执行文件：%s\n' "$STAGED_PYTHON" >&2
    exit 1
fi
if ! STAGED_VERSION="$("$STAGED_PYTHON" --version 2>&1)"; then
    printf '错误：预编译解释器无法运行（可能是系统运行库不兼容）：%s\n' "$STAGED_VERSION" >&2
    exit 1
fi
if [[ "$STAGED_VERSION" != "Python $VERSION" ]]; then
    printf '错误：预编译解释器版本不匹配：预期 Python %s，实际 %s\n' "$VERSION" "$STAGED_VERSION" >&2
    exit 1
fi
if ! "$STAGED_PYTHON" -c 'import bz2, ctypes, lzma, sqlite3, ssl, venv'; then
    printf '错误：预编译解释器核心模块验证失败，未安装。\n' >&2
    exit 1
fi
printf '解释器与 ssl、bz2、lzma、sqlite3、ctypes、venv 模块验证通过：%s\n' "$STAGED_VERSION"

printf '\n[5/6] 安装独立运行时到 %s\n' "$DESTINATION"
install -d -m 0755 -- "$INSTALL_ROOT"
if [[ -e "$DESTINATION" || -L "$DESTINATION" ]]; then
    printf '错误：安装目录已存在：%s。为避免覆盖未知文件，安装已中止。\n' "$DESTINATION" >&2
    exit 1
fi
mv -- "$STAGING_DIR/python" "$DESTINATION"

printf '\n[6/6] 创建指定入口 %s\n' "$TARGET"
if [[ -e "$TARGET" || -L "$TARGET" ]]; then
    printf '错误：安装过程中目标路径被其他进程创建：%s。为避免覆盖，未修改该路径。\n' "$TARGET" >&2
    exit 1
fi
ln -s -- "$DESTINATION/python/bin/python3.13" "$TARGET"

printf '\n安装完成：\n'
printf '  命令：%s\n' "$TARGET"
printf '  版本：'
"$TARGET" --version
printf '  运行时：%s\n' "$DESTINATION"
printf '  默认 Python：未修改；/usr/bin/python3 未被替换或重定向。\n'
