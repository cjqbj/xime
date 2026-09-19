package com.kingzcheung.xime.plugin.core.lua.ws

/**
 * 插件网络访问策略（纯函数，可单测）。
 *
 * 放行条件（满足其一）：
 * 1. 目标域名 ∈ 宿主可信池（官方域名，静默放行）
 * 2. 目标域名 ∈ 插件 manifest 声明的域名（declaredHosts）
 *    且 ∈ 用户已授权的域名集合（authorizedHosts）
 *
 * 否则拒绝——第三方插件必须声明域名并经用户授权才能联网。
 */
object NetworkPolicy {

    /** 校验 URL 是否允许访问。reason 为拒绝原因（null 表示放行）。 */
    fun check(
        url: String,
        trustedHosts: Set<String>,
        declaredHosts: List<String>,
        authorizedHosts: Set<String>
    ): String? {
        val host = extractHost(url) ?: return "无法解析 URL: $url"
        if (host in trustedHosts) return null

        if (host !in declaredHosts) {
            return "插件未声明访问域名 $host，已阻止联网"
        }
        if (host !in authorizedHosts) {
            return "访问 $host 未获用户授权，请在插件中心授权"
        }
        return null
    }

    /** 从 URL 提取域名（不含端口，纯字符串解析，无 Android 依赖）。 */
    fun extractHost(url: String): String? {
        var rest = url
        val schemeIdx = rest.indexOf("://")
        if (schemeIdx >= 0) rest = rest.substring(schemeIdx + 3)
        val pathIdx = rest.indexOf('/')
        if (pathIdx >= 0) rest = rest.substring(0, pathIdx)
        val queryIdx = rest.indexOf('?')
        if (queryIdx >= 0) rest = rest.substring(0, queryIdx)
        val host = rest.substringBefore(':')
        return host.takeIf { it.isNotBlank() }
    }
}
