import importlib, os, pathlib, sys, tempfile, warnings # 导入系统及路径依赖
from _frozen_importlib_external import FileFinder, PathFinder, _get_supported_file_loaders # 导入路径查找器
CHAQUO_INDEX = "https://chaquo.com/pypi-13.1/" # 核心修正：主源必须是Chaquopy官方源以获取Android预编译C扩展
EXTRA_INDEX = "https://pypi.tuna.tsinghua.edu.cn/simple" # 备用源：清华源用于加速纯Python包下载
def _patch_chaquopy_environment(): # 补丁：修复Chaquopy特有类型引发的pip扫描崩溃
    for mod in list(sys.modules.values()): # 遍历当前已加载模块
        if mod and hasattr(mod, "AssetPath"): # 定位AssetPath类
            cls = getattr(mod, "AssetPath") # 取出类对象
            if isinstance(cls, type): # 补全标准库Path所必须的属性
                if not hasattr(cls, "parent"): cls.parent = property(lambda self: pathlib.Path(str(self)).parent)
                if not hasattr(cls, "name"): cls.name = property(lambda self: pathlib.Path(str(self)).name)
    try: # 绕过pip的User-Agent全量扫描引发的连锁崩溃
        import pip._internal.network.session as pip_session 
        pip_session.user_agent = lambda: "pip/runtime_pip (Android/Chaquopy)"
    except Exception: pass
    try: # 拦截pip底层元数据查找机制
        import pip._internal.metadata.importlib._envs as pip_envs
        if hasattr(pip_envs, "Environment") and hasattr(pip_envs.Environment, "find"):
            old_find = pip_envs.Environment.find # 备份原生函数
            def safe_find(self, location): # 构建安全网
                if location is not None and not hasattr(location, "parent"): 
                    try: type(location).parent = property(lambda s: pathlib.Path(str(s)).parent)
                    except Exception: pass
                return old_find(self, location)
            pip_envs.Environment.find = safe_find # 挂载拦截器
    except Exception: pass
def _fix_import_environment(): # 刷新Python运行时导入缓存
    if PathFinder in sys.meta_path: sys.meta_path.remove(PathFinder) # 移除旧PathFinder
    sys.meta_path.insert(0, PathFinder) # 置顶标准PathFinder
    if not any("FileFinder" in str(hook) for hook in sys.path_hooks): sys.path_hooks.insert(0, FileFinder.path_hook(*_get_supported_file_loaders()))
    sys.path_importer_cache.clear() # 清空路径缓存
    importlib.invalidate_caches() # 强制刷新底层导入锁
_fix_import_environment() # 初始化时立刻执行一次
RUN_LIB_DIR = None # 全局依赖目录
def configure(files_dir=None): # 初始化依赖安装路径
    global RUN_LIB_DIR
    RUN_LIB_DIR = os.path.join(files_dir or os.environ.get("XIME_FILES_DIR") or tempfile.gettempdir(), "runtime_site_packages")
    os.makedirs(RUN_LIB_DIR, exist_ok=True) # 递归创建目录
    if RUN_LIB_DIR not in sys.path: sys.path.insert(0, RUN_LIB_DIR) # 置顶sys.path优先加载
    _fix_import_environment() # 刷新环境
    return RUN_LIB_DIR
configure() # 模块加载时生效
def install(package_name, extra_args=None): # 单包核心安装逻辑
    if not isinstance(package_name, str) or not package_name.strip(): return False, "package_name 不能为空"
    _patch_chaquopy_environment() # 注入兼容性补丁
    try: from pip._internal.cli.main import main as pip_main # 提取pip核心
    except ImportError: return False, "pip 模块未打包进APK"
    # 核心修补：--no-compile防污染, --upgrade防重名, 并通过 -i 和 --extra-index-url 组合双源
    args = ["install", "--target", RUN_LIB_DIR, "--upgrade", "--no-compile", "--no-build-isolation", "--disable-pip-version-check", "--no-cache-dir", "--only-binary=:all:", package_name]
    if isinstance(extra_args, list): args.extend(extra_args) # 附加参数
    print(f"[RuntimePIP] 开始安装: {package_name} ...")
    try: # 启动pip安装
        with warnings.catch_warnings(): # 捕获系统警告
            warnings.simplefilter("ignore") # 屏蔽Unexpected import等刷屏警告
            ret_code = pip_main(args) # 同步执行
    except Exception as error: return False, f"pip 崩溃: {error}"
    _fix_import_environment() # 安装后立刻刷新导入器
    mod_name = package_name.split("=")[0].split("<")[0].split(">")[0].strip().replace("-", "_") # 提取纯净包名
    for key in list(sys.modules.keys()): # 暴力清洗：清理pip运行期间因意外import留在内存中的残缺模块
        if key == mod_name or key.startswith(mod_name + "."): del sys.modules[key]
    if ret_code != 0: return False, f"安装失败，退出码: {ret_code}"
    try: # 最终验收
        importlib.import_module(mod_name) # 强制从新路径读取最新文件加载
        return True, f"成功安装并加载 {package_name} 到 {RUN_LIB_DIR}"
    except Exception as e: return False, f"安装完成，但 import 失败: {e}"
def install_missing(packages): # 批量检查并自动双源安装
    packages = [packages] if isinstance(packages, str) else packages
    missing = []
    for pkg in packages: # 检测缺失
        mod_name = pkg.split("=")[0].split("<")[0].split(">")[0].strip().replace("-", "_")
        try: importlib.import_module(mod_name)
        except ImportError: missing.append(pkg)
    if not missing: return True, "所有依赖均已存在，无需安装"
    print(f"[+] 正在安装缺失依赖: {', '.join(missing)}")
    results, all_success = {}, True
    try: # 批量执行
        for package in missing:
            # 核心修正：结合主源(Chaquopy,保障C扩展)与备用源(Tsinghua,加速纯Python)
            ok, message = install(package, extra_args=["-i", CHAQUO_INDEX, "--extra-index-url", EXTRA_INDEX, "--trusted-host", "chaquo.com", "--trusted-host", "pypi.tuna.tsinghua.edu.cn"])
            results[package] = (ok, message)
            if not ok: all_success = False; print(f"[!] {package} 安装失败: {message}")
            else: print(f"[+] {package} 安装及导入成功")
    except Exception as e: return False, f"批量安装异常: {e}"
    return all_success, results