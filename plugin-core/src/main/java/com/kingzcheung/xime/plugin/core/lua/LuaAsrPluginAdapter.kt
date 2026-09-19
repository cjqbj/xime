package com.kingzcheung.xime.plugin.core.lua

import android.content.Context
import com.kingzcheung.xime.plugin.core.api.AsrAudioFormat
import com.kingzcheung.xime.plugin.core.api.AsrInputMode
import com.kingzcheung.xime.plugin.core.api.AsrPlugin
import com.kingzcheung.xime.plugin.core.api.AsrPluginBackend
import com.kingzcheung.xime.plugin.core.api.AsrPluginCapabilities
import com.kingzcheung.xime.plugin.core.lua.asr.LuaAsrBackend
import com.kingzcheung.xime.plugin.core.model.PluginContext

/** speech 类型 Lua 插件的宿主侧适配器：实现 AsrPlugin 接口。 */
class LuaAsrPluginAdapter(
    runtime: LuaScriptRuntime,
    pluginContext: PluginContext
) : LuaPluginAdapter(runtime, pluginContext), AsrPlugin {

    override val providerId: String
        get() = runtime.call("getProviderId").let { result ->
            if (result.isnil()) pluginContext.pluginInfo.id
            else result.tojstring().takeIf { it.isNotBlank() } ?: pluginContext.pluginInfo.id
        }

    override fun getDisplayName(): String {
        return runtime.call("getDisplayName").let { result ->
            if (result.isnil()) pluginContext.pluginInfo.name
            else result.tojstring().takeIf { it.isNotBlank() } ?: pluginContext.pluginInfo.name
        }
    }

    override fun getCapabilities(): AsrPluginCapabilities {
        val result = runtime.call("getCapabilities")
        if (!result.istable()) return AsrPluginCapabilities()
        val map = LuaScriptRuntime.tableToMap(result)
        return AsrPluginCapabilities(
            inputMode = if (map["inputMode"]?.tojstring() == "batch") AsrInputMode.BATCH else AsrInputMode.STREAMING,
            supportsPartialResults = map["supportsPartialResults"]?.toboolean() ?: true,
            maxRecordDurationMillis = map["maxRecordDurationMillis"]?.toint() ?: (10 * 60 * 1000),
            requiresNetwork = map["requiresNetwork"]?.toboolean() ?: true
        )
    }

    override fun getAudioFormat(): AsrAudioFormat = AsrAudioFormat()

    override fun isConfigured(): Boolean = runtime.call("isConfigured").toboolean()

    override fun createBackend(context: Context): AsrPluginBackend = LuaAsrBackend(runtime)
}
