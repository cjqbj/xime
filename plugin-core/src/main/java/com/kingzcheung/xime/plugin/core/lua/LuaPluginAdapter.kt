package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.api.IPluginEntryClass
import com.kingzcheung.xime.plugin.core.api.PluginIcon
import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable
import com.kingzcheung.xime.plugin.core.config.PluginFieldType
import com.kingzcheung.xime.plugin.core.config.PluginSettingField
import com.kingzcheung.xime.plugin.core.lua.sdk.LuaPluginContract
import com.kingzcheung.xime.plugin.core.model.PluginContext
import org.luaj.vm2.LuaValue

/**
 * Lua 脚本插件的宿主侧适配器基类：实现通用接口（入口生命周期 + 设置项 + 图标），
 * 具体能力接口（EmojiPlugin / AsrPlugin）由按插件类别派生的子类实现，
 * 保证 `instance is AsrPlugin` 只对 speech 类型插件成立。
 */
open class LuaPluginAdapter(
    protected val runtime: LuaScriptRuntime,
    protected val pluginContext: PluginContext
) : IPluginEntryClass, IPluginConfigurable {

    override fun getSettingsSchema(): List<PluginSettingField> {
        val result = runtime.call("getSettingsSchema")
        if (!result.istable()) return emptyList()
        return LuaScriptRuntime.tableToList(result).mapNotNull { field ->
            val map = LuaScriptRuntime.tableToMap(field)
            val key = map["key"]?.tojstring() ?: return@mapNotNull null
            PluginSettingField(
                key = key,
                label = map["label"]?.tojstring() ?: key,
                type = parseFieldType(map["type"]?.tojstring()),
                placeholder = map["placeholder"]?.tojstring(),
                defaultValue = map["defaultValue"]?.tojstring(),
                options = stringList(map["options"] ?: LuaValue.NIL),
                helpText = map["helpText"]?.tojstring(),
                section = map["section"]?.tojstring(),
                required = map["required"]?.toboolean() ?: true,
                action = map["action"]?.tojstring()
            )
        }
    }

    override suspend fun onAction(action: String): String? {
        if (action.isBlank()) return "未知操作"
        val result = runtime.call(action)
        if (result.isnil()) return null
        val msg = result.tojstring()
        return if (msg.isBlank()) null else msg
    }

    override fun getOptions(key: String): List<String>? {
        val result = runtime.call("getOptions", LuaValue.valueOf(key))
        if (!result.istable()) return null
        return stringList(result)
    }

    private fun parseFieldType(type: String?): PluginFieldType = when (type) {
        "secret" -> PluginFieldType.SECRET
        "select" -> PluginFieldType.SELECT
        "multi_select" -> PluginFieldType.MULTI_SELECT
        "switch" -> PluginFieldType.SWITCH
        "number" -> PluginFieldType.NUMBER
        "button" -> PluginFieldType.BUTTON
        else -> PluginFieldType.TEXT
    }

    private fun stringList(value: LuaValue): List<String> {
        if (!value.istable()) return emptyList()
        return LuaScriptRuntime.tableToList(value).mapNotNull { it.tojstring() }
    }

    override fun getIcon(): PluginIcon? {
        val result = runtime.call("getIcon")
        if (!result.istable()) return null
        val map = LuaScriptRuntime.tableToMap(result)
        val text = map["text"]?.tojstring()?.takeIf { it.isNotBlank() }
        if (text != null) return PluginIcon(text = text)
        val assetName = map["assetName"]?.tojstring()?.takeIf { it.isNotBlank() }
        if (assetName != null) return PluginIcon(assetName = assetName)
        return null
    }

    override fun onLoad(context: PluginContext) {
        if (runtime.load()) {
            runtime.callOnLoad()
        }
    }

    override fun onUnload() {
        runtime.close()
    }

    companion object {
        const val FN_GET_EMOJIS = LuaPluginContract.FN_GET_EMOJIS
        const val FN_GET_CATEGORIES = LuaPluginContract.FN_GET_CATEGORIES
        const val FN_GET_CATEGORY_LAYOUT = LuaPluginContract.FN_GET_CATEGORY_LAYOUT
    }
}
