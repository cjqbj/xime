package com.kingzcheung.xime.plugin.ws

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kingzcheung.xime.plugin.core.lua.ws.NetworkPolicy
import com.kingzcheung.xime.plugin.core.lua.ws.WsHostApi
import com.kingzcheung.xime.plugin.core.lua.ws.WsHostListener
import com.kingzcheung.xime.settings.SettingsPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * 宿主通用 WebSocket 白名单 API。
 *
 * - 连接 URL 域名须通过 [NetworkPolicy]：命中宿主可信池（官方域名）或
 *   插件已声明且经用户授权，否则拒绝（插件无法静默发起任意网络请求）
 * - 协议无关：只提供收发原语，prebuffer/协议组装/结果解析全部由插件 Lua 承载
 */
class WsHostApiImpl(
    private val context: Context,
    private val pluginId: String
) : WsHostApi {

    companion object {
        private const val TAG = "WsHostApi"

        /** 宿主可信域名池（官方服务商，静默放行）。 */
        private val TRUSTED_HOSTS = setOf("dashscope.aliyuncs.com")

        private const val STATE_IDLE = 0
        private const val STATE_CONNECTING = 1
        private const val STATE_OPEN = 2
        private const val STATE_CLOSED = 3
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var listener: WsHostListener? = null

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var state = STATE_IDLE

    @Volatile
    private var lastErrorMsg: String? = null

    override fun connect(url: String, headers: Map<String, String>, listener: WsHostListener): Boolean {
        val declaredHosts = com.kingzcheung.xime.plugin.core.runtime.PluginManager.getAllInstallPlugins()
            .firstOrNull { it.id == pluginId }
            ?.declaredHosts ?: emptyList()
        val authorizedHosts = SettingsPreferences.getPluginAuthorizedHosts(context, pluginId)

        val reason = NetworkPolicy.check(url, TRUSTED_HOSTS, declaredHosts, authorizedHosts)
        if (reason != null) {
            lastErrorMsg = reason
            Log.w(TAG, "[$pluginId] 联网被拒绝: $reason")
            return false
        }
        lastErrorMsg = null

        synchronized(this) {
            if (webSocket != null) {
                Log.w(TAG, "Already connected, reusing")
                return true
            }
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            this.listener = listener
            webSocket = client.newWebSocket(requestBuilder.build(), wsListener)
            state = STATE_CONNECTING
            Log.d(TAG, "[$pluginId] Connecting to ${NetworkPolicy.extractHost(url)}")
        }
        return true
    }

    override fun sendText(message: String) {
        try {
            webSocket?.send(message)
        } catch (e: Exception) {
            Log.e(TAG, "sendText failed", e)
        }
    }

    override fun sendBinary(data: ByteArray) {
        try {
            webSocket?.send(data.toByteString())
        } catch (e: Exception) {
            Log.e(TAG, "sendBinary failed", e)
        }
    }

    override fun close() {
        synchronized(this) {
            webSocket?.close(1000, "closed")
            webSocket = null
            state = STATE_CLOSED
            listener = null
        }
    }

    override fun getState(): Int = state

    override fun lastError(): String? = lastErrorMsg

    private fun extractServerError(body: String): String? {
        if (body.isBlank()) return null
        return try {
            org.json.JSONObject(body).optString("error").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            state = STATE_OPEN
            listener?.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            listener?.onMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            listener?.onBinary(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val serverMsg = response?.let { r ->
                val body = try { r.body?.string() ?: "" } catch (e: Exception) { "" }
                val reason = extractServerError(body) ?: "HTTP ${r.code}"
                "HTTP ${r.code}: $reason"
            } ?: ""
            Log.e(TAG, "WS failure: ${t.message} | $serverMsg")
            state = STATE_CLOSED
            listener?.onError(serverMsg.ifEmpty { t.message ?: "连接失败" })
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            state = STATE_CLOSED
            listener?.onClose()
        }
    }
}
