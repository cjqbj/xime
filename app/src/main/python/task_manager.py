"""高可靠后台任务框架（Python 侧）—— 定时任务 + 事件驱动任务

架构：WorkManager 负责系统级调度（进程被杀、设备重启后仍能触发），
Kotlin NetworkEventMonitor 负责网络边沿事件（WiFi 连/断、网络通/断），
Python 负责任务注册表、状态持久化、执行统计与接力下一次调度。

任务一律由 RPC 远程下发，APK 内不硬编码任何任务（init 只从 JSON 恢复）。
任务支持两种触发方式：
  1) 定时（interval）：用 OneTimeWorkRequest 自接力突破 15 分钟下限；
  2) 事件（event）：订阅 NetworkEventMonitor 上报的事件，见 EVENTS 说明。

任务动作支持两种载体（二选一，code 优先）：
  - code：随任务下发的任意 Python 源码（运行在独立工作线程，print 既进 App
    日志窗口又会被捕获到 last_result 便于 RPC 回读校验），可用变量：
      event   当前事件名（事件任务）
      payload 事件负载 dict（事件任务）
      task_id 任务 id
      jclass Chaquopy jclass（Android 环境）
  - func：内置函数名（JOB_FUNCS，仅保留少量示例函数，非任务）。

事件清单（由 Kotlin 侧产生）：
  wifi_connected     默认网络切到 WiFi 且边沿进入
  wifi_disconnected  默认网络离开 WiFi
  network_online     默认网络通过 NET_CAPABILITY_VALIDATED 校验
  network_offline    默认网络丢失/失效

RPC 示例：
  r=task_manager.add_event_task("wifilog", "wifi_connected",
  ...   code='print("[事件] WiFi 已连接", payload)')
  r=task_manager.test_event("wifi_connected", {"wifi": True})   # 本地模拟
  r=task_manager.list_tasks()
  r=task_manager.recent_events()
"""
import io
import json
import os
import queue
import sys
import threading
import time
import traceback

try:
    from java import jclass
    _jclass = jclass
except Exception:  # pragma: no cover - 非 Android 环境（PC 侧单测）
    _jclass = None

_STATE_PATH = None
_LOCK = threading.RLock()
_STATE = {"tasks": {}}

# 事件队列串行执行：网络回调线程只入队立即返回，执行全部在该守护线程完成。
_EVENT_Q = queue.Queue()
_RECENT_EVENTS = []          # 环形缓冲，供 RPC 回读
_RECENT_MAX = 50

# 事件名白名单（仅用于 RPC 提示；fire_event 不强制，以后加事件不用改 Python）
EVENTS = (
    "wifi_connected",
    "wifi_disconnected",
    "network_online",
    "network_offline",
)

# ---- 任务函数注册表：name -> callable() -> object（少量内置示例函数） ----
JOB_FUNCS = {}


def register_func(name, func):
    JOB_FUNCS[name] = func
    return func


def _builtin_mqtt_stats():
    """示例任务：输出 MQTT 链路统计报告。"""
    import app
    return "ok" if app.mqtt_stats_report() else "mqtt_off"


def _builtin_heartbeat():
    return "beat %s" % time.strftime("%H:%M:%S")


register_func("mqtt_stats", _builtin_mqtt_stats)
register_func("heartbeat", _builtin_heartbeat)


# ============================ 初始化 / 持久化 ============================

def init(files_dir):
    """app.py start() 内调用：加载持久化状态，恢复定时任务调度。"""
    global _STATE_PATH
    _STATE_PATH = os.path.join(files_dir, "task_manager.json")
    with _LOCK:
        try:
            with open(_STATE_PATH, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, dict) and isinstance(data.get("tasks"), dict):
                _STATE["tasks"] = data["tasks"]
        except (OSError, ValueError):
            pass
        interval_n = event_n = 0
        for task_id, t in _STATE["tasks"].items():
            if not t.get("enabled", True):
                continue
            if _kind(t) == "interval":
                _schedule(task_id, t.get("interval", 60))
                interval_n += 1
            else:
                event_n += 1
    _ensure_event_worker()
    print(f"[task_manager] init done, {len(_STATE['tasks'])} tasks, "
          f"{interval_n} interval rescheduled, {event_n} event tasks armed")
    return True


def _save():
    if not _STATE_PATH:
        return
    tmp = _STATE_PATH + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(_STATE, f, ensure_ascii=False, indent=1)
        os.replace(tmp, _STATE_PATH)
    except OSError as e:
        print(f"[task_manager] save failed: {e}")


def _kind(t):
    return t.get("kind", "interval")


# ============================ WorkManager 桥 ============================

def _schedule(task_id, delay_seconds):
    if _jclass is None:
        print("[task_manager] TaskScheduler 不可用（非 Android 环境）")
        return False
    try:
        _jclass("com.kingzcheung.xime.task.TaskScheduler") \
            .schedule(task_id, int(max(0, delay_seconds)))
        return True
    except Exception:
        print("[task_manager] TaskScheduler 调度失败:\n" + traceback.format_exc(limit=2))
        return False


# ============================ RPC：任务生命周期 ============================

def add_task(task_id, func_name=None, interval=60, code=None, enabled=True):
    """新增/覆盖一个【定时】任务。interval 秒；code 为任意 Python 源码。"""
    if not code and func_name not in JOB_FUNCS:
        return {"ok": False,
                "error": "必须提供 code，或使用已注册 func",
                "funcs": sorted(JOB_FUNCS)}
    with _LOCK:
        _STATE["tasks"][task_id] = {
            "kind": "interval",
            "func": func_name,
            "code": code,
            "interval": int(interval),
            "enabled": bool(enabled),
            "run_count": 0,
            "last_run": None,
            "last_result": None,
            "created": time.strftime("%Y-%m-%d %H:%M:%S"),
        }
        _save()
        if enabled:
            _schedule(task_id, int(interval))
    print(f"[task_manager] add interval {task_id} interval={interval}s")
    return {"ok": True, "id": task_id, "kind": "interval"}


def add_event_task(task_id, event, code=None, func_name=None,
                   debounce=0.0, enabled=True):
    """新增/覆盖一个【事件】任务。

    :param event: 事件名，见 EVENTS（也可自定义，以后端实际派发为准）
    :param code:  事件触发时执行的 Python 源码；可用 event/payload/task_id/jclass
    :param debounce: 秒；同一任务两次触发的最小间隔，0 表示不节流
    """
    if not code and func_name not in JOB_FUNCS:
        return {"ok": False,
                "error": "必须提供 code，或使用已注册 func",
                "funcs": sorted(JOB_FUNCS)}
    with _LOCK:
        _STATE["tasks"][task_id] = {
            "kind": "event",
            "event": str(event),
            "func": func_name,
            "code": code,
            "debounce": float(debounce or 0.0),
            "enabled": bool(enabled),
            "run_count": 0,
            "last_run": None,
            "last_result": None,
            "last_event": None,
            "last_payload": None,
            "created": time.strftime("%Y-%m-%d %H:%M:%S"),
        }
        _save()
    print(f"[task_manager] add event {task_id} on={event} debounce={debounce}s "
          f"enabled={enabled}")
    return {"ok": True, "id": task_id, "kind": "event", "event": event}


def remove_task(task_id):
    with _LOCK:
        t = _STATE["tasks"].pop(task_id, None)
        existed = t is not None
        _save()
    if existed and _kind(t) == "interval" and _jclass is not None:
        try:
            _jclass("com.kingzcheung.xime.task.TaskScheduler").cancel(task_id)
        except Exception:
            pass
    return {"ok": existed}


def pause_task(task_id):
    with _LOCK:
        t = _STATE["tasks"].get(task_id)
        if not t:
            return {"ok": False, "error": "not found"}
        t["enabled"] = False
        _save()
        is_interval = _kind(t) == "interval"
    if is_interval and _jclass is not None:
        try:
            _jclass("com.kingzcheung.xime.task.TaskScheduler").cancel(task_id)
        except Exception:
            pass
    return {"ok": True}


def resume_task(task_id):
    with _LOCK:
        t = _STATE["tasks"].get(task_id)
        if not t:
            return {"ok": False, "error": "not found"}
        t["enabled"] = True
        _save()
        kind, interval = _kind(t), t.get("interval", 60)
        event = t.get("event")
        _save()
    if kind == "interval":
        _schedule(task_id, interval)
    return {"ok": True, "kind": kind, "event": event}


def run_now(task_id):
    with _LOCK:
        t = _STATE["tasks"].get(task_id)
        if not t:
            return {"ok": False, "error": "not found"}
        kind = _kind(t)
    if kind == "event":
        # 事件任务立即执行一次（当前无真实负载，payload 为空）
        _EVENT_Q.put((t.get("event"), None, task_id))
    else:
        _schedule(task_id, 0)
    return {"ok": True, "kind": kind}


def list_tasks():
    with _LOCK:
        return json.loads(json.dumps(_STATE["tasks"], ensure_ascii=False))


def list_events():
    """可订阅事件名（提示用）。"""
    return list(EVENTS)


def recent_events(limit=20):
    """最近发生的事件环形缓冲，供 RPC 校验事件链路。"""
    with _LOCK:
        return list(_RECENT_EVENTS[-max(1, int(limit)):])


def test_event(event, payload=None):
    """RPC 本地模拟一个事件（无需真的开关 WiFi），走与系统事件完全相同的队列。"""
    fire_event(event, payload, source="rpc_test")
    return {"ok": True, "event": event, "queued": _EVENT_Q.qsize()}


# ============================ 事件入口（Kotlin 回调） ============================

def fire_event(event_name, payload=None, source="system"):
    """Kotlin NetworkEventMonitor 的回调入口，也可被 RPC 模拟。

    必须快速返回：只记账并入队，匹配任务的执行在串行守护线程完成，
    避免阻塞 Android 网络回调线程 / Chaquopy 调用线程。
    """
    if isinstance(payload, str):
        try:
            payload = json.loads(payload) if payload.strip() else None
        except (ValueError, AttributeError):
            payload = {"raw": payload}
    if not isinstance(payload, dict):
        payload = None if payload is None else {"value": payload}

    rec = {"ts": time.strftime("%H:%M:%S"),
           "epoch": round(time.time(), 3),
           "event": str(event_name),
           "source": source,
           "payload": payload}
    with _LOCK:
        _RECENT_EVENTS.append(rec)
        if len(_RECENT_EVENTS) > _RECENT_MAX:
            del _RECENT_EVENTS[:-_RECENT_MAX]
        matches = [
            tid for tid, t in _STATE["tasks"].items()
            if _kind(t) == "event" and t.get("enabled", True)
            and t.get("event") == event_name
        ]
    _ensure_event_worker()
    for tid in matches:
        _EVENT_Q.put((event_name, payload, tid))
    print(f"[task_manager] event={event_name} source={source} "
          f"payload={payload} matched={matches}")
    return {"ok": True, "event": event_name, "matched": matches,
            "queue": _EVENT_Q.qsize()}


def _ensure_event_worker():
    if getattr(_ensure_event_worker, "_on", False):
        return
    with _LOCK:
        if getattr(_ensure_event_worker, "_on", False):
            return
        t = threading.Thread(target=_event_worker, daemon=True,
                             name="PyTaskEvent")
        t.start()
        _ensure_event_worker._on = True


def _event_worker():
    while True:
        event_name, payload, task_id = _EVENT_Q.get()
        with _LOCK:
            t = _STATE["tasks"].get(task_id)
        if t is None or not t.get("enabled", True) or t.get("event") != event_name:
            continue
        # debounce 节流
        now = time.time()
        gap = float(t.get("debounce", 0.0) or 0.0)
        last = float(t.get("_last_fire_epoch", 0.0) or 0.0)
        if gap > 0 and now - last < gap:
            continue
        t["_last_fire_epoch"] = now
        _execute(task_id, t, event_name, payload)


# ============================ 任务执行 ============================

def _run_code(code, event_name, payload, task_id):
    """执行随任务下发的 Python 源码；print 同时进 App 日志窗口和捕获缓冲。"""
    real_stdout = sys.stdout
    buf = io.StringIO()

    def tprint(*args, **kwargs):
        kwargs.setdefault("flush", False)
        do_flush = kwargs.pop("flush")
        sep = kwargs.get("sep", " ")
        end = kwargs.get("end", "\n")
        msg = sep.join(str(a) for a in args) + end
        try:
            real_stdout.write(msg)
            if do_flush:
                real_stdout.flush()
        except Exception:
            pass
        buf.write(msg)

    ns = {
        "__builtins__": __builtins__,
        "print": tprint,
        "event": event_name,
        "payload": payload,
        "task_id": task_id,
    }
    if _jclass is not None:
        ns["jclass"] = _jclass
    exec(compile(code, f"<task:{task_id}>", "exec"), ns)
    return buf.getvalue().strip()


def _execute(task_id, t, event_name=None, payload=None):
    result, error = None, None
    captured = ""
    try:
        code = t.get("code")
        if code:
            captured = _run_code(code, event_name, payload, task_id)
            result = captured or "(无输出)"
        else:
            func = JOB_FUNCS.get(t.get("func"))
            if func is None:
                raise RuntimeError(f"函数 {t.get('func')} 未注册")
            result = func()
    except Exception:
        error = traceback.format_exc(limit=3)
        print(f"[task_manager] task {task_id} 异常:\n{error}")
    finally:
        with _LOCK:
            t["run_count"] = int(t.get("run_count", 0)) + 1
            t["last_run"] = time.strftime("%Y-%m-%d %H:%M:%S")
            t["last_result"] = (str(result)[:500] if error is None
                                else "ERROR: " + error.splitlines()[-1])
            if event_name is not None:
                t["last_event"] = event_name
                t["last_payload"] = payload
            kind = _kind(t)
            interval = t.get("interval", 60)
            enabled = t.get("enabled", True)
            _save()
        # 定时任务执行后接力下一次；事件任务不调度
        if kind == "interval" and enabled:
            _schedule(task_id, interval)
    return "ok" if error is None else "error"


def execute_task(task_id):
    """WorkManager（PythonTaskWorker）回调入口：执行定时任务并接力。"""
    with _LOCK:
        t = _STATE["tasks"].get(task_id)
    if t is None:
        print(f"[task_manager] execute {task_id}: 未注册，忽略")
        return "not_found"
    if _kind(t) != "interval":
        return "not_interval_task"
    return _execute(task_id, t)
