package com.kingzcheung.xime.plugin.core.lua

import android.util.Log
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.lua.sdk.LuaHostApi
import com.kingzcheung.xime.plugin.core.lua.sdk.LuaHostApiImpl
import com.kingzcheung.xime.plugin.core.lua.sdk.LuaPluginContract
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Lua 脚本插件运行时。
 *
 * ## 沙箱与隔离
 * - **一个插件一个 state**：每个 [LuaScriptRuntime] 持有独立的 `Globals`（独立 Lua 状态），
 *   插件间全局环境（`_G` 变量）、`require` 模块缓存完全隔离；一个插件的脚本错误不影响其他插件。
 * - **危险库剥离**：不加载 io/os，剥离 luajava（Java 反射）、loadfile/dofile（任意文件加载）
 * - **受限 require**：只能从插件包 libs/ 目录加载 .lua 模块，禁止路径穿越与 Java 类
 * - **宿主白名单 API**：仅注入 `host`（见 [LuaHostApi]）：config/log/resource
 */
class LuaScriptRuntime(
    private val pluginId: String,
    private val pluginDir: File,
    private val entryScript: String,
    private val configStore: PluginConfigStore,
    private val hostApi: LuaHostApi? = null,
    private val wsHostApi: com.kingzcheung.xime.plugin.core.lua.ws.WsHostApi? = null,
    private val httpHostApi: com.kingzcheung.xime.plugin.core.lua.http.HttpHostApi? = null,
    private val cryptoHostApi: com.kingzcheung.xime.plugin.core.lua.crypto.CryptoHostApi? = null
) {

    companion object {
        private const val TAG = "LuaRuntime"

        fun tableToMap(table: LuaValue): Map<String, LuaValue> {
            val result = LinkedHashMap<String, LuaValue>()
            if (!table.istable()) return result
            var k: LuaValue = LuaValue.NIL
            while (true) {
                val next = table.next(k)
                if (next.arg1().isnil()) break
                k = next.arg1()
                result[k.tojstring()] = next.arg(2)
            }
            return result
        }

        fun tableToList(table: LuaValue): List<LuaValue> {
            val result = mutableListOf<LuaValue>()
            if (!table.istable()) return result
            var k: LuaValue = LuaValue.NIL
            while (true) {
                val next = table.next(k)
                if (next.arg1().isnil()) break
                k = next.arg1()
                result.add(next.arg(2))
            }
            return result
        }

        /** Lua 值 → Java 对象（供 host.json.encode 使用）。 */
        fun tableToJava(value: LuaValue): Any? {
            return when {
                value.isnil() -> null
                value.isboolean() -> value.toboolean()
                value.isnumber() -> if (value.tonumber().isint()) value.toint() else value.todouble()
                value.isstring() -> value.tojstring()
                value.istable() -> {
                    if (value.length() > 0) {
                        val list = mutableListOf<Any?>()
                        for (i in 1..value.length()) {
                            list.add(tableToJava(value.get(i)))
                        }
                        list
                    } else {
                        val map = LinkedHashMap<String, Any?>()
                        tableToMap(value).forEach { (k, v) -> map[k] = tableToJava(v) }
                        map
                    }
                }
                else -> null
            }
        }

        /** Java 对象 → Lua 值（供 host.json.decode 使用，Map/List 转为 LuaTable）。 */
        fun javaToLua(value: Any?): LuaValue {
            return when (value) {
                null -> LuaValue.NIL
                is Map<*, *> -> {
                    val table = LuaTable()
                    value.forEach { (k, v) -> table.set(k.toString(), javaToLua(v)) }
                    table
                }
                is List<*> -> {
                    val table = LuaTable()
                    value.forEachIndexed { i, v -> table.set(i + 1, javaToLua(v)) }
                    table
                }
                is Boolean -> LuaValue.valueOf(value)
                is Number -> LuaValue.valueOf(value.toDouble())
                is String -> LuaValue.valueOf(value)
                else -> CoerceJavaToLua.coerce(value)
            }
        }
    }

    /** Lua 字符串（二进制可含 \0）或 ByteArray userdata → ByteArray。 */
    private fun luaToBytes(value: LuaValue): ByteArray? {
        if (value is LuaString) {
            val out = ByteArray(value.m_length)
            value.copyInto(0, out, 0, value.m_length)
            return out
        }
        return try {
            value.checkuserdata(ByteArray::class.java) as ByteArray
        } catch (e: Exception) {
            null
        }
    }

    private val api: LuaHostApi = hostApi ?: LuaHostApiImpl(pluginId, pluginDir, configStore)
    private val globals: Globals = buildSandbox()
    private val loadedModules = ConcurrentHashMap<String, LuaValue>()
    private val libsDir = File(pluginDir, "libs")

    /** ASR 插件后端设置的宿主结果回调（Lua 的 emit* 桥接目标）。 */
    @Volatile
    var asrResultCallback: com.kingzcheung.xime.plugin.core.api.AsrPluginListener? = null

    /** 宿主 WebSocket 白名单 API（app 层实现）。 */
    val wsApi: com.kingzcheung.xime.plugin.core.lua.ws.WsHostApi? = wsHostApi

    private var pluginTable: LuaValue = LuaValue.NIL
    private var loaded = false

    /** Lua 侧注册的 WS 事件回调（host.ws.connect 的 callbacks 表）。 */
    @Volatile
    private var wsCallbacks: Map<String, LuaValue>? = null

    private val wsListener = object : com.kingzcheung.xime.plugin.core.lua.ws.WsHostListener {
        override fun onOpen() { wsCallbacks?.get("onOpen")?.invoke() }
        override fun onMessage(text: String) { wsCallbacks?.get("onMessage")?.invoke(LuaValue.valueOf(text)) }
        override fun onBinary(data: ByteArray) { wsCallbacks?.get("onBinary")?.invoke(LuaString.valueOf(data)) }
        override fun onError(message: String) { wsCallbacks?.get("onError")?.invoke(LuaValue.valueOf(message)) }
        override fun onClose() { wsCallbacks?.get("onClose")?.invoke() }
    }

    private fun buildSandbox(): Globals {
        // JsePlatform 自动配置 compiler 与 loader；随后剥离全部危险库
        val g = org.luaj.vm2.lib.jse.JsePlatform.standardGlobals()

        // 危险库剥离：io/os（文件与系统调用）、luajava（Java 反射）、loadfile/dofile（任意文件加载）
        g.set("os", LuaValue.NIL)
        g.set("io", LuaValue.NIL)
        g.set("luajava", LuaValue.NIL)
        g.set("loadfile", LuaValue.NIL)
        g.set("dofile", LuaValue.NIL)

        g.set("print", luaFunction { args ->
            api.log(args.tojstring())
            LuaValue.NIL
        })

        // 受限 require：只能加载 libs/<name>.lua，禁止路径穿越与 java 类
        g.set("require", luaFunction { args ->
            val name = args.arg1().checkjstring()
            if (name.contains("/") || name.contains("\\") || name.contains("..")) {
                throw LuaError("require 非法模块名: $name")
            }
            synchronized(loadedModules) {
                loadedModules[name]?.let { return@luaFunction it }
                val moduleFile = File(libsDir, "$name.lua")
                if (!moduleFile.exists()) {
                    throw LuaError("module '$name' not found in libs/")
                }
                val chunk = g.load(moduleFile.readText(), "@$name")
                val result = chunk.call()
                val module = result.takeIf { it.istable() } ?: LuaValue.TRUE
                loadedModules[name] = module
                module
            }
        })

        // 宿主白名单 API（见 LuaHostApi SDK 接口）
        g.set(LuaPluginContract.GLOBAL_HOST, buildHostTable())
        return g
    }

    private fun buildHostTable(): LuaTable {
        val host = LuaTable()

        host.set("sdkVersion", api.sdkVersion)
        host.set("log", luaFunction { args ->
            api.log(args.tojstring())
            LuaValue.NIL
        })
        host.set("logError", luaFunction { args ->
            api.logError(args.tojstring())
            LuaValue.NIL
        })

        val config = LuaTable()
        config.set("get", luaFunction { args ->
            CoerceJavaToLua.coerce(api.configGet(args.arg1().checkjstring()))
        })
        config.set("set", luaFunction { args ->
            api.configSet(args.arg1().checkjstring(), args.arg(2).tojstring())
            LuaValue.TRUE
        })
        config.set("remove", luaFunction { args ->
            api.configRemove(args.arg1().checkjstring())
            LuaValue.TRUE
        })
        config.set("keys", luaFunction { _ ->
            val arr = LuaTable()
            api.configKeys().forEachIndexed { index, key -> arr.set(index + 1, key) }
            arr
        })
        host.set("config", config)

        val resource = LuaTable()
        resource.set("path", luaFunction { args ->
            CoerceJavaToLua.coerce(api.resourcePath(args.arg1().checkjstring()))
        })
        resource.set("list", luaFunction { args ->
            val arr = LuaTable()
            api.resourceList(args.arg1().checkjstring()).forEachIndexed { i, name ->
                arr.set(i + 1, name)
            }
            arr
        })
        host.set("resource", resource)

        val json = LuaTable()
        json.set("encode", luaFunction { args ->
            val obj = LuaScriptRuntime.tableToJava(args.arg1())
            CoerceJavaToLua.coerce(api.jsonEncode(obj))
        })
        json.set("decode", luaFunction { args ->
            javaToLua(api.jsonDecode(args.arg1().checkjstring()))
        })
        host.set("json", json)

        host.set("uuid", luaFunction { _ ->
            LuaValue.valueOf(api.uuid())
        })

        // 二进制原语：大端 int32（帧序号，负数按补码输出）与 gzip 压缩/解压（火山等二进制协议需要）
        val bin = LuaTable()
        bin.set("int32be", luaFunction { args ->
            val n = args.arg1().toint()
            LuaString.valueOf(
                byteArrayOf(
                    ((n ushr 24) and 0xFF).toByte(),
                    ((n ushr 16) and 0xFF).toByte(),
                    ((n ushr 8) and 0xFF).toByte(),
                    (n and 0xFF).toByte()
                )
            )
        })
        bin.set("uint32be", luaFunction { args ->
            val n = args.arg1().toint()
            LuaString.valueOf(
                byteArrayOf(
                    ((n ushr 24) and 0xFF).toByte(),
                    ((n ushr 16) and 0xFF).toByte(),
                    ((n ushr 8) and 0xFF).toByte(),
                    (n and 0xFF).toByte()
                )
            )
        })
        host.set("bin", bin)

        val zlib = LuaTable()
        zlib.set("gzip", luaFunction { args ->
            val data = luaToBytes(args.arg1()) ?: return@luaFunction LuaValue.NIL
            try {
                val bos = ByteArrayOutputStream()
                GZIPOutputStream(bos).use { it.write(data) }
                LuaString.valueOf(bos.toByteArray())
            } catch (e: Exception) {
                api.log("zlib.gzip failed: ${e.message}")
                LuaValue.NIL
            }
        })
        zlib.set("gunzip", luaFunction { args ->
            val data = luaToBytes(args.arg1()) ?: return@luaFunction LuaValue.NIL
            try {
                LuaString.valueOf(GZIPInputStream(data.inputStream()).readBytes())
            } catch (e: Exception) {
                api.log("zlib.gunzip failed: ${e.message}")
                LuaValue.NIL
            }
        })
        host.set("zlib", zlib)

        // 通用 WebSocket 白名单 API（协议无关，ASR 等网络插件使用，见 WsHostApi）
        if (wsHostApi != null) {
            host.set("ws", buildWsTable())
        }

        // 通用 HTTP 白名单 API（协议无关，剪贴板同步等插件使用，见 HttpHostApi）
        if (httpHostApi != null) {
            host.set("http", buildHttpTable())
        }

        // 加密/编码原语（S3 SigV4 签名等，见 CryptoHostApi）
        if (cryptoHostApi != null) {
            host.set("crypto", buildCryptoTable())
        }

        // ASR 结果回传桥：插件 Lua 解析结果后通知宿主后端（协议无关的接口桥）
        host.set("asr", buildAsrEmitTable())

        return host
    }

    private fun buildWsTable(): LuaTable {
        val ws = LuaTable()

        ws.set("connect", luaFunction { args ->
            val url = args.arg1().tojstring()
            val headers = HashMap<String, String>()
            LuaScriptRuntime.tableToMap(args.arg(2)).forEach { (k, v) ->
                headers[k] = v.tojstring()
            }
            // 解析 callbacks 表：{ onOpen=fn, onMessage=fn, onBinary=fn, onError=fn, onClose=fn }
            val callbacks = args.arg(3)
            if (callbacks.istable()) {
                val map = HashMap<String, LuaValue>()
                for (name in listOf("onOpen", "onMessage", "onBinary", "onError", "onClose")) {
                    val fn = callbacks.get(name)
                    if (fn.isfunction()) map[name] = fn
                }
                wsCallbacks = map
            }
            CoerceJavaToLua.coerce(wsHostApi?.connect(url, headers, wsListener) ?: false)
        })
        ws.set("sendText", luaFunction { args ->
            wsHostApi?.sendText(args.arg1().tojstring())
            LuaValue.NIL
        })
        ws.set("sendBinary", luaFunction { args ->
            val data = luaToBytes(args.arg1())
            if (data != null) wsHostApi?.sendBinary(data)
            LuaValue.NIL
        })
        ws.set("close", luaFunction { _ ->
            wsHostApi?.close()
            LuaValue.NIL
        })
        ws.set("getState", luaFunction { _ ->
            LuaValue.valueOf(wsHostApi?.getState() ?: 0)
        })
        ws.set("lastError", luaFunction { _ ->
            CoerceJavaToLua.coerce(wsHostApi?.lastError())
        })
        return ws
    }

    private fun buildHttpTable(): LuaTable {
        val http = LuaTable()

        http.set("request", luaFunction { args ->
            val method = args.arg1().tojstring()
            val url = args.arg(2).tojstring()
            val headers = HashMap<String, String>()
            LuaScriptRuntime.tableToMap(args.arg(3)).forEach { (k, v) ->
                headers[k] = v.tojstring()
            }
            val bodyArg = args.arg(4)
            val body: ByteArray? = when {
                bodyArg.isnil() -> null
                bodyArg.isstring() -> bodyArg.tojstring().toByteArray(Charsets.UTF_8)
                else -> luaToBytes(bodyArg)
            }
            val response = httpHostApi?.request(method, url, headers, body)
            if (response == null) {
                CoerceJavaToLua.coerce(null)
            } else {
                val table = LuaTable()
                table.set("status", response.status)
                val headerTable = LuaTable()
                response.headers.forEach { (k, v) -> headerTable.set(k, v) }
                table.set("headers", headerTable)
                table.set("body", LuaString.valueOf(response.body))
                table.set("text", response.body.toString(Charsets.UTF_8))
                table
            }
        })
        http.set("lastError", luaFunction { _ ->
            CoerceJavaToLua.coerce(httpHostApi?.lastError())
        })
        return http
    }

    private fun buildCryptoTable(): LuaTable {
        val crypto = LuaTable()

        crypto.set("sha256", luaFunction { args ->
            val data = luaToBytes(args.arg1()) ?: return@luaFunction LuaValue.NIL
            LuaString.valueOf(cryptoHostApi?.sha256(data) ?: return@luaFunction LuaValue.NIL)
        })
        crypto.set("hmacSha256", luaFunction { args ->
            val key = luaToBytes(args.arg1()) ?: return@luaFunction LuaValue.NIL
            val data = luaToBytes(args.arg(2)) ?: return@luaFunction LuaValue.NIL
            LuaString.valueOf(cryptoHostApi?.hmacSha256(key, data) ?: return@luaFunction LuaValue.NIL)
        })
        crypto.set("hex", luaFunction { args ->
            val data = luaToBytes(args.arg1()) ?: return@luaFunction LuaValue.NIL
            CoerceJavaToLua.coerce(cryptoHostApi?.hex(data))
        })
        crypto.set("base64", luaFunction { args ->
            val data = luaToBytes(args.arg1()) ?: return@luaFunction LuaValue.NIL
            CoerceJavaToLua.coerce(cryptoHostApi?.base64(data))
        })
        crypto.set("utcTime", luaFunction { args ->
            CoerceJavaToLua.coerce(cryptoHostApi?.utcTime(args.arg1().tojstring()))
        })
        return crypto
    }

    private fun buildAsrEmitTable(): LuaTable {
        val asr = LuaTable()

        asr.set("emitFinal", luaFunction { args ->
            asrResultCallback?.onFinal(args.arg1().tojstring())
            LuaValue.NIL
        })
        asr.set("emitPartial", luaFunction { args ->
            asrResultCallback?.onPartial(args.arg1().tojstring())
            LuaValue.NIL
        })
        asr.set("emitError", luaFunction { args ->
            asrResultCallback?.onError(args.arg1().tojstring())
            LuaValue.NIL
        })
        asr.set("emitState", luaFunction { args ->
            asrResultCallback?.onStateChanged(
                when (args.arg1().toint()) {
                    1 -> com.kingzcheung.xime.plugin.core.api.AsrPluginState.LISTENING
                    2 -> com.kingzcheung.xime.plugin.core.api.AsrPluginState.PROCESSING
                    3 -> com.kingzcheung.xime.plugin.core.api.AsrPluginState.ERROR
                    else -> com.kingzcheung.xime.plugin.core.api.AsrPluginState.IDLE
                }
            )
            LuaValue.NIL
        })
        return asr
    }

    /** 加载入口脚本并调用插件导出表。 */
    fun load(): Boolean {
        if (loaded) return true
        return try {
            val entryFile = File(pluginDir, entryScript)
            if (!entryFile.exists()) {
                Log.e(TAG, "Entry script not found: ${entryFile.absolutePath}")
                return false
            }
            val chunk = globals.load(entryFile.readText(), "@$entryScript")
            val result = chunk.call()
            pluginTable = result.takeIf { it.istable() } ?: LuaValue.NIL
            loaded = true
            Log.d(TAG, "Plugin $pluginId loaded from $entryScript")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Lua plugin $pluginId", e)
            false
        }
    }

    fun callOnLoad() {
        if (!loaded) return
        try {
            val fn = pluginTable.get(LuaPluginContract.FN_ON_LOAD)
            if (fn.isfunction()) {
                fn.invoke()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onLoad failed for $pluginId", e)
        }
    }

    fun callOnUnload() {
        if (!loaded) return
        try {
            val fn = pluginTable.get(LuaPluginContract.FN_ON_UNLOAD)
            if (fn.isfunction()) {
                fn.invoke()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onUnload failed for $pluginId", e)
        }
    }

    /** 调用插件导出的函数，返回 LuaValue 结果；不存在或出错返回 NIL。 */
    fun call(name: String, vararg args: LuaValue): LuaValue {
        if (!loaded) return LuaValue.NIL
        return try {
            val fn = pluginTable.get(name)
            if (!fn.isfunction()) {
                Log.w(TAG, "Plugin $pluginId does not export '$name'")
                return LuaValue.NIL
            }
            if (args.isEmpty()) {
                fn.invoke().arg1()
            } else {
                fn.invoke(args).arg1()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Call '$name' failed for $pluginId: ${e.message}", e)
            api.log("Call '$name' failed: ${e.message}")
            LuaValue.NIL
        }
    }

    fun close() {
        try {
            callOnUnload()
        } finally {
            loadedModules.clear()
            pluginTable = LuaValue.NIL
            loaded = false
        }
    }

    private fun luaFunction(body: (Varargs) -> Varargs): LuaValue {
        return object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = body(args)
        }
    }
}
