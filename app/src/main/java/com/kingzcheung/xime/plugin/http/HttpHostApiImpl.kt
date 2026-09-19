package com.kingzcheung.xime.plugin.http

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kingzcheung.xime.plugin.PluginConfigStoreImpl
import com.kingzcheung.xime.plugin.core.lua.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.lua.http.HttpResponse
import com.kingzcheung.xime.plugin.core.lua.ws.NetworkPolicy
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.settings.SettingsPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 宿主通用 HTTP 白名单 API。
 *
 * - URL 域名须通过 [NetworkPolicy]：命中宿主可信池（官方域名）或插件已声明且经
 *   用户授权，否则拒绝（插件无法静默发起任意网络请求）
 * - 同步阻塞执行：必须在 IO 线程调用（宿主同步引擎在 Dispatchers.IO 运行）
 * - 协议无关：认证头/ETag/SigV4 全部由插件 Lua 组装，宿主只透传
 */
class HttpHostApiImpl(
    private val context: Context,
    private val pluginId: String
) : HttpHostApi {

    companion object {
        private const val TAG = "HttpHostApi"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var lastErrorMsg: String? = null

    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?
    ): HttpResponse? {
        val pluginInfo = PluginManager.getAllInstallPlugins()
            .firstOrNull { it.id == pluginId }
        val declaredHosts = pluginInfo?.declaredHosts ?: emptyList()
        val authorizedHosts = SettingsPreferences.getPluginAuthorizedHosts(context, pluginId)

        val reason = NetworkPolicy.check(url, emptySet(), declaredHosts, authorizedHosts)
        if (reason != null) {
            // 插件声明了"接受用户自定义服务器地址"时：若目标域名来自用户填写的配置 URL，
            // 视为用户意图连接该服务器，自动授权并放行。
            val host = NetworkPolicy.extractHost(url)
            if (pluginInfo?.allowCustomHosts == true && host != null && isConfiguredUrlHost(host)) {
                // 仅首次授权，避免每 3 秒轮询刷屏（授权后 authorizedHosts 命中即放行）
                if (host !in authorizedHosts) {
                    SettingsPreferences.authorizePluginHost(context, pluginId, host)
                    Log.d(TAG, "[$pluginId] 自动授权用户配置的服务器域名: $host")
                }
            } else {
                lastErrorMsg = reason
                Log.w(TAG, "[$pluginId] 联网被拒绝: $reason")
                return null
            }
        }
        lastErrorMsg = null

        return try {
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            val requestBody = (body ?: ByteArray(0)).toRequestBody(JSON_MEDIA_TYPE)
            when (method.uppercase()) {
                "GET" -> requestBuilder.get()
                "DELETE" -> requestBuilder.delete()
                "HEAD" -> requestBuilder.head()
                "PUT" -> requestBuilder.put(requestBody)
                "POST" -> requestBuilder.post(requestBody)
                "PATCH" -> requestBuilder.patch(requestBody)
                "MKCOL" -> requestBuilder.method("MKCOL", null)
                "PROPFIND" -> requestBuilder.method("PROPFIND", null)
                else -> requestBuilder.get()
            }
            client.newCall(requestBuilder.build()).execute().use { response ->
                toHttpResponse(response)
            }
        } catch (e: Exception) {
            lastErrorMsg = e.message ?: "request failed"
            Log.e(TAG, "[$pluginId] HTTP $method $url failed", e)
            null
        }
    }

    override fun lastError(): String? = lastErrorMsg

    /**
     * 判断目标域名是否来自插件配置中用户填写的 URL（如 serverUrl）。
     * 遍历插件配置所有值，凡以 http(s):// 开头的提取其域名比对。
     */
    private fun isConfiguredUrlHost(host: String): Boolean {
        return try {
            val configStore = PluginConfigStoreImpl(
                context.applicationContext as android.app.Application,
                pluginId
            )
            configStore.keys().any { key ->
                val value = configStore.get(key) ?: return@any false
                val configuredHost = extractHttpHost(value)
                configuredHost != null && configuredHost == host
            }
        } catch (e: Exception) {
            Log.e(TAG, "isConfiguredUrlHost failed", e)
            false
        }
    }

    private fun extractHttpHost(value: String): String? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        return NetworkPolicy.extractHost(withScheme)
    }

    private fun toHttpResponse(response: Response): HttpResponse {
        val headers = HashMap<String, String>()
        response.headers.forEach { (k, v) -> headers[k] = v }
        val bytes = response.body?.bytes() ?: ByteArray(0)
        return HttpResponse(
            status = response.code,
            headers = headers,
            body = bytes
        )
    }

    private val JSON_MEDIA_TYPE = "application/octet-stream".toMediaType()
}
