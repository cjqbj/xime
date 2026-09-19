package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.api.AsrPluginListener
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.lua.ws.WsHostApi
import com.kingzcheung.xime.plugin.core.lua.ws.WsHostListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.luaj.vm2.LuaString
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * 验证 volc-asr：火山 bigmodel_async 二进制协议（gzip + 帧头/序列号）全部在 Lua 承载，
 * 宿主仅提供 host.ws（含 onBinary）与 host.zlib / host.bin 原语。
 */
class LuaVolcAsrPluginTest {

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class MockWsHostApi : WsHostApi {
        val sentBinaries = mutableListOf<ByteArray>()
        var connectedUrl: String? = null
        var connectedHeaders: Map<String, String> = emptyMap()
        var hostListener: WsHostListener? = null
        var closed = false

        override fun connect(url: String, headers: Map<String, String>, listener: WsHostListener): Boolean {
            connectedUrl = url
            connectedHeaders = headers
            hostListener = listener
            return true
        }
        override fun sendText(message: String) {}
        override fun sendBinary(data: ByteArray) { sentBinaries.add(data) }
        override fun close() { closed = true }
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

    private fun u32be(n: Int): ByteArray = byteArrayOf(
        (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()
    )

    private fun header(msgType: Int, flags: Int, ser: Int, comp: Int): ByteArray =
        byteArrayOf(
            0x11,
            ((msgType shl 4) or flags).toByte(),
            ((ser shl 4) or comp).toByte(),
            0
        )

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    /** 构造服务端 FULL_SERVER_RESP 帧（flags 控制是否带 seq/event/末包标志）。 */
    private fun serverResp(json: String, flags: Int): ByteArray {
        val payload = gzip(json.toByteArray(Charsets.UTF_8))
        var body = ByteArray(0)
        if (flags and 0x1 != 0) body += u32be(0) // sequence
        if (flags and 0x4 != 0) body += u32be(0) // event
        body += u32be(payload.size)
        body += payload
        return header(0x9, flags, 0x1, 0x1) + body
    }

    /** 构造服务端 ERROR 帧。 */
    private fun serverError(code: Int, msg: String): ByteArray {
        val m = msg.toByteArray(Charsets.UTF_8)
        val body = u32be(code) + u32be(m.size) + m
        return header(0xF, 0, 0, 0) + body
    }

    @Test
    fun `volc lua plugin owns bigmodel_async binary protocol`() {
        val dir = File("../plugins/volc-asr")
        assertTrue("插件目录应存在: ${dir.absolutePath}", dir.exists())
        val mock = MockWsHostApi()
        val store = InMemoryConfigStore()
        val runtime = LuaScriptRuntime(
            "com.kingzcheung.xime.plugin.volc_asr",
            dir,
            "main.lua",
            store,
            wsHostApi = mock
        )
        assertTrue("main.lua 应能加载", runtime.load())

        // 元信息
        assertEquals("volc", runtime.call("getProviderId").tojstring())
        assertEquals("火山引擎流式语音识别", runtime.call("getDisplayName").tojstring())
        assertTrue("未配置时不就绪", !runtime.call("isConfigured").toboolean())

        // 设置 schema 非空（插件中心渲染表单的前提）
        val schema = LuaScriptRuntime.tableToList(runtime.call("getSettingsSchema"))
        assertTrue("getSettingsSchema 应非空", schema.isNotEmpty())
        val schemaFields = schema.map { LuaScriptRuntime.tableToMap(it) }
        val schemaKeys = schemaFields.mapNotNull { it["key"]?.tojstring() }
        assertTrue("schema 应含 apiKey", schemaKeys.contains("apiKey"))
        assertTrue("schema 应含 resourceId", schemaKeys.contains("resourceId"))
        val apiKeyField = schemaFields.first { it["key"]?.tojstring() == "apiKey" }
        val appKeyField = schemaFields.first { it["key"]?.tojstring() == "appKey" }
        assertNull("apiKey 未声明 required（默认必填）", apiKeyField["required"])
        assertFalse("appKey 旧鉴权应为可选", appKeyField["required"]?.toboolean() == true)

        val collector = ResultCollector()
        runtime.asrResultCallback = collector

        // 未配置 → start 失败并 emitError
        assertTrue("start 应失败（未配置）", !runtime.call("start").toboolean())
        assertEquals("未配置 API Key，请在插件设置中填写", collector.error)

        // 配置后 start → 连接 openspeech 域名，带鉴权头
        store.set("apiKey", "test-api-key")
        assertTrue("start 应成功", runtime.call("start").toboolean())
        assertTrue("连接地址应为火山域名", mock.connectedUrl?.contains("openspeech.bytedance.com") == true)
        assertEquals("test-api-key", mock.connectedHeaders["X-Api-Key"])
        assertEquals("volc.seedasr.sauc.duration", mock.connectedHeaders["X-Api-Resource-Id"])
        assertTrue("应带 X-Api-Request-Id", mock.connectedHeaders["X-Api-Request-Id"].isNullOrEmpty().not())
        assertEquals("应带 X-Api-Sequence=-1", "-1", mock.connectedHeaders["X-Api-Sequence"])
        assertTrue("应带 X-Api-Connect-Id", mock.connectedHeaders["X-Api-Connect-Id"].isNullOrEmpty().not())

        // onOpen → Lua 组装 full client request 帧（0x1, flags=POS_SEQUENCE, json, gzip）
        mock.hostListener?.onOpen()
        assertEquals("onOpen 应发送 full client request", 1, mock.sentBinaries.size)
        val full = mock.sentBinaries[0]
        assertEquals("帧头[0] 协议版本+头长", 0x11, full[0].toInt() and 0xFF)
        assertEquals("帧头[1] 消息类型 0x1+POS_SEQUENCE", (0x1 shl 4) or 0x1, full[1].toInt() and 0xFF)
        assertEquals("帧头[2] json+gzip", (0x1 shl 4) or 0x1, full[2].toInt() and 0xFF)
        assertTrue("full request 应带 seq", readInt32(full, 4) >= 1)
        val fullSize = readU32(full, 8)
        assertEquals("payload 长度字段正确", full.size - 12, fullSize)
        val fullJson = String(gunzip(full.copyOfRange(12, 12 + fullSize)), Charsets.UTF_8)
        assertTrue("full request 含 model_name", fullJson.contains("\"model_name\":\"bigmodel\""))
        assertTrue("full request 含音频格式", fullJson.contains("\"rate\":16000"))

        // audioReady 后音频直发（0x2, POS_SEQUENCE, raw, gzip）
        runtime.call("processAudioChunk", LuaString.valueOf(byteArrayOf(1, 2, 3, 4)))
        assertEquals("audioReady 后直发音频帧", 2, mock.sentBinaries.size)
        val audio = mock.sentBinaries[1]
        assertEquals("音频帧消息类型 0x2", (0x2 shl 4) or 0x1, audio[1].toInt() and 0xFF)
        assertTrue(
            "音频帧 payload 应为 gzip",
            gzip(byteArrayOf(1, 2, 3, 4)).contentEquals(audio.copyOfRange(12, audio.size))
        )

        // partial 结果（flags=0x1 带 seq，无末包标志）
        mock.hostListener?.onBinary(serverResp("""{"result":{"text":"你好"}}""", flags = 0x1))
        assertEquals("partial 结果", "你好", collector.partialText)

        // final 结果（flags=0x3 带 seq + 末包）→ emitFinal 并关闭连接
        mock.hostListener?.onBinary(serverResp("""{"result":{"text":"你好世界"}}""", flags = 0x3))
        assertEquals("final 结果", "你好世界", collector.finalText)
        assertTrue("final 后应关闭连接", mock.closed)

        // 服务端错误帧 → emitError
        mock.hostListener?.onBinary(serverError(45000001, "auth failed"))
        assertTrue("错误应上报", (collector.error ?: "").contains("45000001"))

        // stop → 发送最后一包标记（flags=0x3 NEG_WITH_SEQUENCE，seq 取负）
        mock.hostListener?.onOpen()
        mock.sentBinaries.clear()
        runtime.call("stop")
        val last = mock.sentBinaries.last()
        assertEquals("最后一包 flags=0x3", (0x2 shl 4) or 0x3, last[1].toInt() and 0xFF)
        assertTrue("最后一包 seq 应为负", readInt32(last, 4) < 0)
    }

    private fun readU32(arr: ByteArray, offset: Int): Int =
        ((arr[offset].toInt() and 0xFF) shl 24) or
            ((arr[offset + 1].toInt() and 0xFF) shl 16) or
            ((arr[offset + 2].toInt() and 0xFF) shl 8) or
            (arr[offset + 3].toInt() and 0xFF)

    private fun readInt32(arr: ByteArray, offset: Int): Int {
        val v = ((arr[offset].toLong() and 0xFF) shl 24) or
            ((arr[offset + 1].toLong() and 0xFF) shl 16) or
            ((arr[offset + 2].toLong() and 0xFF) shl 8) or
            (arr[offset + 3].toLong() and 0xFF)
        return v.toInt()
    }

    private fun gunzip(data: ByteArray): ByteArray =
        java.util.zip.GZIPInputStream(data.inputStream()).readBytes()
}
