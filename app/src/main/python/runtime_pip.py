import os, sys, io, re, tempfile, threading, importlib, warnings, logging, logging.config, traceback, platform, struct, types
from concurrent.futures import ThreadPoolExecutor
DEFAULT_INSTALL_DIR = "site-packages"  # Always created below Android filesDir

__all__ = ["configure", "install", "install_missing", "install_async", "CHAQUO_INDEX", "EXTRA_INDEX", "DEFAULT_INSTALL_DIR", "TARGET_DIR"]
CHAQUO_INDEX = "https://chaquo.com/pypi-13.1/"  # Chaquopy Android wheel index
EXTRA_INDEX = "https://pypi.tuna.tsinghua.edu.cn/simple"  # Pure-Python and source fallback index

LOG_TAIL = 12000  # Keep RPC results bounded
TARGET_DIR = None  # Set by configure
_LOCK = threading.RLock()  # pip and import-system mutations must be serialized
_EXEC_LOCK = threading.Lock()
_EXECUTOR = None
_OUTPUTS = {}  # Thread id -> pip output buffer
_ABI = {"aarch64": "arm64_v8a", "arm64": "arm64_v8a", "armv8l": "armeabi_v7a", "armv7l": "armeabi_v7a", "x86_64": "x86_64", "amd64": "x86_64", "i686": "x86", "x86": "x86"}
_ALIASES = {"pydes": "pyDes", "pyyaml": "yaml", "beautifulsoup4": "bs4", "pillow": "PIL", "opencv-python": "cv2", "opencv-python-headless": "cv2", "scikit-learn": "sklearn", "python-dateutil": "dateutil", "paho-mqtt": "paho.mqtt", "pycryptodome": "Crypto", "protobuf": "google.protobuf", "pyserial": "serial", "msgpack-python": "msgpack"}
class _OutputRouter(io.TextIOBase):
    def __init__(self, original): self.original = original
    def _stream(self): return _OUTPUTS.get(threading.get_ident(), self.original)
    def write(self, text):
        result = self._stream().write(text)
        return len(text) if result is None else result
    def flush(self):
        try: self._stream().flush()
        except Exception: pass
    def writable(self): return True
    def isatty(self): return False
    def fileno(self): raise io.UnsupportedOperation("fileno")
    @property
    def encoding(self): return getattr(self.original, "encoding", None) or "utf-8"
    @property
    def errors(self): return getattr(self.original, "errors", None) or "replace"
    def __getattr__(self, name): return getattr(self.original, name)
def _files_dir():
    try:
        from java import jclass
        return str(jclass("com.chaquo.python.Python").getPlatform().getApplication().getFilesDir().toString())
    except Exception: return os.environ.get("HOME") or os.path.expanduser("~") or os.getcwd()
def _ensure_path(path):
    path = os.path.abspath(path)
    sys.path[:] = [path] + [entry for entry in sys.path if entry != path]
    sys.path_importer_cache.pop(path, None)  # Remove a cached missing-directory finder
    importlib.invalidate_caches()
def configure(files_dir=None):
    global TARGET_DIR
    with _LOCK:
        if files_dir is None and TARGET_DIR: target = TARGET_DIR
        else:
            root = os.path.abspath(str(files_dir or _files_dir()))
            target = root if os.path.basename(os.path.normpath(root)) == DEFAULT_INSTALL_DIR else os.path.join(root, DEFAULT_INSTALL_DIR)
        os.makedirs(target, exist_ok=True)  # Do this before adding it to sys.path
        temp_dir = os.path.join(target, ".runtime_pip_tmp")
        os.makedirs(temp_dir, exist_ok=True)
        try: writable_temp = os.access(tempfile.gettempdir(), os.W_OK)
        except Exception: writable_temp = False
        if not writable_temp: os.environ["TMPDIR"] = temp_dir; tempfile.tempdir = temp_dir  # Android may not provide /tmp
        TARGET_DIR = target
        _ensure_path(target)
        return target
def _patch_asset_path():
    def asset_parent(self): return type(self)(os.path.dirname(str(self)))
    def asset_name(self): return os.path.basename(str(self).rstrip("/"))
    for module in tuple(sys.modules.values()):
        namespace = getattr(module, "__dict__", None)
        if not isinstance(namespace, dict): continue
        for value in tuple(namespace.values()):
            if not isinstance(value, type) or value.__name__ != "AssetPath": continue
            try:
                if "parent" not in value.__dict__: value.parent = property(asset_parent)
                if "name" not in value.__dict__: value.name = property(asset_name)
            except (AttributeError, TypeError): pass  # Immutable lookalikes are harmless
def _module_name(requirement):
    name = re.split(r"[\s\[<>=!~;@]", str(requirement).strip(), 1)[0]
    normalized = re.sub(r"[-_.]+", "-", name).lower()
    return _ALIASES.get(normalized, normalized.replace("-", "_"))
def _has_option(args, *names):
    for arg in args:
        text = str(arg)
        if any(text == name or text.startswith(name + "=") for name in names): return True
    return False
def _android_api():
    try:
        from java import jclass
        return int(jclass("android.os.Build$VERSION").SDK_INT)
    except Exception: pass
    try: return int(sys.getandroidapilevel())
    except Exception: return None
def _android_platform_args(extra_args):
    if _has_option(extra_args, "--platform"): return []
    api = _android_api()
    abi = _ABI.get(platform.machine().lower())
    if not api or not abi: return []
    if struct.calcsize("P") == 4: abi = {"arm64_v8a": "armeabi_v7a", "x86_64": "x86"}.get(abi, abi)
    minimum = 16 if abi in ("armeabi_v7a", "x86") else 21
    return [item for level in range(minimum, api + 1) for item in ("--platform", "android_%d_%s" % (level, abi))]
def _run_pip(args, target):
    output = io.StringIO()
    with _LOCK:
        _patch_asset_path()  # Chaquopy AssetPath lacks pathlib-like parent/name
        try: from pip._internal.cli.main import main as pip_main
        except Exception as exc: return 1, "import pip failed: %r; add install(\"pip\") to the Chaquopy Gradle pip block\n" % exc
        meta_path, path_hooks, sys_path = sys.meta_path[:], sys.path_hooks[:], sys.path[:]
        importer_cache = dict(sys.path_importer_cache)  # Required to remove pip import-guard residue
        warning_filters, showwarning = warnings.filters[:], warnings.showwarning
        root = logging.getLogger()
        root_handlers, root_level = root.handlers[:], root.level
        clear_handlers = getattr(logging.config, "_clearExistingHandlers", None)
        stdout, stderr = sys.stdout, sys.stderr
        routed_stdout, routed_stderr = _OutputRouter(stdout), _OutputRouter(stderr)
        thread_id = threading.get_ident()
        _OUTPUTS[thread_id] = output
        sys.stdout, sys.stderr = routed_stdout, routed_stderr
        if clear_handlers is not None: logging.config._clearExistingHandlers = lambda: None  # Do not close RPC log handlers
        code = 1
        try: code = pip_main(list(args))
        except SystemExit as exc: code = exc.code if isinstance(exc.code, int) else (0 if exc.code is None else 1)
        except BaseException: output.write(traceback.format_exc()); code = 1
        finally:
            sys.meta_path[:] = meta_path  # Remove pip >=24 meta-path import guard
            sys.path_hooks[:] = path_hooks  # Remove pip >=24 path hook import guard
            sys.path[:] = sys_path
            sys.path_importer_cache.clear()
            sys.path_importer_cache.update(importer_cache)
            sys.path_importer_cache.pop(os.path.abspath(target), None)
            if clear_handlers is not None: logging.config._clearExistingHandlers = clear_handlers
            for handler in root.handlers[:]:
                if handler not in root_handlers:
                    try: handler.close()
                    except Exception: pass
            root.handlers[:] = root_handlers
            root.setLevel(root_level)
            warnings.filters[:] = warning_filters
            warnings.showwarning = showwarning
            try: warnings._filters_mutated()
            except Exception: pass
            if sys.stdout is routed_stdout: sys.stdout = stdout
            if sys.stderr is routed_stderr: sys.stderr = stderr
            _OUTPUTS.pop(thread_id, None)
            importlib.invalidate_caches()
    try: return int(code or 0), output.getvalue()
    except (TypeError, ValueError): return 1, output.getvalue() + "\nInvalid pip exit code: %r\n" % (code,)
def _pip_args(package_name, target, extra_args, native):
    args = ["install", str(package_name), "--target", target, "--upgrade", "--no-compile", "--no-build-isolation", "--disable-pip-version-check", "--no-cache-dir", "--prefer-binary", "--no-input", "--no-color", "--progress-bar", "off", "--index-url", CHAQUO_INDEX, "--extra-index-url", EXTRA_INDEX] + list(extra_args)
    if native:
        if not _has_option(args, "--only-binary"): args.append("--only-binary=:all:")
        args += _android_platform_args(extra_args)  # --platform requires binary-only resolution
    return args
def _import_installed(module_name, target):
    with _LOCK:
        _ensure_path(target)
        loaded = sys.modules.get(module_name)
        if isinstance(loaded, types.ModuleType): return importlib.reload(loaded), "reloaded"  # Never delete sys.modules entries
        return importlib.import_module(module_name), "imported"
def install(package_name, extra_args=None, module_name=None, native=None):
    target = TARGET_DIR or configure()
    extra = [str(arg) for arg in (extra_args or [])]
    module_name = module_name or _module_name(package_name)
    forced_native = native is True or _has_option(extra, "--platform")
    modes = [True] if forced_native else ([False] if native is False else [False, True])
    runs = []
    for use_native in modes:
        label = "android-wheel" if use_native else "portable-or-source"
        code, log = _run_pip(_pip_args(package_name, target, extra, use_native), target)
        runs.append((label, code, log))
        if code == 0: break
    label, code, _ = runs[-1]
    result = {"ok": False, "package": package_name, "module": module_name, "action": None, "version": None, "code": code, "error": None, "log": "\n".join("[%s]\n%s" % (mode, log) for mode, _, log in runs)[-LOG_TAIL:], "attempts": [{"mode": mode, "code": exit_code} for mode, exit_code, _ in runs]}
    if code != 0:
        result["error"] = "pip exit code %s (%s)" % (code, label)
        return result
    try:
        module, action = _import_installed(module_name, target)
        result.update(ok=True, action=action, version=getattr(module, "__version__", None))
    except (Exception, SystemExit) as exc: result["error"] = "%s: %s" % (type(exc).__name__, exc)
    return result
def _executor():
    global _EXECUTOR
    with _EXEC_LOCK:
        if _EXECUTOR is None: _EXECUTOR = ThreadPoolExecutor(max_workers=1, thread_name_prefix="runtime_pip")
        return _EXECUTOR
def install_async(package_name, extra_args=None, module_name=None, native=None, callback=None):
    future = _executor().submit(install, package_name, extra_args, module_name, native)
    if callback: future.add_done_callback(lambda completed: callback(completed.result()))
    return future
def _package_items(packages):
    if isinstance(packages, str): return [(packages, None)]
    if isinstance(packages, dict): return list(packages.items())
    return [(item, None) if isinstance(item, str) else (item[0], item[1] if len(item) > 1 else None) for item in packages]
def install_missing(packages, extra_args=None, wait=True, native=None):
    items, extra = _package_items(packages), [str(arg) for arg in (extra_args or [])]
    def job():
        results = {}
        configure()
        for package_name, module_name in items:
            module_name = module_name or _module_name(package_name)
            try:
                module = importlib.import_module(module_name)
                results[package_name] = {"ok": True, "package": package_name, "module": module_name, "action": "present", "version": getattr(module, "__version__", None), "code": 0, "error": None, "log": "", "attempts": []}
            except (Exception, SystemExit): results[package_name] = install(package_name, extra, module_name, native)
        return results
    return job() if wait else _executor().submit(job)