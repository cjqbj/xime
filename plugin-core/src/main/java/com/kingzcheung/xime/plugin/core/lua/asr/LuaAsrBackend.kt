package com.kingzcheung.xime.plugin.core.lua.asr

import android.util.Log
import com.kingzcheung.xime.plugin.core.api.AsrPluginBackend
import com.kingzcheung.xime.plugin.core.api.AsrPluginListener
import com.kingzcheung.xime.plugin.core.api.AsrPluginState
import com.kingzcheung.xime.plugin.core.lua.LuaScriptRuntime
import org.luaj.vm2.LuaValue

/**
 * Lua 插件 ASR 后端适配器：**全部转发插件 Lua 导出函数**。
 *
 * 连接/协议/prebuffer/音频发送决策都由 Lua 承载（Lua 使用宿主通用 `host.ws`），
 * 本类只做接口桥接：
 * - processAudioChunk → Lua `processAudioChunk(pcm)`（Lua 决定缓冲还是直发）
 * - WS 结果经 Lua 解析后通过 `host.asr.emit*` 回传本类 listener
 */
class LuaAsrBackend(
    private val runtime: LuaScriptRuntime
) : AsrPluginBackend {

    private val logTag = "LuaAsrBackend"
    private var backendListener: AsrPluginListener? = null
    private var running = false

    override val isRunning: Boolean get() = running

    override fun setListener(listener: AsrPluginListener) {
        backendListener = listener
        runtime.asrResultCallback = listener
    }

    override fun initialize(): Boolean {
        return runtime.call("initialize").toboolean()
    }

    override fun start(): Boolean {
        val ok = try {
            runtime.call("start").toboolean()
        } catch (e: Exception) {
            Log.e(logTag, "start failed", e)
            false
        }
        if (ok) running = true
        return ok
    }

    override fun processAudioChunk(pcm: ByteArray) {
        runtime.call("processAudioChunk", org.luaj.vm2.LuaString.valueOf(pcm))
    }

    override fun stop() {
        try {
            runtime.call("stop")
        } catch (e: Exception) {
            Log.e(logTag, "stop failed", e)
        }
        running = false
        backendListener?.onStopped()
    }

    override fun cancel() {
        try {
            runtime.call("cancel")
        } catch (e: Exception) {
            Log.e(logTag, "cancel failed", e)
        }
        running = false
    }

    override fun release() {
        cancel()
        backendListener = null
        runtime.asrResultCallback = null
    }

    override fun getState(): AsrPluginState {
        return when (runtime.call("getState").toint()) {
            1 -> AsrPluginState.LISTENING
            2 -> AsrPluginState.PROCESSING
            3 -> AsrPluginState.ERROR
            else -> AsrPluginState.IDLE
        }
    }
}
