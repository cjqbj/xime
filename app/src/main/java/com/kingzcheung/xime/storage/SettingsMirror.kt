package com.kingzcheung.xime.storage

import android.content.Context
import android.os.FileObserver
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 整个 shared_prefs 目录的外置镜像：/sdcard/Alarms/xime/prefs/。
 *
 * 规则只有两条：
 *  1. 外置为准：外置已有的 *.xml 原样字节复制覆盖内置（含密码等全部字段，不解析、不改写）；
 *  2. 内置留种/镜像：内置新产生的 *.xml 复制到外置，之后用 FileObserver 监听落盘，
 *     变化即原样复制出去。
 *
 * 因此卸载重装后只要外置数据还在，所有输入法设置（外观、布局、主题缓存、
 * 模型版本记录、插件配置等全部 SharedPreferences 文件）与原来完全一致。
 *
 * 例外：chaquopy.xml 是 Python 运行时状态，不属于输入法设置，不镜像。
 */
object SettingsMirror {
    private const val TAG = "SettingsMirror"
    private const val PREFS_DIR = "prefs"
    private const val EXPORT_DEBOUNCE_MS = 1000L

    /** 不镜像的文件（Python/Chaquopy 相关）。 */
    private val EXCLUDE = setOf("chaquopy.xml")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var exportJob: Job? = null
    private var watcher: FileObserver? = null
    @Volatile private var watching = false

    private fun internalDir(ctx: Context) = File(ctx.applicationInfo.dataDir, "shared_prefs")

    private fun isMirrored(name: String) = name.endsWith(".xml") && name !in EXCLUDE

    /**
     * Application.onCreate 最早期（任何业务读 prefs 前）调用：
     * 外置可用 → 外置 xml 原样覆盖内置 + 内置独有 xml 留种外置，并开始监听。
     * 外置不可用 → 直接返回（授权后由 StorageRecovery 触发重启，启动期再次走到这里）。
     */
    fun reconcile(context: Context) {
        val ctx = context.applicationContext
        val internal = internalDir(ctx)
        val ext = StorageRoots.ensureExternalDir(ctx, PREFS_DIR) ?: return

        // 1. 外置为准：外置每个 xml 原样覆盖内置
        ext.listFiles { f -> f.isFile && isMirrored(f.name) }?.forEach { extXml ->
            if (StorageRoots.copyFile(extXml, File(internal, extXml.name), overwrite = true)) {
                Log.i(TAG, "外置为准，原样恢复设置文件: ${extXml.name}")
            }
        }

        // 2. 内置独有文件留种到外置
        internal.listFiles { f -> f.isFile && isMirrored(f.name) }?.forEach { inXml ->
            val extXml = File(ext, inXml.name)
            if (!extXml.exists()) {
                StorageRoots.copyFile(inXml, extXml, overwrite = false)
                Log.i(TAG, "外置缺失，内置设置留种: ${inXml.name}")
            }
        }

        startWatcher(internal, ext)
    }

    /** 监听内置 shared_prefs 目录落盘，变化文件原样复制到外置。 */
    private fun startWatcher(internal: File, ext: File) {
        if (watching) return
        val observer = object : FileObserver(internal.absolutePath, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null || !isMirrored(path)) return
                scheduleMirror(File(internal, path), File(ext, path))
            }
        }
        observer.startWatching()
        watcher = observer
        watching = true
    }

    /** 去抖复制，避免连续调节设置时频繁拷贝。 */
    private fun scheduleMirror(src: File, dst: File) {
        exportJob?.cancel()
        exportJob = scope.launch {
            delay(EXPORT_DEBOUNCE_MS) // 等落盘彻底完成
            if (src.isFile && StorageRoots.copyFile(src, dst, overwrite = true)) {
                Log.i(TAG, "设置已镜像到外置: ${src.name}")
            }
        }
    }
}
