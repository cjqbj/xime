package com.kingzcheung.xime.storage

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Process
import android.util.Log

/**
 * 用户在系统设置中授予「所有文件访问」后（典型场景：卸载重装后先开了 App、后补授权）：
 * 先把外置 prefs 原样复制回内置，然后立即重启本进程。
 *
 * 重启后启动期的三条恢复路径自动完成全部对齐，不做任何运行期解析/合并：
 *  - 设置：Application.onCreate 的 SettingsMirror.reconcile（外置 xml 原样覆盖）；
 *  - rime：RimeConfigHelper 初始化时 RimeBackup.reconcile（外置为准）；
 *  - 剪贴板：ClipboardDatabase 建库前 ClipboardBackup.restoreIfNeeded。
 */
object StorageRecovery {
    private const val TAG = "StorageRecovery"
    @Volatile private var handled = false

    fun onAllFilesAccessGranted(context: Context) {
        if (handled) return
        val ctx = context.applicationContext
        if (!StorageRoots.isExternalAvailable(ctx)) return
        handled = true

        val root = StorageRoots.externalRoot()
        val hasBackup = root.isDirectory && runCatching {
            root.listFiles()?.any { child ->
                child.isDirectory && child.listFiles()?.isNotEmpty() == true
            } == true
        }.getOrDefault(false)

        // 外置无任何历史数据：本次只是首次授权，无需重启（reconcile 会留种）
        if (!hasBackup) {
            Log.i(TAG, "外置存储已授权，无历史镜像，仅留种不重启")
            SettingsMirror.reconcile(ctx)
            return
        }

        Log.i(TAG, "外置存储已授权，复制设置后重启进程以完成全部恢复")
        SettingsMirror.reconcile(ctx)

        // 200ms 后拉起主界面，随后自杀；rime/剪贴板由各自启动期路径恢复
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        if (intent != null) {
            val pending = PendingIntent.getActivity(
                ctx, 0, intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.RTC, System.currentTimeMillis() + 200L, pending)
        }
        Process.killProcess(Process.myPid())
    }
}
