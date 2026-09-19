package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.api.AsrPluginListener
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.lua.ws.WsHostApi
import com.kingzcheung.xime.plugin.core.lua.ws.WsHostListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaValue
import java.io.File

/**
 * 验证 funasr-asr-lua：全部 ASR 逻辑（状态机/prebuffer/协议）在 Lua，
 * 宿主只提供通用 WebSocket 原语（mock 验证 Lua 对 host.ws 的使用）。
 */
class LuaAsrPluginTest {

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class MockWsHostApi : WsHostApi {
        val sentTexts = mutableListOf<String>()
        val sentBinaries = mutableListOf<ByteArray>()
        var connectedUrl: String? = null
        var connectedHeaders: Map<String, String> = emptyMap()
        var hostListener: WsHostListener? = null

        override fun connect(url: String, headers: Map<String, String>, listener: WsHostListener): Boolean {
            connectedUrl = url
            connectedHeaders = headers
            hostListener = listener
            return true
        }
        override fun sendText(message: String) { sentTexts.add(message) }
        override fun sendBinary(data: ByteArray) { sentBinaries.add(data) }
        override fun close() {}
        override fun getState(): Int = 2
        override fun lastError(): String? = null
    }

    private class ResultCollector : AsrPluginListener {
        var finalText = ""
        var partialText = ""
        var error: String? = null
        override fun onFinal(text: String) { finalText = text }
        override fun onPartial(text: String) { partialText = text }
        override fun onError(message: String) { error = message }
    }

    @Test
    fun `funasr lua plugin owns all asr logic`() {
        val dir = File("../plugins/funasr-asr")
        assertTrue("插件目录应存在: ${dir.absolutePath}", dir.exists())
        val mock = MockWsHostApi()
        val store = InMemoryConfigStore()
        val runtime = LuaScriptRuntime(
            "com.kingzcheung.xime.plugin.funasr_asr",
            dir,
            "main.lua",
            store,
            wsHostApi = mock
        )
        assertTrue("main.lua 应能加载", runtime.load())

        // 元信息
        assertEquals("funasr", runtime.call("getProviderId").tojstring())
        assertEquals("阿里百炼 FunAsr", runtime.call("getDisplayName").tojstring())
        val icon = LuaScriptRuntime.tableToMap(runtime.call("getIcon"))
        assertEquals("icon.png", icon["assetName"]?.tojstring())
        assertTrue("未配置时不就绪", !runtime.call("isConfigured").toboolean())

        val collector = ResultCollector()
        runtime.asrResultCallback = collector

        // 未配置 apiKey → start 失败并 emitError
        assertTrue("start 应失败（未配置）", !runtime.call("start").toboolean())
        assertEquals("未配置 API Key，请在插件设置中填写", collector.error)

        // 配置后 start → Lua 用 host.ws 发起连接
        store.set("apiKey", "test-key-123")
        assertTrue("start 应成功", runtime.call("start").toboolean())
        assertTrue("连接地址应为 dashscope 白名单域名", mock.connectedUrl?.contains("dashscope.aliyuncs.com") == true)
        assertEquals("Bearer test-key-123", mock.connectedHeaders["Authorization"])

        // onOpen → Lua 组装 run-task（官方格式：task_group/task/function/model 在 payload）
        mock.hostListener?.onOpen()
        assertEquals("应发送 run-task", 1, mock.sentTexts.size)
        val runTask = mock.sentTexts[0]
        assertTrue("run-task 含 action", runTask.contains("\"action\":\"run-task\""))
        assertTrue("payload 含 task_group", runTask.contains("\"task_group\":\"audio\""))
        assertTrue("payload 含 model", runTask.contains("\"model\":\"qwen-audio-3.0-asr-flash-streaming\""))
        assertTrue("payload 含 function", runTask.contains("\"function\":\"recognition\""))
        assertTrue("含 sample_rate", runTask.contains("\"sample_rate\":16000"))

        // 音频提交给 Lua：task-started 前缓冲（不经宿主直接发）
        runtime.call("processAudioChunk", LuaString.valueOf(byteArrayOf(1, 2, 3, 4)))
        assertTrue("task-started 前不应直发音频", mock.sentBinaries.isEmpty())

        // task-started → Lua 冲刷 prebuffer
        mock.hostListener?.onMessage(
            """{"header":{"event":"task-started","task_id":"t1"},"payload":{}}"""
        )
        assertEquals("task-started 后冲刷缓冲音频", 1, mock.sentBinaries.size)

        // 音频直发（audioReady）
        runtime.call("processAudioChunk", LuaString.valueOf(byteArrayOf(5, 6, 7, 8)))
        assertEquals("audioReady 后直发", 2, mock.sentBinaries.size)

        // result-generated（partial / final）→ Lua 解析并 emit
        mock.hostListener?.onMessage(
            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"text":"你好","sentence_end":false}}}}"""
        )
        assertEquals("partial 结果", "你好", collector.partialText)

        mock.hostListener?.onMessage(
            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"text":"你好世界","sentence_end":true}}}}"""
        )
        assertEquals("final 结果", "你好世界", collector.finalText)

        // heartbeat 消息应被忽略
        mock.hostListener?.onMessage(
            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"heartbeat":true}}}}"""
        )
        assertEquals("heartbeat 不产生结果", "你好世界", collector.finalText)

        // task-failed → Lua 解析并 emitError
        mock.hostListener?.onMessage(
            """{"header":{"event":"task-failed","error_code":"InvalidParameter","error_message":"bad param"},"payload":{}}"""
        )
        assertTrue("task-failed 应上报错误", (collector.error ?: "").contains("InvalidParameter"))

        // stop → Lua 发 finish-task
        runtime.call("stop")
        assertTrue("stop 发送 finish-task", mock.sentTexts.any { it.contains("\"action\":\"finish-task\"") })
    }
}
