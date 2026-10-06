package com.kingzcheung.xime.storage

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Rime 用户目录（files/rime/）的外置镜像与恢复。
 *
 * 镜像范围 = 用户可变数据：custom 补丁（外观/键位/颜色）、user.yaml、custom_phrase.txt、
 * userdb 自学习词库、lua、用户导入的 schema 与 dict（随包 assets 自带的除外）、
 * 背景图/字体等。
 * 不镜像：build/（可重编译）、default.yaml（由 SchemaManager 按已启用方案重写）、
 * assets 内置文件（随版本更新，避免旧外置覆盖新内置）。
 *
 * 冲突策略：外置为准。reconcile 在每次 Rime 数据初始化、assets 拷贝之后执行：
 * 外置存在的文件覆盖内置，内置有而外置没有的用户文件反向留种。
 */
object RimeBackup {
    private const val TAG = "RimeBackup"
    private const val DIR = "rime"
    private const val PERIODIC_MS = 180_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var periodicStarted = false
    @Volatile private var mirroring = false
    @Volatile private var lastMirroredAt = 0L
    private var pendingJob: Job? = null

    private var cachedAssetFiles: Set<String>? = null

    /** 随包 assets/rime 内的文件相对路径集合（递归）。 */
    private fun assetFiles(context: Context): Set<String> {
        cachedAssetFiles?.let { return it }
        val out = mutableSetOf<String>()
        fun walk(folder: String) {
            val children = context.assets.list(folder) ?: return
            if (children.isEmpty()) return
            for (name in children) {
                val path = if (folder.isEmpty()) name else "$folder/$name"
                val sub = context.assets.list(path)
                if (sub != null && sub.isNotEmpty()) walk(path) else out.add(path)
            }
        }
        runCatching { walk("rime") }
        return out.toSet().also { cachedAssetFiles = it }
    }

    /** 判断相对路径是否属于用户数据（需要备份/恢复）。 */
    private fun isUserData(rel: String, assetSet: Set<String>): Boolean {
        if (rel.startsWith("build/")) return false
        if (rel == "default.yaml") return false
        if (rel in assetSet) return false
        // leveldb 活动锁/日志不搬（会自动重建，避免持锁文件复制）
        if (rel.endsWith("/LOCK") || rel.endsWith("/LOG") || rel.endsWith("/LOG.old")) return false
        return true
    }

    /**
     * 双向对齐（外置为准）。须在 assets 拷贝完成后、Rime 引擎 initialize 之前调用。
     */
    fun reconcile(context: Context, rimeDir: File) {
        val extDir = StorageRoots.ensureExternalDir(context, DIR) ?: return  // 外置不可用：纯内置
        val assetSet = assetFiles(context)
        try {
            // 1) 外置 → 内置（外置为准）；先拷数据文件，MANIFEST/CURRENT 最后
            val restored = syncRimeTree(extDir, rimeDir, assetSet, overwrite = true)
            // 2) 内置有、外置无的用户文件 → 外置留种（新装首跑 / 新版本带来的用户态文件）
            val seeded = syncRimeTree(rimeDir, extDir, assetSet, overwrite = false)
            Log.i(TAG, "rime reconcile: 外置恢复 $restored 个，留种 $seeded 个")
        } catch (e: Throwable) {
            Log.w(TAG, "rime reconcile 失败: ${e.message}")
        }
        startPeriodic(context.applicationContext)
    }

    /**
     * 把内置用户文件镜像到外置（覆盖已变化文件）。部署完成后调用。
     */
    fun mirrorOut(context: Context) {
        val ctx = context.applicationContext
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(2000) // 等部署/词库写盘 settle
            doMirror(ctx)
        }
    }

    private fun doMirror(context: Context) {
        if (mirroring) return
        val extDir = StorageRoots.ensureExternalDir(context, DIR) ?: return
        val rimeDir = File(context.filesDir, "rime")
        if (!rimeDir.isDirectory) return
        val assetSet = assetFiles(context)
        mirroring = true
        try {
            val n = syncRimeTree(rimeDir, extDir, assetSet, overwrite = true)
            lastMirroredAt = System.currentTimeMillis()
            if (n > 0) Log.i(TAG, "周期镜像 $n 个用户文件到外置")
        } catch (e: Throwable) {
            Log.w(TAG, "rime 镜像失败: ${e.message}")
        } finally {
            mirroring = false
        }
    }

    /**
     * 单向同步用户文件。为降低 leveldb 拷贝窗口，先同步 *.ldb/日志等不可变数据，
     * 最后同步 MANIFEST/CURRENT（它们引用的数据文件此时已就位）。
     */
    private fun syncRimeTree(
        src: File,
        dst: File,
        assetSet: Set<String>,
        overwrite: Boolean,
    ): Int {
        if (!src.isDirectory) return 0
        val files = src.walkTopDown().filter { it.isFile }
            .map { it to it.relativeTo(src).invariantSeparatorsPath }
            .filter { isUserData(it.second, assetSet) }
            .sortedBy { (_, rel) ->
                when {
                    rel.endsWith("/CURRENT") -> 2
                    rel.contains("/MANIFEST-") -> 1
                    else -> 0
                }
            }
        var count = 0
        for ((f, rel) in files) {
            val target = File(dst, rel)
            if (target.exists()) {
                if (!overwrite) continue
                if (target.length() == f.length() && target.lastModified() == f.lastModified()) continue
            }
            if (StorageRoots.copyFile(f, target, overwrite = true)) {
                target.setLastModified(f.lastModified())
                count++
            }
        }
        return count
    }

    /** 运行期周期镜像自学习词库等持续变化的文件。 */
    private fun startPeriodic(context: Context) {
        if (periodicStarted) return
        periodicStarted = true
        scope.launch {
            while (true) {
                delay(PERIODIC_MS)
                runCatching { doMirror(context) }
            }
        }
    }
}
