package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.api.CategoryLayoutConfig
import com.kingzcheung.xime.plugin.core.api.EmojiItem
import com.kingzcheung.xime.plugin.core.api.EmojiPlugin
import com.kingzcheung.xime.plugin.core.lua.sdk.LuaPluginContract
import com.kingzcheung.xime.plugin.core.model.PluginContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.luaj.vm2.LuaValue

/** emoji 类型 Lua 插件的宿主侧适配器：实现 EmojiPlugin 接口。 */
class LuaEmojiPluginAdapter(
    runtime: LuaScriptRuntime,
    pluginContext: PluginContext
) : LuaPluginAdapter(runtime, pluginContext), EmojiPlugin {

    override suspend fun getEmojis(category: String?, searchText: String?, topK: Int): List<EmojiItem> =
        withContext(Dispatchers.IO) {
            val result = runtime.call(
                LuaPluginContract.FN_GET_EMOJIS,
                LuaValue.valueOf(category ?: ""),
                LuaValue.valueOf(searchText ?: ""),
                LuaValue.valueOf(topK)
            )
            LuaScriptRuntime.tableToList(result).mapNotNull { item ->
                val map = LuaScriptRuntime.tableToMap(item)
                val id = map[LuaPluginContract.FIELD_ID]?.tojstring() ?: return@mapNotNull null
                val text = map[LuaPluginContract.FIELD_TEXT]?.tojstring()
                    ?: map["displayText"]?.tojstring() ?: ""
                val insertText = map["insertText"]?.tojstring()?.takeIf { it.isNotBlank() }
                EmojiItem(
                    id = id,
                    displayText = text,
                    insertText = insertText ?: text,
                    imageUrl = map[LuaPluginContract.FIELD_IMAGE_URL]?.tojstring(),
                    category = map[LuaPluginContract.FIELD_CATEGORY]?.tojstring() ?: ""
                )
            }
        }

    override suspend fun getCategories(): List<String> = withContext(Dispatchers.IO) {
        LuaScriptRuntime.tableToList(runtime.call(LuaPluginContract.FN_GET_CATEGORIES))
            .mapNotNull { it.tojstring() }
    }

    override suspend fun getCategoryLayoutConfig(category: String): CategoryLayoutConfig? =
        withContext(Dispatchers.IO) {
            val result = runtime.call(LuaPluginContract.FN_GET_CATEGORY_LAYOUT, LuaValue.valueOf(category))
            if (!result.istable()) return@withContext null
            val map = LuaScriptRuntime.tableToMap(result)
            CategoryLayoutConfig(
                columns = map["columns"]?.toint() ?: 8,
                itemHeightDp = map["itemHeightDp"]?.toint() ?: 40
            )
        }
}
