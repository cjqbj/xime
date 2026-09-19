package com.kingzcheung.xime.clipboard.sync

import android.util.Log
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.plugin.core.api.ClipboardProfile
import com.kingzcheung.xime.plugin.core.api.ClipboardSyncPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * 剪贴板同步引擎（仿 ximed SyncEngine）。
 *
 * 规则（与 ximed 设计一致的三通道去重语义）：
 * - 本地剪贴板变化 → 与上次推送 hash 不同且非"自写内容" → 推送到远端
 * - 拉取远端（启动时 + 键盘显示时 pullOnce）→ 与本地 hash 及"自写 hash"比对 → 不同才写回本地剪贴板
 * - 自写抑制：引擎写回本地后记录 hash，监听到同一 hash 判定为回声，跳过推送
 *
 * 不再做后台轮询：拉取只在启动时和键盘显示（[pullOnce]）时各触发一次，
 * 避免 IME service 生命周期内频繁请求；推送仍由本地剪贴板变化实时触发。
 *
 * 宿主只持有引擎与剪贴板桥，具体传输协议（WebDAV/S3/ximed）由 [ClipboardSyncPlugin]
 * 的 Lua 实现承载。
 */
class ClipboardSyncBridge(
    private val clipboardManager: ClipboardManager,
    private val plugin: ClipboardSyncPlugin,
    val pluginId: String = ""
) {
    companion object {
        private const val TAG = "ClipboardSync"
        private const val PUSH_RETRY_BACKOFF_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 上次推送/写入的 hash（去重）。 */
    @Volatile
    private var lastHash: String? = null

    /** 自写 hash：引擎写入本地剪贴板的内容 hash，用于回声抑制。 */
    @Volatile
    private var selfWritten: String? = null

    @Volatile
    private var running = false

    /** 推送失败后的退避截止时间。 */
    @Volatile
    private var retryUntil = 0L

    private var collectJob: Job? = null

    fun start() {
        if (running) return
        running = true
        Log.d(TAG, "Sync started")

        // 1. 订阅本地剪贴板变化 → push（回声抑制：selfWritten 命中的跳过）
        collectJob = clipboardManager.clipboardChanged
            .filter { it.text.isNotBlank() }
            .onEach { item ->
                val hash = ClipboardProfile.sha256Hex(item.text.toByteArray(Charsets.UTF_8))
                if (hash == selfWritten) {
                    Log.d(TAG, "Echo suppressed (self-written)")
                    return@onEach
                }
                pushLocal(item.text, hash)
            }
            .launchIn(scope)

        // 2. 启动即拉取一次；后续由键盘显示时 pullOnce() 触发
        scope.launch { pullRemote() }
    }

    fun stop() {
        if (!running) return
        running = false
        collectJob?.cancel()
        collectJob = null
        Log.d(TAG, "Sync stopped")
    }

    fun release() {
        stop()
        scope.cancel()
    }

    /** 键盘显示时触发一次拉取。 */
    fun pullOnce() {
        if (!running) return
        scope.launch {
            pullRemote()
        }
    }

    private suspend fun pushLocal(text: String, hash: String) {
        if (!running) return
        if (hash == lastHash) {
            Log.d(TAG, "No change, skip push")
            return
        }
        if (System.currentTimeMillis() < retryUntil) {
            Log.d(TAG, "Push backoff active, skip")
            return
        }
        val profile = ClipboardProfile.fromText(text)
        val ok = try {
            plugin.push(profile)
        } catch (e: Exception) {
            Log.e(TAG, "push failed", e)
            false
        }
        if (ok) {
            lastHash = hash
            retryUntil = 0L
        } else {
            retryUntil = System.currentTimeMillis() + PUSH_RETRY_BACKOFF_MS
        }
    }

    private suspend fun pullRemote() {
        val remote = try {
            plugin.pull()
        } catch (e: Exception) {
            Log.e(TAG, "pull failed", e)
            null
        }
        if (remote == null) return

        val remoteHash = remote.hash
        val currentClipboard = clipboardManager.getCurrentClipboardText()
        val currentHash = currentClipboard?.let {
            ClipboardProfile.sha256Hex(it.toByteArray(Charsets.UTF_8))
        }

        // 与本地当前内容相同 或 与自己写回的内容相同 → 跳过（避免循环）
        if (remoteHash == currentHash || remoteHash == selfWritten) {
            Log.d(TAG, "Remote unchanged vs local, skip write")
            return
        }

        Log.d(TAG, "Remote changed, writing to local clipboard")
        lastHash = remoteHash
        selfWritten = remoteHash
        clipboardManager.copyToSystemClipboard(remote.text)
    }
}
