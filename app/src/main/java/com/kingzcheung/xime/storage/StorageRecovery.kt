package com.kingzcheung.xime.storage

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 用户在系统设置中授予「所有文件访问」后（典型场景：卸载重装后先开了 App，后补授权），
 * 在不杀进程的前提下把外置 /sdcard/Alarms/xime 的数据对齐回内置活数据：
 *  - 设置：解析外置 XML 写回已加载的 SharedPreferences（见 [SettingsMirror]）；
 *  - rime 用户目录：外置为准双向 reconcile（见 [RimeBackup]）；
 *  - 剪贴板：库若已在本进程建出，由 ClipboardManager 的表观察自行做行级恢复；
 *    若尚未建库，下次 ClipboardDatabase.getInstance 时由 restoreIfNeeded 文件恢复。
 */
object StorageRecovery {
    private const val TAG = "StorageRecovery"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var handled = false

    fun onAllFilesAccessGranted(context: Context) {
        if (handled) return
        val ctx = context.applicationContext
        if (!StorageRoots.isExternalAvailable(ctx)) return
        handled = true
        Log.i(TAG, "外置存储已授权，运行中对齐外置数据")
        // prefs 写入内部自带去抖/异步，直接调用；rime 与剪贴板放 IO
        SettingsMirror.onExternalStorageAvailable(ctx)
        scope.launch {
            runCatching {
                RimeBackup.reconcile(ctx, File(ctx.filesDir, "rime"))
            }.onFailure { Log.w(TAG, "授权后 rime 对齐失败: ${it.message}") }
            runCatching {
                ClipboardBackup.recoverLive(ctx)
            }.onFailure { Log.w(TAG, "授权后剪贴板对齐失败: ${it.message}") }
        }
    }
}
