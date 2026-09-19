package com.kingzcheung.xime.plugin.core.model

/** 插件信任等级：Lua 插件无签名，由插件中心按来源与用户授权展示信任标记。 */
enum class TrustLevel {
    /** 官方：与宿主来源一致 */
    TRUSTED,

    /** 第三方：非官方来源，需用户确认 */
    THIRD_PARTY,

    /** 未知：无法判定 */
    UNKNOWN
}

data class PluginInfo(
    val id: String,
    val name: String,
    val iconResId: Int,
    val versionCode: Long,
    val versionName: String,
    val path: String,
    val description: String,
    val type: String = "unknown",
    val enabled: Boolean = true,
    val installTime: Long = System.currentTimeMillis(),
    val source: PluginSource = PluginSource.SYSTEM,
    val minHostVersion: String? = null,
    val maxHostVersion: String? = null,
    val trustLevel: TrustLevel = TrustLevel.UNKNOWN,
    /** Lua 入口脚本路径（相对插件包目录）。插件逻辑全部由该脚本导出。 */
    val entryScript: String? = null,
    /** 插件声明需要访问的域名（manifest.network.hosts）。联网时需命中可信池或获用户授权。 */
    val declaredHosts: List<String> = emptyList(),
    /** 是否接受用户自定义服务器地址（manifest.network.allowCustomHosts）。为 true 时，
     *  插件配置中用户填写的 URL 域名自动获得联网授权（剪贴板同步等服务器地址场景）。 */
    val allowCustomHosts: Boolean = false
) {
    val version: String get() = versionName
    val category: PluginCategory get() = PluginCategory.fromId(type)
}
