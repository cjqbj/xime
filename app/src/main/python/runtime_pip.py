import os, sys, io, re, struct, platform, tempfile, threading, importlib, logging, logging.config, warnings, locale, pathlib, functools, traceback
from concurrent.futures import ThreadPoolExecutor
__all__ = ["configure", "install", "install_missing", "install_async", "CHAQUO_INDEX", "EXTRA_INDEX"]
CHAQUO_INDEX = "https://chaquo.com/pypi-13.1/"  # Chaquopy官方安卓预编译轮子源(Chaquopy 17 文档仍使用此地址)
EXTRA_INDEX = "https://pypi.tuna.tsinghua.edu.cn/simple"  # 清华镜像,加速纯Python包
AUTO_ANDROID_PLATFORM = True  # 自动追加 --platform android_<api>_<abi>,让进程内pip能识别Chaquopy轮子
LOG_TAIL = 8000  # 结果中保留的pip日志尾部字符数
TARGET_DIR = None  # configure()后的安装目录
_LOCK = threading.RLock()  # pip非线程安全,所有安装全局串行
_ELOCK = threading.Lock()  # 仅保护执行器创建,避免RPC线程等待长时间安装
_EXEC = None  # 单工作线程执行器(懒创建)
_ROUTES = {}  # 线程ID -> 输出缓冲,实现按线程分流stdout/stderr
_ABI = {"aarch64": "arm64_v8a", "arm64": "arm64_v8a", "armv8l": "armeabi_v7a", "armv7l": "armeabi_v7a", "x86_64": "x86_64", "amd64": "x86_64", "i686": "x86", "x86": "x86"}  # uname -> Android ABI
_ALIASES = {"pyyaml": "yaml", "beautifulsoup4": "bs4", "pillow": "PIL", "opencv-python": "cv2", "opencv-python-headless": "cv2", "scikit-learn": "sklearn", "python-dateutil": "dateutil", "paho-mqtt": "paho.mqtt", "pycryptodome": "Crypto", "protobuf": "google.protobuf", "pyserial": "serial", "msgpack-python": "msgpack"}  # pip名与模块名不一致的常见包
class _Router(io.TextIOBase):  # 坑4:stdout/stderr按线程分流代理,只收集pip线程输出,不污染RPC线程
    def __init__(self, orig): self._orig = orig
    def _dst(self):
        b = _ROUTES.get(threading.get_ident())
        return b if b is not None else self._orig
    def write(self, s):
        d = self._dst()
        return d.write(s) if d is not None else len(s)
    def flush(self):
        try: self._dst().flush()
        except Exception: pass
    def writable(self): return True
    def isatty(self): return False  # 让pip/rich关闭颜色与动画
    def fileno(self): raise io.UnsupportedOperation("fileno")
    @property
    def encoding(self): return getattr(self._orig, "encoding", None) or "utf-8"
    @property
    def errors(self): return getattr(self._orig, "errors", None) or "replace"
    def __getattr__(self, n): return getattr(self._orig, n)
def _fs(o):  # 从AssetPath等对象中尽力取出字符串路径
    for a in ("path", "_path", "root"):
        v = getattr(o, a, None)
        if isinstance(v, str): return v
    try: return os.fspath(o)
    except TypeError: return str(o)
def _patch_asset_path():  # 坑1:遍历sys.modules,给Chaquopy的AssetPath动态补parent/name
    for m in list(sys.modules.values()):
        d = getattr(m, "__dict__", None)
        cls = d.get("AssetPath") if isinstance(d, dict) else None
        if not isinstance(cls, type): continue
        try:
            if not hasattr(cls, "parent"): cls.parent = property(lambda s: pathlib.Path(os.path.dirname(_fs(s).rstrip("/")) or "/"))
            if not hasattr(cls, "name"): cls.name = property(lambda s: os.path.basename(_fs(s).rstrip("/")))
        except (TypeError, AttributeError): pass  # 内置/不可写类型则交给_patch_pip_env兜底
def _norm_loc(loc):  # 非pathlib路径对象统一转成pathlib.Path
    if loc is None or isinstance(loc, pathlib.PurePath): return loc
    try: return pathlib.Path(_fs(loc))
    except Exception: return loc
def _wrap_find_impl(fn):  # 在源头把info_location规范化,逐个分发包修复
    @functools.wraps(fn)
    def w(*a, **k):
        for dist, loc in fn(*a, **k): yield dist, _norm_loc(loc)
    w._rtpip = True
    return w
def _safe_iter(fn):  # 兜底:扫描异常时结束该位置扫描而非让pip崩溃
    @functools.wraps(fn)
    def w(*a, **k):
        try: it = iter(fn(*a, **k))
        except (AttributeError, TypeError, ValueError): return
        while True:
            try: x = next(it)
            except (StopIteration, AttributeError, TypeError, ValueError): return
            yield x
    w._rtpip = True
    return w
def _patch_pip_env():  # 坑1兜底:拦截pip的Environment查找(_DistributionFinder)
    try: from pip._internal.metadata.importlib import _envs
    except Exception: return
    f = getattr(_envs, "_DistributionFinder", None)
    if f is None: return
    fi = getattr(f, "_find_impl", None)
    if fi is not None and not getattr(fi, "_rtpip", False): f._find_impl = _wrap_find_impl(fi)
    for n in ("find", "find_linked", "find_eggs", "find_legacy_editables"):
        fn = getattr(f, n, None)
        if fn is not None and not getattr(fn, "_rtpip", False): setattr(f, n, _safe_iter(fn))
def _default_dir():  # 优先Chaquopy的Context.getFilesDir(),其次HOME
    try:
        from java import jclass
        base = str(jclass("com.chaquo.python.Python").getPlatform().getApplication().getFilesDir().toString())
    except Exception: base = os.environ.get("HOME") or os.path.expanduser("~") or os.getcwd()
    return os.path.join(base, "runtime_pip_site")
def _ensure_path(d):  # 目标目录置顶+清除导入器负缓存
    if sys.path[:1] != [d]:
        while d in sys.path: sys.path.remove(d)
        sys.path.insert(0, d)
    sys.path_importer_cache.pop(d, None)
    importlib.invalidate_caches()
def configure(files_dir=None):
    global TARGET_DIR
    with _LOCK:
        d = os.path.abspath(files_dir or TARGET_DIR or _default_dir())
        os.makedirs(d, exist_ok=True)  # 必须先建目录,否则FileFinder会把None永久缓存
        tmp = os.path.join(os.path.dirname(d), "runtime_pip_tmp")
        os.makedirs(tmp, exist_ok=True)
        try: ok = os.access(tempfile.gettempdir(), os.W_OK)
        except Exception: ok = False
        if not ok: os.environ["TMPDIR"] = tmp; tempfile.tempdir = tmp  # Android没有可写的/tmp
        TARGET_DIR = d
        _ensure_path(d)
        return d
def _mod_name(pkg):  # 'PyYAML>=6' -> 'yaml', 'Flask[async]' -> 'flask'
    n = re.split(r"[\s\[<>=!~;@]", pkg.strip(), 1)[0]
    k = re.sub(r"[-_.]+", "-", n).lower()
    return _ALIASES.get(k, k.replace("-", "_"))
def _android_api():  # 优先设备SDK_INT,其次编译期API
    try:
        from java import jclass
        return int(jclass("android.os.Build$VERSION").SDK_INT)
    except Exception: pass
    try: return int(sys.getandroidapilevel())
    except Exception: return None
def _platform_args(extra):  # Chaquopy轮子标签形如 android_21_arm64_v8a
    if not AUTO_ANDROID_PLATFORM or any(str(a).startswith("--platform") for a in extra): return []
    api, abi = _android_api(), _ABI.get(platform.machine().lower())
    if not api or not abi: return []
    if struct.calcsize("P") == 4: abi = {"arm64_v8a": "armeabi_v7a", "x86_64": "x86"}.get(abi, abi)  # 64位设备上的32位进程
    out = []
    for lv in range(16, api + 1): out += ["--platform", "android_%d_%s" % (lv, abi)]
    return out
def _run_pip(args):  # 在当前进程运行pip,快照并强制还原所有全局状态
    buf = io.StringIO()
    with _LOCK:
        _patch_asset_path()
        try: from pip._internal.cli.main import main as pip_main
        except Exception as e: return 1, "import pip failed: %r (Chaquopy需在build.gradle的pip块中 install \"pip\")" % e
        _patch_pip_env()
        meta, hooks, spath, pic = sys.meta_path[:], sys.path_hooks[:], sys.path[:], dict(sys.path_importer_cache)  # 坑2:备份导入系统
        wfilters, wshow = warnings.filters[:], warnings.showwarning
        root = logging.getLogger()
        rh, rl = root.handlers[:], root.level
        clr = getattr(logging.config, "_clearExistingHandlers", None)
        try: loc = locale.setlocale(locale.LC_ALL)
        except Exception: loc = None
        so, se = sys.stdout, sys.stderr
        ro, re_ = _Router(so), _Router(se)
        tid = threading.get_ident()
        _ROUTES[tid] = buf
        sys.stdout, sys.stderr = ro, re_
        if clr is not None: logging.config._clearExistingHandlers = lambda: None  # 防止pip的dictConfig关闭RPC已有日志handler
        code = 1
        try: code = pip_main(list(args))
        except SystemExit as e: code = e.code if isinstance(e.code, int) else (0 if e.code is None else 1)
        except BaseException: buf.write(traceback.format_exc()); code = 1
        finally:
            sys.meta_path[:] = meta  # 坑2:拔除pip注入的meta_path钩子
            sys.path_hooks[:] = hooks  # 坑2:拔除pip注入的path_hooks
            sys.path[:] = spath
            for k in list(sys.path_importer_cache):
                v = sys.path_importer_cache.get(k)
                if k not in pic or type(v).__module__.startswith("pip"): sys.path_importer_cache.pop(k, None)
            if clr is not None: logging.config._clearExistingHandlers = clr
            for h in root.handlers[:]:
                if h not in rh:
                    try: h.close()
                    except Exception: pass
            root.handlers[:] = rh
            root.setLevel(rl)
            warnings.filters[:] = wfilters
            warnings.showwarning = wshow
            fm = getattr(warnings, "_filters_mutated", None) or getattr(warnings, "_filters_mutated_lock_held", None)
            try: fm and fm()
            except Exception: pass
            if loc:
                try: locale.setlocale(locale.LC_ALL, loc)
                except Exception: pass
            if sys.stdout is ro: sys.stdout = so
            if sys.stderr is re_: sys.stderr = se
            _ROUTES.pop(tid, None)
            importlib.invalidate_caches()
        try: code = int(code or 0)
        except (TypeError, ValueError): code = 1
        return code, buf.getvalue()
def install(package_name, extra_args=None, module_name=None):
    t = TARGET_DIR or configure()
    extra = [str(a) for a in (extra_args or [])]
    mod = module_name or _mod_name(package_name)
    args = ["install", package_name, "--target", t, "--upgrade", "--no-compile", "--no-build-isolation", "--disable-pip-version-check", "--no-cache-dir", "--only-binary=:all:", "--no-input", "--no-color", "--progress-bar", "off"] + _platform_args(extra) + extra
    res = {"ok": False, "package": package_name, "module": mod, "action": None, "version": None, "code": None, "error": None, "log": ""}
    try: code, log = _run_pip(args)
    except Exception as e: code, log = 1, traceback.format_exc()
    res["code"], res["log"] = code, log[-LOG_TAIL:]
    if code != 0:
        res["error"] = "pip exit code %s" % code
        return res
    try:
        with _LOCK:
            _ensure_path(t)
            m = sys.modules.get(mod)
            if m is not None: m, res["action"] = importlib.reload(m), "reloaded"  # 坑3:禁止del sys.modules,原地热更新
            else: m, res["action"] = importlib.import_module(mod), "imported"
        res["version"], res["ok"] = getattr(m, "__version__", None), True
    except (Exception, SystemExit) as e: res["error"] = "%s: %s" % (type(e).__name__, e)
    return res
def _executor():  # 单工作线程,保证安装串行且不占用RPC线程
    global _EXEC
    with _ELOCK:
        if _EXEC is None: _EXEC = ThreadPoolExecutor(max_workers=1, thread_name_prefix="runtime_pip")
        return _EXEC
def install_async(package_name, extra_args=None, module_name=None, callback=None):  # 返回Future,RPC线程立即返回
    f = _executor().submit(install, package_name, extra_args, module_name)
    if callback: f.add_done_callback(lambda fu: callback(fu.result()))
    return f
def install_missing(packages, extra_args=None, wait=True):  # packages: ['requests', ('beautifulsoup4','bs4')] 或 {'pip名':'模块名'}
    if isinstance(packages, str): packages = [packages]
    items = list(packages.items()) if isinstance(packages, dict) else [(p, None) if isinstance(p, str) else (p[0], p[1] if len(p) > 1 else None) for p in packages]
    extra = ["-i", CHAQUO_INDEX, "--extra-index-url", EXTRA_INDEX] + [str(a) for a in (extra_args or [])]
    def job():
        out = {}
        for pkg, mod in items:
            mod = mod or _mod_name(pkg)
            try:
                m = importlib.import_module(mod)
                out[pkg] = {"ok": True, "package": pkg, "module": mod, "action": "present", "version": getattr(m, "__version__", None), "code": 0, "error": None, "log": ""}
                continue
            except (Exception, SystemExit): pass
            out[pkg] = install(pkg, extra, mod)
        return out
    return job() if wait else _executor().submit(job)
