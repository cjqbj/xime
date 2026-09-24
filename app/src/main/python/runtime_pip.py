import importlib
import os
import sys
import tempfile
from _frozen_importlib_external import FileFinder, PathFinder, _get_supported_file_loaders


def _fix_import_environment():
    if PathFinder in sys.meta_path:
        sys.meta_path.remove(PathFinder)
    sys.meta_path.insert(0, PathFinder)

    if not any("FileFinder" in str(hook) for hook in sys.path_hooks):
        sys.path_hooks.insert(0, FileFinder.path_hook(*_get_supported_file_loaders()))

    sys.path_importer_cache.clear()
    importlib.invalidate_caches()


_fix_import_environment()

RUN_LIB_DIR = None


def configure(files_dir=None):
    global RUN_LIB_DIR
    base_dir = files_dir or os.environ.get("XIME_FILES_DIR")
    if not base_dir:
        base_dir = tempfile.gettempdir()
    RUN_LIB_DIR = os.path.join(base_dir, "runtime_site_packages")
    os.makedirs(RUN_LIB_DIR, exist_ok=True)
    if RUN_LIB_DIR not in sys.path:
        sys.path.insert(0, RUN_LIB_DIR)
    _fix_import_environment()
    return RUN_LIB_DIR


configure()


def install(package_name, extra_args=None):
    if not isinstance(package_name, str) or not package_name.strip():
        return False, "package_name 必须是非空字符串"

    try:
        from pip._internal.cli.main import main as pip_main
    except ImportError:
        return False, "pip 模块未打包进 APK，请检查 build.gradle.kts 配置"

    args = [
        "install",
        "--target",
        RUN_LIB_DIR,
        "--no-build-isolation",
        "--only-binary=:all:",
        package_name,
    ]
    if isinstance(extra_args, list):
        args.extend(extra_args)

    print(f"[RuntimePIP] 开始安装: {package_name} ...")
    try:
        ret_code = pip_main(args)
    except Exception as error:
        return False, f"pip 安装异常: {error}"

    sys.path_importer_cache.clear()
    importlib.invalidate_caches()
    if ret_code == 0:
        return True, f"成功安装 {package_name} 到 {RUN_LIB_DIR}"
    return False, f"pip 安装失败，退出码: {ret_code}"