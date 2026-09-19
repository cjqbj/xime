package com.kingzcheung.xime.plugin.core.lua

import android.util.Log
import com.kingzcheung.xime.plugin.core.api.ClipboardProfile
import com.kingzcheung.xime.plugin.core.api.ClipboardSyncPlugin
import com.kingzcheung.xime.plugin.core.model.PluginContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

/**
 * clipboard_sync 类型 Lua 插件的宿主侧适配器：实现 [ClipboardSyncPlugin] 接口。
 *
 * 协议逻辑（WebDAV / S3 / ximed HTTP）全部由插件 Lua 用 `host.http` + `host.crypto`
 * 承载，本类只做接口桥接：
 * - push(profile)     → Lua `push(profileTable)`，profile 字段转 snake_case
 * - pull(lastEtag)    → Lua `pull(lastEtag)`，返回 profile 表（nil → 无变更）
 * - testConnection()  → Lua `testConnection()`，返回错误消息（nil/空 → 成功）
 */
class LuaClipboardSyncPluginAdapter(
    runtime: LuaScriptRuntime,
    pluginContext: PluginContext
) : LuaPluginAdapter(runtime, pluginContext), ClipboardSyncPlugin {

    override suspend fun push(profile: ClipboardProfile): Boolean = withContext(Dispatchers.IO) {
        try {
            val table = LuaTable()
            table.set("type", profile.type)
            table.set("hash", profile.hash)
            table.set("text", profile.text)
            table.set("has_data", LuaValue.valueOf(profile.hasData))
            if (profile.dataName != null) table.set("data_name", profile.dataName)
            table.set("size", LuaValue.valueOf(profile.size.toDouble()))
            if (profile.source != null) table.set("source", profile.source)
            val result = runtime.call("push", table)
            if (!result.toboolean()) {
                Log.e("LuaClipboardSync", "push returned false")
            }
            result.toboolean()
        } catch (e: Exception) {
            Log.e("LuaClipboardSync", "push failed", e)
            false
        }
    }

    override suspend fun pull(): ClipboardProfile? = withContext(Dispatchers.IO) {
        try {
            val result = runtime.call("pull")
            if (!result.istable()) return@withContext null
            val map = LuaScriptRuntime.tableToMap(result)
            val text = map["text"]?.tojstring()?.takeIf { it.isNotEmpty() } ?: return@withContext null
            val hash = map["hash"]?.tojstring()?.takeIf { it.isNotEmpty() }
                ?: ClipboardProfile.sha256Hex(text.toByteArray(Charsets.UTF_8))
            ClipboardProfile(
                type = map["type"]?.tojstring() ?: "text",
                hash = hash,
                text = text,
                hasData = map["has_data"]?.toboolean() ?: false,
                dataName = map["data_name"]?.tojstring(),
                size = map["size"]?.tolong() ?: 0,
                source = map["source"]?.tojstring()
            )
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun testConnection(): String? = withContext(Dispatchers.IO) {
        try {
            val result = runtime.call("testConnection")
            val msg = result.tojstring()
            if (msg.isBlank() || result.isnil()) null else msg
        } catch (e: Exception) {
            e.message ?: "connection test failed"
        }
    }
}
