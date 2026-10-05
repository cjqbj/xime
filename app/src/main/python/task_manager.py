"""高可靠后台任务框架（Python 侧）

架构：WorkManager 负责系统级调度（进程被杀、设备重启后仍能触发），
Kotlin PythonTaskWorker 收到触发后回调本模块 execute_task，
Python 负责任务注册表、状态持久化、执行统计与接力下一次调度。

为什么用 OneTimeWorkRequest 接力而不是 PeriodicWorkRequest：
PeriodicWorkRequest 最小周期 15 分钟，接力式可支持任意秒级间隔。

RPC 控制示例：
  r=task_manager.add_task("demo", "mqtt_stats", 60)
  r=task_manager.list_tasks()
  r=task_manager.pause_task("demo") / resume_task / remove_task / run_now("demo")
"""
import json
import os
import threading
import time
import traceback

try:
    from java import jclass
    _TaskScheduler = jclass("com.kingzcheung.xime.task.TaskScheduler")
except Exception:  # pragma: no cover - 非 Android 环境（PC 侧单测）
    _TaskScheduler = None

_STATE_PATH = None
_LOCK = threading.RLock()
_STATE = {"tasks": {}}

# ---- 任务函数注册表：name -> callable() -> object（返回值会被 str 记入 last_result） ----
JOB_FUNCS = {}


def register_func(name, func):
    """注册可被任务引用的 Python 函数。模块加载时内置注册基础任务。"""
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


def init(files_dir):
    """app.py start() 内调用：加载持久化状态，并为启用中的任务接力恢复调度。"""
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
        restored = 0
        for task_id, t in _STATE["tasks"].items():
            if t.get("enabled", True):
                _schedule(task_id, t.get("interval", 60))
                restored += 1
    print(f"[task_manager] init done, {len(_STATE['tasks'])} tasks, {restored} rescheduled")
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


def _schedule(task_id, delay_seconds):
    if _TaskScheduler is None:
        print("[task_manager] TaskScheduler 不可用（非 Android 环境）")
        return False
    _TaskScheduler.schedule(task_id, int(max(0, delay_seconds)))
    return True


def add_task(task_id, func_name, interval, enabled=True):
    """新增/覆盖任务。interval 单位秒；任务执行后自动接力下一次。"""
    if func_name not in JOB_FUNCS:
        return {"ok": False, "error": f"unknown func: {func_name}", "funcs": sorted(JOB_FUNCS)}
    with _LOCK:
        _STATE["tasks"][task_id] = {
            "func": func_name,
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
    print(f"[task_manager] add {task_id} func={func_name} interval={interval}s enabled={enabled}")
    return {"ok": True, "id": task_id}


def remove_task(task_id):
    with _LOCK:
        existed = _STATE["tasks"].pop(task_id, None) is not None
        _save()
    if _TaskScheduler is not None:
        _TaskScheduler.cancel(task_id)
    return {"ok": existed}


def pause_task(task_id):
    with _LOCK:
        t = _STATE["tasks"].get(task_id)
        if not t:
            return {"ok": False, "error": "not found"}
        t["enabled"] = False
        _save()
    if _TaskScheduler is not None:
        _TaskScheduler.cancel(task_id)
    return {"ok": True}


def resume_task(task_id):
    with _LOCK:
        t = _STATE["tasks"].get(task_id)
        if not t:
            return {"ok": False, "error": "not found"}
        t["enabled"] = True
        _save()
        _schedule(task_id, t.get("interval", 60))
    return {"ok": True}


def run_now(task_id):
    with _LOCK:
        if task_id not in _STATE["tasks"]:
            return {"ok": False, "error": "not found"}
    _schedule(task_id, 0)
    return {"ok": True}


def list_tasks():
    with _LOCK:
        return json.loads(json.dumps(_STATE["tasks"], ensure_ascii=False))


def execute_task(task_id):
    """PythonTaskWorker 回调入口。执行函数、记账、接力下一次调度。

    接力放在 finally：即使任务抛异常也重新排队，保证链条不断；
    唯一断链途径是显式 pause/remove（会 cancel WorkManager 工作）。
    """
    with _LOCK:
        t = _STATE["tasks"].get(task_id)
    if t is None:
        print(f"[task_manager] execute {task_id}: 未注册，忽略")
        return "not_found"
    func = JOB_FUNCS.get(t.get("func"))
    if func is None:
        print(f"[task_manager] execute {task_id}: 函数 {t.get('func')} 未注册")
        return "func_missing"
    result, error = None, None
    try:
        result = func()
    except Exception:
        error = traceback.format_exc(limit=3)
        print(f"[task_manager] task {task_id} 异常:\n{error}")
    finally:
        with _LOCK:
            t["run_count"] = int(t.get("run_count", 0)) + 1
            t["last_run"] = time.strftime("%Y-%m-%d %H:%M:%S")
            t["last_result"] = (str(result)[:200] if error is None else "ERROR: " + error.splitlines()[-1])
            still_enabled = t.get("enabled", True)
            interval = t.get("interval", 60)
            _save()
        if still_enabled:
            _schedule(task_id, interval)
    return "ok" if error is None else "error"
