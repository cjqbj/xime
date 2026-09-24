import importlib
import os
import pathlib
import sys
import tempfile
from _frozen_importlib_external import FileFinder, PathFinder, _get_supported_file_loaders


def _patch_chaquopy_environment():
    """修复 Chaquopy 环境下 AssetPath 缺失 parent 属性及 pip User-Agent 扫描崩溃的问题"""
    # 1. 动态给已加载模块中的 AssetPath 类补全 parent / name 属性
    for mod in list(sys.modules.values()):
        if mod and hasattr(mod, "AssetPath"):
            cls = getattr(mod, "AssetPath")
            if isinstance(cls, type):
                if not hasattr(cls, "parent"):
                    cls.parent = property(lambda self: pathlib.Path(str(self)).parent)
                if not hasattr(cls, "name"):
                    cls.name = property(lambda self: pathlib.Path(str(self)).name)

    # 2. 绕过 pip 在构建 User-Agent 时扫描 importlib.metadata 的过程
    try:
        import pip._internal.network.session as pip_session
        pip_session.user_agent = lambda: "pip/runtime_pip (Android/Chaquopy)"
    except Exception:
        pass

    # 3. 拦截 pip 的 metadata.importlib 环境查找，规避未预期的 AssetPath 实例
    try:
        import pip._internal.metadata.importlib._envs as pip_envs
        if hasattr(pip_envs, "Environment"):
            env_cls = pip_envs.Environment
            if hasattr(env_cls, "find"):
                old_find = env_cls.find
                def safe_find(self, location):
                    if location is not None and not hasattr(location, "parent"):
                        try:
                            type(location).parent = property(lambda s: pathlib.Path(str(s)).parent)
                        except Exception:
                            pass
                    return old_find(self, location)
                env_cls.find = safe_find
    except Exception:
        pass


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

    # 执行 pip 安装前先注入 Chaquopy 兼容性补丁
    _patch_chaquopy_environment()

    try:
        from pip._internal.cli.main import main as pip_main
    except ImportError:
        return False, "pip 模块未打包进 APK，请检查 build.gradle.kts 配置"

    args = [
        "install",
        "--target",
        RUN_LIB_DIR,
        "--no-build-isolation",
        "--disable-pip-version-check",  # 禁用 pip 更新检查（避免触发 metadata 扫描）
        "--no-cache-dir",              # 禁用缓存，避免 Android 权限或缓存污染
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