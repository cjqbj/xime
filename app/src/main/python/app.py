import os
import sys
import traceback
import threading
import json

MAX_LOG_BYTES = 100 * 1024
MAX_LOG_LINES = 1000
RPC_CONFIG_PATH = "/sdcard/Alarms/xime_rpc.json"

try:
    from java import jclass
    RpcUiController = jclass("com.kingzcheung.xime.util.RpcUiController")
except Exception:  # pragma: no cover - chaquopy/python-only test environments may not expose Java.
    RpcUiController = None

def load_rpc_config():
    defaults = {
        "http_enabled": True,
        "http_port": 1144,
        "http_host": "0.0.0.0",
        "http_key": "",
        "mqtt_enabled": False,
        #"mqtt_brokers": "broker.emqx.io:1883",
        "mqtt_request_topic": "sys/device/request",
        "mqtt_reply_topic": "sys/device/response",
        "mqtt_pub_key": "",
    }
    try:
        with open(RPC_CONFIG_PATH, "r", encoding="utf-8") as config_file:
            values = json.load(config_file)
        if isinstance(values, dict):
            defaults.update(values)
    except (OSError, ValueError):
        pass
    return defaults


class LimitedLogFile:
    def __init__(self, path, max_bytes=MAX_LOG_BYTES):
        self.path = path
        self.max_bytes = max_bytes
        self.lock = threading.Lock()
        self.file = open(path, "a+b", buffering=0)

    def write(self, value):
        data = value.encode("utf-8") if isinstance(value, str) else value
        with self.lock:
            self.file.seek(0, 2)
            if self.file.tell() + len(data) > self.max_bytes:
                keep = self.max_bytes // 2
                self.file.seek(-min(keep, self.file.tell()), 2)
                tail = self.file.read()
                self.file.seek(0)
                self.file.truncate()
                self.file.write(tail)
            self.file.seek(0, 2)
            self.file.write(data)

    def flush(self):
        with self.lock:
            self.file.flush()


class Tee:
    def __init__(self, stream, sinks):
        self.stream = stream
        if sinks is None:
            self.sinks = []
        elif isinstance(sinks, (list, tuple)):
            self.sinks = [sink for sink in sinks if sink is not None]
        else:
            self.sinks = [sinks]

    def write(self, value):
        self.stream.write(value)
        for sink in self.sinks:
            sink.write(value)
            sink.flush()

    def flush(self):
        self.stream.flush()
        for sink in self.sinks:
            sink.flush()


class MemoryLogProxy:
    def __init__(self):
        self.buffer = []

    def write(self, value):
        if not value:
            return
        text = value if isinstance(value, str) else value.decode("utf-8", errors="replace")
        if RpcUiController is not None:
            try:
                RpcUiController.appendLog(text)
            except Exception:
                pass
        self.buffer.append(text)
        total_chars = sum(len(part) for part in self.buffer)
        if total_chars > MAX_LOG_BYTES:
            trimmed = ''.join(self.buffer)
            trimmed = trimmed[-MAX_LOG_BYTES:]
            self.buffer = trimmed.splitlines(keepends=True)
        if len(self.buffer) > MAX_LOG_LINES:
            self.buffer = self.buffer[-MAX_LOG_LINES:]

    def flush(self):
        pass


def start(log_path, files_dir=None):
    global mqtt_server,http_server,gms
    memory_sink = MemoryLogProxy()
    file_sink = None
    if log_path:
        os.makedirs(os.path.dirname(log_path) or ".", exist_ok=True)
        file_sink = LimitedLogFile(log_path)

    sinks = [memory_sink]
    if file_sink is not None:
        sinks.append(file_sink)

    sys.stdout = Tee(sys.__stdout__, sinks)
    sys.stderr = Tee(sys.__stderr__, sinks)
    print("[PYTHON] Chaquopy RPC bootstrap started")
    try:
        import runtime_pip
        runtime_pip.configure(files_dir)
        # Import after stdout/stderr redirection so logging.basicConfig in the
        # MQTT and HTTP modules writes into the same log shown by the settings UI.
        import logging
        submodule_root = os.path.join(os.path.dirname(__file__), "multi_mqtt")
        if submodule_root not in sys.path:
            sys.path.insert(0, submodule_root)
        import server_http,server_mqtt
        logging.basicConfig(
            level=logging.INFO,
            format='%(asctime)s - %(levelname)s - %(message)s',
            force=True,
        )
        config = load_rpc_config()
        mqtt_server =  None
        if config.get('http_enabled', True):
            http_server= server_http.start_rpc_server(
                port=int(config.get('http_port', 1144)),
                ip=str(config.get('http_host', '0.0.0.0')),
                key=str(config.get('http_key', '')),
                globals=globals(),
                locals=locals(),
            )

        # mqtt_server = server_mqtt.start(config,globals=globals()) if config.get("mqtt_enabled", False) else None
        request_topic = str(config.get("mqtt_request_topic") or server_mqtt.REQUEST_TOPIC)
        reply_topic = str(config.get("mqtt_reply_topic") or server_mqtt.DEFAULT_REPLY_TOPIC)

        # 直接把原始值交给 MultiMQTTManager，由它统一走 get_standard_public_pem_bytes
        # 手机端把 paho 重连退避封顶从默认 3600s 降到 120s：长 Doze 期间所有
        # socket 被切后指数退避很快到顶，网络恢复后仍要等最长 1 小时才重连；
        # 另有网络恢复看门狗（TCP 可达即重建卡死 client）兜底，正常恢复 ≤2 分钟。
        mqtt_server=server_mqtt.MQTTServer(
            server_public_key_bytes=config.get("mqtt_pub_key"),
            request_topic=request_topic,
            reply_topic=reply_topic,
            globals=globals(),
            max_reconnect_delay=int(config.get("mqtt_max_reconnect_delay", 120)),
        )
        gms = mqtt_server  # 别名：与 multi_mqtt 约定一致，RPC/设置页按钮统一用 gms.mqtt_net.stats.get_report()
        mqtt_server.start(block=False)

        import task_manager
        task_manager.init(files_dir or ".")

        print(f"[app.py] {config} loaded, HTTP={http_server} MQTT={mqtt_server} ")
        return True
    except Exception:
        traceback.print_exc()
        return False


def mqtt_stats_report():
    """设置页按钮入口：执行 gms.mqtt_net.stats.get_report() 并把报告打印进运行日志。"""
    server = globals().get("gms")
    if server is None:
        print("[app.py] MQTT 未启动，暂无连接质量统计")
        return False
    print(gms.mqtt_net.stats.get_report())
    return True
