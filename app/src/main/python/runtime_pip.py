import os, sys, io, re, ast, ssl, glob, shutil, struct, hashlib, tarfile, zipfile, platform, sysconfig, tempfile, threading, importlib, importlib.util, importlib.metadata, logging, logging.config, warnings, locale, pathlib, functools, traceback, configparser, urllib.request, urllib.parse, urllib.error
from html import unescape
from concurrent.futures import ThreadPoolExecutor
__all__ = ["configure", "install", "install_missing", "install_async", "install_wheel", "migrate_root_packages", "CHAQUO_INDEX", "EXTRA_INDEX", "FIND_LINKS"]
CHAQUO_INDEX = "https://chaquo.com/pypi-13.1/"  # Chaquopy官方Android预编译wheel源(静态仓库,由维护者预先构建,不会按需编译)
EXTRA_INDEX = "https://pypi.tuna.tsinghua.edu.cn/simple"  # 清华PyPI镜像:纯Python wheel与sdist
FIND_LINKS = []  # 自建wheel仓库:平铺目录页URL或本地目录,如["https://your.host/wheels/"],用于放自己交叉编译/打包的wheel
DEFAULT_INSTALL_DIR = "site-packages"  # 安装目录 = filesDir/site-packages,绝不写入files根目录
USE_PIP = True  # True:先用pip(--no-deps --only-binary)快速安装,失败再走内置直装;False:只用内置直装(更快更可控)
AUTO_ANDROID_PLATFORM = True  # pip模式下自动追加--platform android_<api>_<abi>(pip自身不认识Chaquopy标签时)
SDIST_FALLBACK = True  # 没有兼容wheel时,下载sdist并只安装其纯Python部分(不执行setup.py,不需要子进程)
HTTP_TIMEOUT = 60  # 内置直装的网络超时(秒)
LOG_TAIL = 8000  # 返回结果中保留的日志尾部字符数
TARGET_DIR = None  # 实际安装目录(configure后赋值)
FILES_DIR = None  # Android filesDir根目录(仅用于校验和泄漏检测)
_LOCK = threading.RLock()  # pip及文件操作非线程安全,全局串行;可重入以支持依赖递归
_ELOCK = threading.Lock()  # 仅保护后台执行器的创建
_EXEC = None  # 单工作线程执行器(懒创建),避免阻塞RPC线程
_ROUTES = {}  # 线程ID -> 输出缓冲,实现stdout/stderr按线程分流
_NATIVE = None  # 缓存:pip自身是否已原生支持android_*标签
_ENV = None  # 缓存:当前解释器的wheel兼容参数
_SSLCTX = None  # 缓存:系统证书失败时使用的certifi SSL上下文
_LOADED = set()  # 已预加载的chaquopy/lib/*.so,避免重复dlopen
_PROTECTED = {"logs", "chaquopy", "bin", "rime", "cache", "lib", "site-packages"}  # 迁移时绝不移动的files根目录项
_ABI = {"aarch64": "arm64_v8a", "arm64": "arm64_v8a", "armv8l": "armeabi_v7a", "armv7l": "armeabi_v7a", "x86_64": "x86_64", "amd64": "x86_64", "i686": "x86", "x86": "x86"}  # uname机器名 -> Android ABI名
_ALIASES = {"pyyaml": "yaml", "beautifulsoup4": "bs4", "pillow": "PIL", "opencv-python": "cv2", "opencv-python-headless": "cv2", "scikit-learn": "sklearn", "python-dateutil": "dateutil", "paho-mqtt": "paho.mqtt", "pycryptodome": "Crypto", "protobuf": "google.protobuf", "pyserial": "serial", "msgpack-python": "msgpack"}  # 无法从dist-info推断时的pip名->模块名兜底
_SDIST_EXT = (".tar.gz", ".tgz", ".tar.bz2", ".zip")  # 支持的sdist压缩格式
_NATIVE_SRC = (".c", ".cc", ".cpp", ".cxx", ".pyx", ".f", ".f90", ".rs")  # 需要编译器的源码后缀
_EXCL_DIR = {"test", "tests", "testing", "doc", "docs", "example", "examples", "benchmark", "benchmarks", "build", "dist", "scripts", "tools", "bin", "venv", "__pycache__"}  # sdist启发式扫描忽略的目录
_EXCL_PY = {"setup", "conftest", "noxfile", "fabfile", "tasks", "runtests", "ez_setup", "distribute_setup", "bootstrap", "versioneer", "manage"}  # sdist中不是库的顶层脚本
class _Router(io.TextIOBase):  # 坑4:stdout/stderr按线程分流代理,只截获执行pip的线程的输出,RPC线程输出不受影响
    def __init__(self, orig): self._orig = orig  # 保存原始流
    def _dst(self):
        b = _ROUTES.get(threading.get_ident())  # 当前线程是否登记了缓冲
        return b if b is not None else self._orig
    def write(self, s):
        d = self._dst()
        return d.write(s) if d is not None else len(s)  # 原始流为None(无控制台)时静默丢弃
    def flush(self):
        try: self._dst().flush()
        except Exception: pass
    def writable(self): return True
    def isatty(self): return False  # 让pip/rich关闭颜色与进度动画
    def fileno(self): raise io.UnsupportedOperation("fileno")  # 防止底层直接写fd绕过分流
    @property
    def encoding(self): return getattr(self._orig, "encoding", None) or "utf-8"
    @property
    def errors(self): return getattr(self._orig, "errors", None) or "replace"
    def __getattr__(self, n): return getattr(self._orig, n)  # 其余属性透传给原始流
def _rd(p):  # 读取文本文件(容错编码)
    with open(p, encoding="utf-8", errors="replace") as f: return f.read()
def _wr(p, s):  # 写入文本文件
    with open(p, "w", encoding="utf-8") as f: f.write(s)
def _ls(p):  # 安全listdir,目录不存在返回空
    try: return os.listdir(p)
    except Exception: return []
def _canon(n): return re.sub(r"[-_.]+", "-", str(n)).lower()  # PEP 503名称规范化:pyDes->pydes, chaquopy_curl->chaquopy-curl
def _req_name(s): return re.split(r"[\s\[<>=!~;@(]", str(s).strip(), 1)[0]  # 'Flask[async]>=2' -> 'Flask'
def _req(s):  # 解析依赖字符串为Requirement对象,失败返回None
    from pip._vendor.packaging.requirements import Requirement
    try: return Requirement(str(s).strip())
    except Exception: return None
def _fs(o):  # 从AssetPath等非标准路径对象中尽力取出字符串路径
    for a in ("path", "_path", "root"):
        v = getattr(o, a, None)
        if isinstance(v, str): return v
    try: return os.fspath(o)
    except TypeError: return str(o)
def _patch_asset_path():  # 坑1:遍历sys.modules,给Chaquopy的AssetPath类动态补parent/name属性
    for m in list(sys.modules.values()):
        d = getattr(m, "__dict__", None)
        cls = d.get("AssetPath") if isinstance(d, dict) else None
        if not isinstance(cls, type): continue
        try:
            if not hasattr(cls, "parent"): cls.parent = property(lambda s: pathlib.Path(os.path.dirname(_fs(s).rstrip("/")) or "/"))  # 父目录
            if not hasattr(cls, "name"): cls.name = property(lambda s: os.path.basename(_fs(s).rstrip("/")))  # 末级名称
        except (TypeError, AttributeError): pass  # 类不可写时由_patch_pip_env兜底
def _norm_loc(loc):  # 把非pathlib路径对象统一转换为pathlib.Path
    if loc is None or isinstance(loc, pathlib.PurePath): return loc
    try: return pathlib.Path(_fs(loc))
    except Exception: return loc
def _wrap_find_impl(fn):  # 包装pip的_find_impl,在源头规范化info_location
    @functools.wraps(fn)
    def w(*a, **k):
        for dist, loc in fn(*a, **k): yield dist, _norm_loc(loc)
    w._rtpip = True  # 标记已打补丁,防止重复包装
    return w
def _safe_iter(fn):  # 包装pip的find*方法:扫描某路径出错时跳过该路径而不是让pip崩溃
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
def _patch_pip_env():  # 坑1兜底:拦截pip底层Environment扫描用的_DistributionFinder
    try: from pip._internal.metadata.importlib import _envs
    except Exception: return
    f = getattr(_envs, "_DistributionFinder", None)
    if f is None: return
    fi = getattr(f, "_find_impl", None)
    if fi is not None and not getattr(fi, "_rtpip", False): f._find_impl = _wrap_find_impl(fi)
    for n in ("find", "find_linked", "find_eggs", "find_legacy_editables"):
        fn = getattr(f, n, None)
        if fn is not None and not getattr(fn, "_rtpip", False): setattr(f, n, _safe_iter(fn))
def _app():  # 获取Android Application对象
    from java import jclass
    return jclass("com.chaquo.python.Python").getPlatform().getApplication()
def _files_dir():  # 按要求优先使用getFilesDir(),非Android环境回退HOME
    try: return str(_app().getFilesDir().toString())
    except Exception: return os.environ.get("HOME") or os.path.expanduser("~") or os.getcwd()
def _cache_dir(base):  # 临时文件放cacheDir,不污染filesDir
    try: return str(_app().getCacheDir().toString())
    except Exception: return os.path.join(os.path.dirname(base), "cache")
def _ensure_path(d):  # 安装目录置于sys.path[0]并清理导入器缓存
    if sys.path[:1] != [d]:
        while d in sys.path: sys.path.remove(d)
        sys.path.insert(0, d)
    sys.path_importer_cache.pop(d, None)  # 清除可能缓存的"目录不存在"
    importlib.invalidate_caches()
def configure(files_dir=None, target_dir=None):  # files_dir=filesDir根目录(不是安装目录);target_dir可显式指定安装目录
    global TARGET_DIR, FILES_DIR
    with _LOCK:
        base = os.path.normpath(os.path.abspath(str(files_dir or _files_dir())))
        if os.path.basename(base) == DEFAULT_INSTALL_DIR and not target_dir: base, target_dir = os.path.dirname(base), base  # 兼容误传.../site-packages
        t = os.path.normpath(os.path.abspath(str(target_dir or os.path.join(base, DEFAULT_INSTALL_DIR))))
        if t in (base, os.path.normpath(_files_dir())): raise ValueError("安装目录不能是files根目录: %s" % t)  # 杜绝上次dill装进files根目录的问题
        os.makedirs(t, exist_ok=True)  # 先建目录,避免FileFinder缓存"不存在"
        try: ok = os.access(tempfile.gettempdir(), os.W_OK)
        except Exception: ok = False
        if not ok:
            tmp = os.path.join(_cache_dir(base), "runtime_pip_tmp")
            os.makedirs(tmp, exist_ok=True)
            os.environ["TMPDIR"] = tmp; tempfile.tempdir = tmp  # Android没有可写/tmp
        if TARGET_DIR and TARGET_DIR != t:
            while TARGET_DIR in sys.path: sys.path.remove(TARGET_DIR)  # 移除旧的(可能错误的)路径
        FILES_DIR, TARGET_DIR = base, t
        _ensure_path(t)
        return t
def _target():  # 每次安装前重新校验目标目录,防止残留错误配置
    t = TARGET_DIR
    if not t or not FILES_DIR or os.path.normpath(t) == FILES_DIR: return configure()
    os.makedirs(t, exist_ok=True)
    _ensure_path(t)
    return t
def _android_api():  # 设备API级别:优先Build.VERSION.SDK_INT,其次编译期API
    try:
        from java import jclass
        return int(jclass("android.os.Build$VERSION").SDK_INT)
    except Exception: pass
    try: return int(sys.getandroidapilevel())
    except Exception: return None
def _env():  # 当前进程的wheel兼容参数:Python版本/实现/API/ABI
    global _ENV
    if _ENV is None:
        abi, m = None, re.match(r"android-\d+-(\w+)", sysconfig.get_platform() or "")  # Python3.13+官方格式 android-24-arm64_v8a
        if m: abi = m.group(1)
        if not abi:
            abi = _ABI.get(platform.machine().lower())
            if abi and struct.calcsize("P") == 4: abi = {"arm64_v8a": "armeabi_v7a", "x86_64": "x86"}.get(abi, abi)  # 64位内核上的32位进程
        _ENV = {"py": "%d.%d" % sys.version_info[:2], "cp": "cp%d%d" % sys.version_info[:2], "impl": sys.implementation.name, "api": _android_api(), "abi": abi}
    return _ENV
def _native_android():  # pip自身生成的兼容标签是否已含android_*(新pip+官方Android Python)
    global _NATIVE
    if _NATIVE is None:
        try:
            from pip._internal.utils.compatibility_tags import get_supported
            _NATIVE = any(t.platform.startswith("android") for t in get_supported())
        except Exception: _NATIVE = False
    return _NATIVE
def _platform_args(extra):  # pip模式下的--platform参数,覆盖16..设备API的所有Chaquopy标签
    if not AUTO_ANDROID_PLATFORM or any(str(a).startswith("--platform") for a in extra) or _native_android(): return []
    e = _env()
    if not e["api"] or not e["abi"]: return []
    out = []
    for lv in range(16, e["api"] + 1): out += ["--platform", "android_%d_%s" % (lv, e["abi"])]
    return out
def _wheel_score(fn):  # 判断wheel是否可在本机使用:3=精确cpXY原生 2=abi3原生 1=纯Python None=不兼容
    p = fn[:-4].split("-")
    if len(p) not in (5, 6): return None
    e = _env(); maj, mi = sys.version_info[:2]
    pys, abis, plats = p[-3].split("."), p[-2].split("."), p[-1].split(".")
    if "any" in plats:
        if "none" not in abis: return None
        for t in pys:
            m = re.match(r"^(py|cp)(\d)(\d*)$", t)
            if not m or int(m.group(2)) != maj: continue
            if m.group(1) == "cp" and m.group(3) != str(mi): continue  # cpXY-none-any必须精确匹配
            if not m.group(3) or int(m.group(3)) <= mi: return 1  # py3 / py3X(X<=当前)
        return None
    if e["impl"] != "cpython" or not e["abi"]: return None
    if not any((lambda m: m and m.group(2) == e["abi"] and (not e["api"] or int(m.group(1)) <= e["api"]))(re.match(r"^android_(\d+)_(\w+)$", pl)) for pl in plats): return None  # 平台:ABI一致且最低API<=设备API
    for t in pys:
        m = re.match(r"^cp(\d)(\d+)$", t)
        if not m or int(m.group(1)) != maj: continue
        if int(m.group(2)) == mi and (e["cp"] in abis or "none" in abis): return 3
        if "abi3" in abis and int(m.group(2)) <= mi: return 2
    return None
def _index_args(extra, index_url=None, extra_index_urls=None):  # 默认Chaquopy主源+清华备用源;用户在extra里指定则不覆盖
    a = []
    if not any(x in ("-i", "--index-url") or x.startswith("--index-url=") for x in extra): a += ["-i", index_url or CHAQUO_INDEX]
    for u in ([EXTRA_INDEX] if extra_index_urls is None else list(extra_index_urls)):
        if u and u not in extra: a += ["--extra-index-url", u]
    return a
def _index_urls(args):  # 从参数中解析出所有索引URL,供内置直装使用
    out, it = [], iter(args)
    for x in it:
        if x in ("-i", "--index-url", "--extra-index-url"): v = next(it, None)
        elif x.startswith(("--index-url=", "--extra-index-url=")): v = x.split("=", 1)[1]
        else: continue
        if v and v not in out: out.append(v)
    return out
def _base_args(t): return ["--target", t, "--upgrade", "--no-compile", "--no-build-isolation", "--disable-pip-version-check", "--no-cache-dir", "--no-input", "--no-color", "--progress-bar", "off"]  # pip公共参数
def _run_pip(args):  # 在当前进程运行pip,运行前快照、运行后强制还原所有被污染的全局状态
    buf = io.StringIO()
    with _LOCK:
        _patch_asset_path()
        try: from pip._internal.cli.main import main as pip_main
        except Exception as e: return 1, "import pip failed: %r (build.gradle的pip块需 install \"pip\")" % e
        _patch_pip_env()
        meta, hooks, spath, pic = sys.meta_path[:], sys.path_hooks[:], sys.path[:], dict(sys.path_importer_cache)  # 坑2:备份导入系统
        wfilters, wshow = warnings.filters[:], warnings.showwarning  # 备份warnings配置
        root = logging.getLogger()
        rh, rl = root.handlers[:], root.level  # 备份根logger
        clr = getattr(logging.config, "_clearExistingHandlers", None)
        try: loc = locale.setlocale(locale.LC_ALL)
        except Exception: loc = None
        so, se = sys.stdout, sys.stderr
        ro, re_ = _Router(so), _Router(se)
        tid = threading.get_ident()
        _ROUTES[tid] = buf  # 坑4:只截获本线程输出
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
                if k not in pic or type(v).__module__.startswith("pip"): sys.path_importer_cache.pop(k, None)  # 清掉pip期间产生的导入器缓存
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
def _ssl_ctx():  # 系统CA不可用时,用pip自带certifi证书构建SSL上下文
    global _SSLCTX
    if _SSLCTX is None:
        cadata = None
        try: cadata = importlib.resources.files("pip._vendor.certifi").joinpath("cacert.pem").read_text(encoding="utf-8")
        except Exception:
            try:
                import pip._vendor.certifi as c
                cadata = _rd(c.where())
            except Exception: pass
        _SSLCTX = ssl.create_default_context(cadata=cadata) if cadata else ssl.create_default_context()
    return _SSLCTX
def _http(url, dest=None):  # 下载文本(dest=None)或文件;SSL失败自动改用certifi重试
    import importlib.resources
    rq = urllib.request.Request(url, headers={"User-Agent": "runtime_pip/3 (Chaquopy)", "Accept": "text/html,*/*"})
    try: r = urllib.request.urlopen(rq, timeout=HTTP_TIMEOUT)
    except urllib.error.URLError as e:
        if not isinstance(getattr(e, "reason", None), ssl.SSLError): raise
        r = urllib.request.urlopen(rq, timeout=HTTP_TIMEOUT, context=_ssl_ctx())
    with r:
        if dest is None: return r.read().decode("utf-8", "replace"), r.geturl()
        with open(dest, "wb") as f: shutil.copyfileobj(r, f, 1 << 16)
        return dest, r.geturl()
def _page_links(url):  # 解析PEP503简单索引/Apache目录列表(Chaquopy源)/find-links平铺页/本地目录,返回[(文件名,URL,属性)]
    if os.path.isdir(url): return [(f, os.path.join(url, f), "") for f in _ls(url)]
    try: html, base = _http(url)
    except Exception: return []  # 404(该源没有此包)或网络错误都视为无候选
    out = []
    for m in re.finditer(r"<a\s+([^>]*)>", html, re.I):
        attrs = m.group(1)
        h = re.search(r"href\s*=\s*[\"']([^\"']*)[\"']", attrs, re.I)
        if not h: continue
        u = urllib.parse.urljoin(base, unescape(h.group(1)))
        fn = urllib.parse.unquote(urllib.parse.urlparse(u).path.rsplit("/", 1)[-1])  # 用href取文件名(Apache列表的显示文本会被截断)
        if fn: out.append((fn, u, attrs))
    return out
def _candidates(req, ctx):  # 在所有源中收集满足版本约束的候选:兼容wheel、sdist、被拒的Android wheel(用于诊断)
    from pip._vendor.packaging.version import Version, InvalidVersion
    from pip._vendor.packaging.specifiers import SpecifierSet
    c, pyv, seen, wheels, sdists, rej = _canon(req.name), "%d.%d.%d" % sys.version_info[:3], set(), [], [], []
    for pg in [u.rstrip("/") + "/" + c + "/" for u in ctx["indexes"]] + list(ctx["flat"]):
        if pg not in ctx["pages"]: ctx["pages"][pg] = _page_links(pg)  # 同一次安装内缓存页面
        for fn, u, attrs in ctx["pages"][pg]:
            if fn in seen or re.search(r"data-yanked", attrs, re.I): continue
            seen.add(fn)
            if fn.endswith(".whl"):
                p = fn[:-4].split("-")
                if len(p) not in (5, 6): continue
                nm, ver = p[0], p[1]
            else:
                ext = next((x for x in _SDIST_EXT if fn.lower().endswith(x)), None)
                if not ext: continue
                nm, _, ver = fn[:-len(ext)].rpartition("-")
            if _canon(nm) != c: continue
            try: v = Version(ver)
            except InvalidVersion: continue
            if not req.specifier.contains(v): continue
            rp = re.search(r"data-requires-python\s*=\s*[\"']([^\"']*)[\"']", attrs, re.I)
            try:
                if rp and rp.group(1).strip() and not SpecifierSet(unescape(rp.group(1))).contains(pyv, prereleases=True): continue
            except Exception: pass
            if fn.endswith(".whl"):
                s = _wheel_score(fn)
                if s is None:
                    if "android" in fn: rej.append(fn)  # 只记录Android wheel,便于诊断Python版本/ABI不匹配
                    continue
                b = int((re.match(r"\d*", p[2]).group() or 0)) if len(p) == 6 else 0  # Chaquopy的build号,越大越新
                wheels.append(((v, s, b), u, fn))
            else: sdists.append((v, u, fn))
    wheels.sort(key=lambda x: x[0], reverse=True)
    sdists.sort(key=lambda x: x[0], reverse=True)
    return wheels, sdists, rej
def _fetch(url, fn, work):  # 下载到临时目录并校验URL片段中的哈希;本地路径直接返回
    if os.path.isfile(url): return url
    path = os.path.join(work, os.path.basename(fn) or "download")
    _http(url.split("#", 1)[0], path)
    m = re.match(r"(sha256|sha384|sha512|md5)=([0-9a-fA-F]+)", urllib.parse.urlparse(url).fragment)
    if m:
        h = hashlib.new(m.group(1))
        with open(path, "rb") as f:
            for b in iter(lambda: f.read(1 << 16), b""): h.update(b)
        if h.hexdigest().lower() != m.group(2).lower(): raise ValueError("hash mismatch: %s" % fn)
    return path
def _find_dist_info(t, name):  # 在目标目录中找某个分发包的dist-info
    c = _canon(name)
    for d in _ls(t):
        if d.endswith(".dist-info") and _canon(d[:-10].rsplit("-", 1)[0]) == c: return os.path.join(t, d)
    return None
def _uninstall(t, name):  # 按RECORD卸载旧版本(只删自己的文件,不误伤命名空间包的其他部分)
    di = _find_dist_info(t, name)
    if not di: return
    rt = os.path.realpath(t); real, rdi, dirs = rt + os.sep, os.path.realpath(di), set()
    rp = os.path.join(di, "RECORD")
    for line in (_rd(rp).splitlines() if os.path.isfile(rp) else []):
        f = line.split(",", 1)[0].strip()
        if not f: continue
        p = os.path.realpath(os.path.join(t, f))
        if not p.startswith(real) or p.startswith(rdi + os.sep): continue  # 越界路径与dist-info本身跳过
        try: os.remove(p)
        except OSError: pass
        dirs.add(os.path.dirname(p))
    for d in sorted(dirs, key=len, reverse=True):
        while d.startswith(real):
            shutil.rmtree(os.path.join(d, "__pycache__"), ignore_errors=True)
            try: os.rmdir(d)  # 只删除已空的目录
            except OSError: break
            d = os.path.dirname(d)
    shutil.rmtree(di, ignore_errors=True)
def _install_wheel_file(path, t):  # 内置wheel安装器:绕过pip的标签判断直接解压;.data只取purelib/platlib,跳过scripts避免生成bin
    real = os.path.realpath(t) + os.sep
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        dis = sorted({n.split("/")[0] for n in names if n.count("/") == 1 and n.endswith("/METADATA") and n.split("/")[0].endswith(".dist-info")})
        if not dis: raise ValueError("不是有效的wheel(缺少*.dist-info/METADATA): %s" % path)
        di = dis[0]; data = di[:-10] + ".data/"
        _uninstall(t, di[:-10].rsplit("-", 1)[0])  # 升级:先按RECORD卸载旧版
        written = []
        for n in names:
            if n.endswith("/"): continue
            rel = n
            if n.startswith(data):
                parts = n[len(data):].split("/", 1)
                if len(parts) < 2 or parts[0] not in ("purelib", "platlib"): continue  # 丢弃scripts/headers/data
                rel = parts[1]
            dst = os.path.realpath(os.path.join(t, *rel.split("/")))
            if not dst.startswith(real): raise ValueError("wheel中存在越界路径: %s" % n)  # 防路径穿越
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with z.open(n) as s, open(dst, "wb") as f: shutil.copyfileobj(s, f, 1 << 16)
            written.append(dst[len(real):].replace(os.sep, "/"))
    dp = os.path.join(t, di)
    _wr(os.path.join(dp, "INSTALLER"), "runtime_pip\n")
    _wr(os.path.join(dp, "RECORD"), "".join("%s,,\n" % w for w in sorted(set(written + [di + "/INSTALLER"])) if w != di + "/RECORD") + di + "/RECORD,,\n")  # 重写RECORD以匹配实际落盘路径,便于下次卸载
    return dp
def _extract(path, dest):  # 安全解压sdist(防路径穿越,忽略链接/设备文件)
    os.makedirs(dest, exist_ok=True)
    real = os.path.realpath(dest)
    safe = lambda n: os.path.realpath(os.path.join(dest, n)).startswith(real + os.sep)
    if path.lower().endswith(".zip"):
        with zipfile.ZipFile(path) as z:
            bad = [n for n in z.namelist() if not safe(n)]
            if bad: raise ValueError("unsafe path in archive: %s" % bad[0])
            z.extractall(dest)
    else:
        with tarfile.open(path) as tf:
            ms = [mi for mi in tf.getmembers() if (mi.isfile() or mi.isdir()) and safe(mi.name)]
            tf.extractall(dest, members=ms, **({"filter": "data"} if hasattr(tarfile, "data_filter") else {}))
    items = [os.path.join(dest, x) for x in os.listdir(dest)]
    return items[0] if len(items) == 1 and os.path.isdir(items[0]) else dest
def _setup_kwargs(src):  # 静态解析setup.py(AST)与setup.cfg,不执行任何代码,不chdir,不影响RPC线程
    out, p = {}, os.path.join(src, "setup.py")
    if os.path.isfile(p):
        try: tree = ast.parse(open(p, "rb").read())
        except Exception: tree = None
        for node in (ast.walk(tree) if tree else []):
            if isinstance(node, ast.Call) and getattr(node.func, "id", getattr(node.func, "attr", None)) == "setup":
                for kw in node.keywords:
                    if kw.arg in ("py_modules", "packages", "package_dir", "install_requires", "ext_modules"):
                        try: out[kw.arg] = ast.literal_eval(kw.value)
                        except Exception: out.setdefault(kw.arg, None if kw.arg != "ext_modules" else True)  # 非字面量交给启发式;ext_modules存在即视为有C扩展
    c = os.path.join(src, "setup.cfg")
    if os.path.isfile(c):
        cp = configparser.ConfigParser(interpolation=None)
        try: cp.read(c, encoding="utf-8")
        except Exception: cp = None
        if cp is not None and cp.has_section("options"):
            o, lines = cp["options"], lambda s: [x.strip() for x in re.split(r"[\n,]", s) if x.strip() and not x.strip().startswith("#")]
            if "py_modules" in o and not out.get("py_modules"): out["py_modules"] = lines(o["py_modules"])
            if "packages" in o and not out.get("packages"): out["packages"] = None if o["packages"].strip().startswith("find") else lines(o["packages"])
            if "package_dir" in o and not out.get("package_dir"): out["package_dir"] = {k.strip(): v.strip() for k, _, v in (x.partition("=") for x in lines(o["package_dir"]))}
            if "install_requires" in o and not out.get("install_requires"): out["install_requires"] = [x.strip() for x in o["install_requires"].splitlines() if x.strip() and not x.strip().startswith("#")]
            if cp.has_section("options.packages.find"): out["find_where"] = cp["options.packages.find"].get("where", "").strip()
    return out
def _collect(src, kw):  # 找出sdist中需要复制的顶层包/模块:先按声明,再启发式扫描根目录/src/lib
    pd = kw.get("package_dir") if isinstance(kw.get("package_dir"), dict) else {}
    root, items = os.path.join(src, pd.get("", "") or kw.get("find_where") or ""), {}
    for p in (kw.get("packages") if isinstance(kw.get("packages"), (list, tuple)) else []):
        top = str(p).split(".")[0]
        d = os.path.join(src, pd[top]) if top in pd else os.path.join(root, top)
        if os.path.isdir(d): items[top] = d
    for m in (kw.get("py_modules") if isinstance(kw.get("py_modules"), (list, tuple)) else []):
        f = os.path.join(root, *str(m).split(".")) + ".py"
        if os.path.isfile(f): items[str(m).split(".")[0]] = f
    if items: return items
    for r in [root] + [os.path.join(src, x) for x in ("src", "lib3", "lib")]:
        for n in sorted(_ls(r)):
            f = os.path.join(r, n)
            if os.path.isdir(f) and n.isidentifier() and n.lower() not in _EXCL_DIR and os.path.isfile(os.path.join(f, "__init__.py")): items.setdefault(n, f)
            elif n.endswith(".py") and n[:-3].isidentifier() and n[:-3].lower() not in _EXCL_PY and os.path.isfile(f): items.setdefault(n[:-3], f)
        if items: break
    return items
def _has_native(src):  # sdist是否包含需要编译的源码
    for _, _, fns in os.walk(src):
        if any(f.lower().endswith(_NATIVE_SRC) for f in fns): return True
    return False
def _write_dist_info(t, name, ver, src, names, reqs):  # 为sdist安装生成标准dist-info,importlib.metadata/pip可识别
    di = os.path.join(t, "%s-%s.dist-info" % (re.sub(r"[-_.]+", "_", name), ver))
    os.makedirs(di, exist_ok=True)
    pk = os.path.join(src, "PKG-INFO")
    meta = _rd(pk).replace("\r\n", "\n") if os.path.isfile(pk) else "Metadata-Version: 2.1\nName: %s\nVersion: %s\n" % (name, ver)
    if "\nRequires-Dist:" not in meta and reqs:
        head, sep, body = meta.partition("\n\n")
        meta = head.rstrip("\n") + "".join("\nRequires-Dist: %s" % x for x in reqs) + "\n" + (sep + body if sep else "")  # 老式PKG-INFO缺依赖声明时从setup.py补上
    files = {"METADATA": meta, "INSTALLER": "runtime_pip\n", "top_level.txt": "\n".join(sorted(names)) + "\n", "WHEEL": "Wheel-Version: 1.0\nGenerator: runtime_pip\nRoot-Is-Purelib: true\nTag: py3-none-any\n"}
    for k, v in files.items(): _wr(os.path.join(di, k), v)
    rec = []
    for n in names:
        p = os.path.join(t, n)
        if os.path.isdir(p): rec += [os.path.relpath(os.path.join(dp, f), t) for dp, _, fs in os.walk(p) for f in fs]
        else: rec.append(n + ".py")
    rec += [os.path.relpath(os.path.join(di, k), t) for k in list(files) + ["RECORD"]]
    _wr(os.path.join(di, "RECORD"), "".join("%s,,\n" % r.replace(os.sep, "/") for r in rec))
    return di
def _sdist_install(r, best, work, ctx):  # sdist回退:纯Python包(如pyDes)直接复制源码;需编译的包(如pycurl)明确报错
    v, u, fn = best
    t = ctx["t"]
    src = _extract(_fetch(u, fn, work), os.path.join(work, "src"))
    kw = _setup_kwargs(src)
    items, native = _collect(src, kw), bool(kw.get("ext_modules")) or _has_native(src)
    want = {_canon(r.name).replace("-", "_"), _ALIASES.get(_canon(r.name), "").split(".")[0].lower()}
    if not items or (native and not any(n.lower() in want for n in items)): return False, "sdist %s 需要编译C扩展,设备端无法编译(进程内不能起编译子进程);请用Chaquopy源中与当前Python版本匹配的wheel、自建wheel仓库(FIND_LINKS)或install_wheel()" % fn
    if native: ctx["notes"].append("%s 含可选C加速源码,仅安装了纯Python部分" % fn)
    _uninstall(t, r.name)
    for n, p in items.items():
        dst = os.path.join(t, n if os.path.isdir(p) else n + ".py")
        if os.path.isdir(dst): shutil.rmtree(dst)
        elif os.path.exists(dst): os.remove(dst)
        if os.path.isdir(p): shutil.copytree(p, dst, ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "*.c", "*.cpp", "*.pyx", "*.h"))
        else: shutil.copy2(p, dst)
    _write_dist_info(t, r.name, str(v), src, list(items), kw.get("install_requires") if isinstance(kw.get("install_requires"), list) else [])
    return True, "sdist(pure): %s -> %s" % (fn, ", ".join(items))
def _direct(spec, ctx):  # 内置直装:自己挑选兼容的Android/纯Python wheel,不行再sdist纯Python回退
    r = _req(spec)
    if r is None: return False, "无效的依赖描述 %r" % spec
    wheels, sdists, rej = _candidates(r, ctx)
    work = tempfile.mkdtemp(prefix="rtpip-")
    try:
        if wheels:
            _, u, fn = wheels[0]
            _install_wheel_file(_fetch(u, fn, work), ctx["t"])
            return True, "direct-wheel: %s" % fn
        if sdists and SDIST_FALLBACK: return _sdist_install(r, sdists[0], work, ctx)
        e = _env()
        msg = "各源均无兼容 %s / android_%s_%s 的wheel,也无sdist" % (e["cp"], e["api"], e["abi"])
        if rej: msg += ";存在但不兼容的Android wheel(通常是Python版本不匹配): " + ", ".join(rej[-8:])
        return False, msg
    except Exception: return False, "direct error:\n" + traceback.format_exc()
    finally: shutil.rmtree(work, ignore_errors=True)
def _top_levels(di):  # 从top_level.txt或RECORD推断真实导入名(如pyDes而不是pydes)
    p = os.path.join(di, "top_level.txt")
    if os.path.isfile(p):
        names = [l.strip() for l in _rd(p).splitlines() if l.strip() and "/" not in l]
        if names: return names
    out, rp = [], os.path.join(di, "RECORD")
    if not os.path.isfile(rp): return out
    for line in _rd(rp).splitlines():
        f = line.split(",", 1)[0].strip().replace("\\", "/")
        if not f or f.startswith(("..", "/")): continue
        head = f.split("/", 1)[0]
        if head.endswith((".dist-info", ".data")) or head in ("__pycache__", "bin", "chaquopy"): continue  # chaquopy/lib是原生依赖库目录,不是模块
        if "/" in f: n = head
        elif f.endswith(".py"): n = f[:-3]
        elif re.search(r"\.(so|pyd)$", f): n = f.split(".", 1)[0]
        else: continue
        if n.isidentifier() and n not in out: out.append(n)
    return out
def _pick_module(pkg, names):  # 在多个顶层名中选出与包名最匹配的
    c = _canon(_req_name(pkg)).replace("-", "_")
    for n in names:
        if n.lower() == c: return n
    for n in names:
        if not n.startswith("_"): return n
    return names[0] if names else None
def _resolve_module(pkg, t):  # 导入名:dist-info优先,其次别名表,最后按包名猜
    k = _canon(_req_name(pkg))
    di = _find_dist_info(t, _req_name(pkg))
    n = _pick_module(pkg, _top_levels(di)) if di else None
    if k in _ALIASES and (not n or _ALIASES[k].split(".")[0] == n): return _ALIASES[k]
    return n or _ALIASES.get(k) or k.replace("-", "_")
def _guess_modules(pkg):  # 未安装时猜测可能的导入名列表
    n = _req_name(pkg); k = _canon(n)
    c = [_ALIASES[k]] if k in _ALIASES else []
    try: c += [x for x in (importlib.metadata.distribution(n).read_text("top_level.txt") or "").split() if x.isidentifier()]
    except Exception: pass
    for x in (n, n.replace("-", "_"), k.replace("-", "_")):
        if x not in c: c.append(x)
    return c
def _satisfied(r):  # 依赖是否已满足:metadata优先;Chaquopy打包进APK的包可能无metadata,用find_spec兜底,避免重复安装遮蔽App自带包
    try: return r.specifier.contains(importlib.metadata.version(r.name), prereleases=True)
    except importlib.metadata.PackageNotFoundError: pass
    except Exception: return True
    for m in _guess_modules(r.name):
        try:
            if m.isidentifier() and importlib.util.find_spec(m) is not None: return True
        except Exception: pass
    return False
def _meta_requires(di):  # 读取METADATA中的Requires-Dist
    p = di and os.path.join(di, "METADATA")
    if not p or not os.path.isfile(p): return []
    head = _rd(p).replace("\r\n", "\n").split("\n\n", 1)[0]
    return [l.split(":", 1)[1].strip() for l in head.split("\n") if l.lower().startswith("requires-dist:")]
def _needed(reqs, extras=()):  # 过滤掉marker不适用或已满足的依赖
    out = []
    for s in reqs or []:
        r = _req(s)
        if r is None: continue
        try:
            if r.marker and not any(r.marker.evaluate({"extra": e}) for e in (list(extras) or [""])): continue
        except Exception: continue
        if _satisfied(r): continue
        out.append(r.name + ("[%s]" % ",".join(sorted(r.extras)) if r.extras else "") + str(r.specifier))
    return out
def _install_one(spec, ctx):  # 单包流程:pip(--no-deps,仅wheel,绝不触发源码构建) -> 内置直装 -> 递归安装缺失依赖
    c = _canon(_req_name(spec))
    if c in ctx["seen"]: return True
    ctx["seen"].add(c)
    ok = False
    if USE_PIP:
        code, log = _run_pip(["install", spec, "--no-deps", "--only-binary=:all:"] + _base_args(ctx["t"]) + _platform_args(ctx["extra"]) + ctx["idx"] + ctx["extra"])  # 只取wheel,避免_in_process.py OSError
        ctx["logs"].append("[pip %s] code=%s\n%s" % (spec, code, log.strip()))
        if code == 0: ok, ctx["methods"][c] = True, "pip"
    if not ok:
        ok, msg = _direct(spec, ctx)
        ctx["logs"].append("[direct %s] %s" % (spec, msg))
        if ok: ctx["methods"][c] = msg.split(":", 1)[0]
    if not ok: return False
    r = _req(spec)
    for d in _needed(_meta_requires(_find_dist_info(ctx["t"], _req_name(spec))), r.extras if r else ()):  # 自己解析依赖:已满足(含APK自带)的不重复安装
        if not _install_one(d, ctx): ctx["notes"].append("依赖安装失败: %s" % d)
    return True
def _preload_libs(t):  # Chaquopy原生依赖库(如chaquopy-curl的libcurl.so)在chaquopy/lib下,先以RTLD_GLOBAL预加载,扩展模块dlopen时按soname即可找到
    pending = [p for p in sorted(glob.glob(os.path.join(t, "chaquopy", "lib", "*.so*"))) if p not in _LOADED]
    if not pending: return
    import ctypes
    while pending:
        nxt = []
        for p in pending:
            try: ctypes.CDLL(p, mode=getattr(ctypes, "RTLD_GLOBAL", 0)); _LOADED.add(p)
            except OSError: nxt.append(p)  # 依赖尚未加载,下一轮重试
        if len(nxt) == len(pending): break  # 无进展则停止,交给import报真实错误
        pending = nxt
def _is_py_item(root, n):  # 判断files根目录中的某项是否是Python包产物
    p = os.path.join(root, n)
    return n.endswith((".dist-info", ".egg-info")) or (n.endswith(".py") and os.path.isfile(p)) or os.path.isfile(os.path.join(p, "__init__.py")) or bool(re.search(r"\.(so|pyd)$", n))
def _load(mod, t):  # 坑3:绝不del sys.modules;已加载则reload原地热更新,否则import
    _ensure_path(t)
    m = sys.modules.get(mod)
    if m is not None: return importlib.reload(m), "reloaded"
    return importlib.import_module(mod), "imported"
def _mkctx(extra, index_url, extra_index_urls, find_links):  # 构造一次安装的上下文
    t = _target()
    idx = _index_args(extra, index_url, extra_index_urls)
    fl = [str(u) for u in list(FIND_LINKS) + list(find_links or [])]
    return {"t": t, "extra": extra, "idx": idx + [x for u in fl for x in ("--find-links", u)], "indexes": _index_urls(idx + extra), "flat": fl, "pages": {}, "seen": set(), "logs": [], "notes": [], "methods": {}}
def _res(pkg, mod): return {"ok": False, "package": pkg, "module": mod, "target": TARGET_DIR, "method": None, "deps": {}, "action": None, "version": None, "error": None, "notes": [], "leaked": [], "env": dict(_env()), "log": ""}  # 统一的返回结构
def _finish(res, ctx, name, mod, before):  # 汇总结果、检测根目录泄漏、预加载原生库并import/reload
    c = _canon(name)
    res["target"], res["method"] = ctx["t"], ctx["methods"].get(c)
    res["deps"] = {k: v for k, v in ctx["methods"].items() if k != c}
    res["notes"], res["log"] = ctx["notes"], "\n".join(x for x in ctx["logs"] if x)[-LOG_TAIL:]
    res["leaked"] = sorted(n for n in set(_ls(FILES_DIR)) - before if _is_py_item(FILES_DIR, n))  # 非空说明有东西被写进了files根目录
    if not res["method"]:
        res["error"] = "install failed (详见log)"
        return res
    mod = mod or _resolve_module(name, ctx["t"])
    res["module"] = mod
    try:
        _preload_libs(ctx["t"])
        m, res["action"] = _load(mod, ctx["t"])
        res["version"], res["ok"] = getattr(m, "__version__", None), True
    except (Exception, SystemExit) as e: res["error"] = "%s: %s" % (type(e).__name__, e)
    return res
def install(package_name, extra_args=None, module_name=None, index_url=None, extra_index_urls=None, find_links=None):  # 安装单个包并立即import/reload,永不抛异常
    extra = [str(a) for a in (extra_args or [])]
    res = _res(package_name, module_name)
    with _LOCK:
        try: ctx = _mkctx(extra, index_url, extra_index_urls, find_links)
        except Exception as e:
            res["error"] = "configure failed: %s" % e
            return res
        before = set(_ls(FILES_DIR))
        try: _install_one(package_name, ctx)
        except Exception: ctx["logs"].append(traceback.format_exc())
        return _finish(res, ctx, _req_name(package_name), module_name, before)
def install_wheel(src, module_name=None, deps=True, extra_args=None, index_url=None, extra_index_urls=None, find_links=None):  # 直接安装wheel:src可为本地路径/URL/bytes(PC端构建后经RPC推送字节即可)
    res = _res(None, module_name)
    with _LOCK:
        work = tempfile.mkdtemp(prefix="rtpip-")
        try:
            ctx = _mkctx([str(a) for a in (extra_args or [])], index_url, extra_index_urls, find_links)
            before = set(_ls(FILES_DIR))
            if isinstance(src, (bytes, bytearray)):
                p = os.path.join(work, "upload.whl")
                with open(p, "wb") as f: f.write(src)
            elif re.match(r"https?://", str(src)): p = _fetch(str(src), urllib.parse.unquote(urllib.parse.urlparse(str(src)).path.rsplit("/", 1)[-1]) or "download.whl", work)
            else: p = str(src)
            di = _install_wheel_file(p, ctx["t"])
            name = os.path.basename(di)[:-10].rsplit("-", 1)[0]
            res["package"] = name
            ctx["seen"].add(_canon(name)); ctx["methods"][_canon(name)] = "wheel-file"
            if deps:
                for d in _needed(_meta_requires(di)):
                    if not _install_one(d, ctx): ctx["notes"].append("依赖安装失败: %s" % d)
            return _finish(res, ctx, name, module_name, before)
        except Exception:
            res["error"] = traceback.format_exc()[-LOG_TAIL:]
            return res
        finally: shutil.rmtree(work, ignore_errors=True)
def _executor():  # 懒创建单工作线程执行器
    global _EXEC
    with _ELOCK:
        if _EXEC is None: _EXEC = ThreadPoolExecutor(max_workers=1, thread_name_prefix="runtime_pip")
        return _EXEC
def install_async(package_name, extra_args=None, module_name=None, index_url=None, extra_index_urls=None, find_links=None, callback=None):  # 后台安装,返回Future,RPC线程立即返回
    f = _executor().submit(install, package_name, extra_args, module_name, index_url, extra_index_urls, find_links)
    if callback: f.add_done_callback(lambda fu: callback(fu.result()))
    return f
def _try_import(pkg, mod):  # 尝试按可能的模块名导入
    for m in ([mod] if mod else _guess_modules(pkg)):
        try: return importlib.import_module(m), m
        except (Exception, SystemExit): pass
    return None, None
def install_missing(packages, extra_args=None, wait=True, index_url=None, extra_index_urls=None, find_links=None):  # packages: ['pyDes', ('beautifulsoup4','bs4')] 或 {'pip名':'模块名'};wait=False返回Future
    if isinstance(packages, str): packages = [packages]
    items = list(packages.items()) if isinstance(packages, dict) else [(p, None) if isinstance(p, str) else (p[0], p[1] if len(p) > 1 else None) for p in packages]
    def job():
        out = {}
        try: _target()
        except Exception: pass
        for pkg, mod in items:
            m, name = _try_import(pkg, mod)
            if m is not None:
                r = _res(pkg, name)
                r.update(ok=True, action="present", version=getattr(m, "__version__", None))
                out[pkg] = r
            else: out[pkg] = install(pkg, extra_args, mod, index_url, extra_index_urls, find_links)
        return out
    return job() if wait else _executor().submit(job)
def migrate_root_packages(dry_run=True):  # 把旧版本误装到files根目录的包迁入site-packages(默认只预览;bin/需手动删除)
    with _LOCK:
        t, root, moved = _target(), FILES_DIR, []
        for d in _ls(root):
            if not d.endswith(".dist-info"): continue
            names = [n for n in _top_levels(os.path.join(root, d)) if n not in _PROTECTED]
            for cand in [x for n in names for x in (n, n + ".py")] + [d]:
                src = os.path.join(root, cand)
                if not os.path.exists(src) or not _is_py_item(root, cand): continue
                moved.append(cand)
                if dry_run: continue
                dst = os.path.join(t, cand)
                if os.path.isdir(dst): shutil.rmtree(dst)
                elif os.path.exists(dst): os.remove(dst)
                shutil.move(src, dst)
        importlib.invalidate_caches()
        return moved
