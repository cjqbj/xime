package com.kingzcheung.xime.plugin.core.config

enum class PluginFieldType { TEXT, SECRET, SELECT, MULTI_SELECT, SWITCH, NUMBER, BUTTON }

data class PluginSettingField(
    val key: String,
    val label: String,
    val type: PluginFieldType,
    val placeholder: String? = null,
    val defaultValue: String? = null,
    val options: List<String> = emptyList(),
    val helpText: String? = null,
    val section: String? = null,
    val required: Boolean = true,
    /**
     * 动作标识（仅 [PluginFieldType.BUTTON] 使用）：点击按钮时触发宿主动作，
     * 通常为插件 Lua 导出的函数名（如 "testConnection"）。
     */
    val action: String? = null
)

interface IPluginConfigurable {
    fun getSettingsSchema(): List<PluginSettingField> = emptyList()

    /**
     * 动态选项：表单渲染 SELECT / MULTI_SELECT 时，若 [PluginSettingField.options]
     * 为空则调用本方法异步拉取（插件自行实现，如模型列表等运行时接口数据）。
     * 返回 null 表示无动态选项。
     */
    fun getOptions(key: String): List<String>? = null

    /**
     * 处理表单 BUTTON 字段点击（action 见 [PluginSettingField.action]）。
     *
     * 默认实现：把 [action] 当作插件 Lua 导出的函数名调用，返回其返回值
     * （nil/空 = 成功，否则为错误消息）。
     *
     * @return null 表示成功；非 null 为错误消息（表单层提示用户）
     */
    suspend fun onAction(action: String): String? = null
}
