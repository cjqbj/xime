package com.kingzcheung.xime.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 外置存储根解析层 —— 全 App 唯一的 /sdcard 数据出口。
 *
 * 外置根目录：/sdcard/Alarms/xime/
 *
 * 决策策略（各业务镜像模块统一遵循）：
 *  - 外置可用且条目存在       → 用外置（外置为准，冲突时外置覆盖内置）
 *  - 外置可用但条目不存在     → 把内置对应条目复制到外置后使用外置
 *  - 外置不可用（未挂载/未授权）→ 全部退回 /data/data 内置路径
 */
object StorageRoots {
    private const val TAG = "StorageRoots"
    private const val APP_DIR = "xime"

    /** 外置根 File（无论是否可用都返回路径，可用性需另查 [isExternalAvailable]）。 */
    fun externalRoot(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_ALARMS), APP_DIR)

    /** 外置某子路径（不创建目录）。 */
    fun externalFile(relative: String): File = File(externalRoot(), relative)

    /**
     * 外置存储当前是否可写：已挂载 +（R 以上有「所有文件访问」权限，低版本有写权限）+ 实测可建文件。
     */
    fun isExternalAvailable(context: Context): Boolean {
        val root = externalRoot()
        if (Environment.getExternalStorageState(root) != Environment.MEDIA_MOUNTED) return false
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return false
        // 实测探针：部分 ROM 授权状态与实际可写不一致
        return runCatching {
            if (!root.exists()) root.mkdirs()
            val probe = File(root, ".write_probe")
            probe.writeText("1")
            probe.delete()
            true
        }.getOrElse {
            Log.w(TAG, "外置存储不可写: ${it.message}")
            false
        }
    }

    /** 确保某外置子目录存在并可写；不可用时返回 null。 */
    fun ensureExternalDir(context: Context, relative: String): File? {
        if (!isExternalAvailable(context)) return null
        val dir = if (relative.isEmpty()) externalRoot() else File(externalRoot(), relative)
        return if (dir.exists() || dir.mkdirs()) dir else null
    }

    /** 复制单文件（自动建父目录，先写 .tmp 再原子改名）；目标存在按 [overwrite] 决定。 */
    fun copyFile(src: File, dst: File, overwrite: Boolean = true): Boolean {
        if (!src.isFile) return false
        if (dst.exists() && !overwrite) return true
        dst.parentFile?.mkdirs()
        return runCatching {
            val tmp = File(dst.absolutePath + ".tmp")
            src.inputStream().channel.use { input ->
                tmp.outputStream().channel.use { output -> output.transferFrom(input, 0, input.size()) }
            }
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrElse {
            Log.w(TAG, "copyFile 失败 ${src.absolutePath} -> ${dst.absolutePath}: ${it.message}")
            false
        }
    }

    /**
     * 目录树单向同步：把 [src] 下相对路径一致的文件同步到 [dst]。
     * - 仅同步相对路径（含文件名）与 [include] 匹配的普通文件；
     * - [include] 为空表示全部；
     * - 目标已存在且 [overwrite] 为 false 时跳过；
     * - 依据 长度+最后修改 跳过未变化文件。
     * @return 实际复制的文件数
     */
    fun syncTree(
        src: File,
        dst: File,
        overwrite: Boolean = true,
        include: Set<String>? = null,
    ): Int {
        if (!src.isDirectory) return 0
        var count = 0
        src.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(src).invariantSeparatorsPath
            if (include != null && include.none { matches(rel, it) }) return@forEach
            val target = File(dst, rel)
            if (target.exists()) {
                if (!overwrite) return@forEach
                if (target.length() == f.length() && target.lastModified() == f.lastModified()) {
                    return@forEach
                }
            }
            if (copyFile(f, target, overwrite = true)) {
                target.setLastModified(f.lastModified())
                count++
            }
        }
        return count
    }

    /** 简单通配：支持前缀目录（dir 下全部）与文件名通配（如 custom.yaml 一类）。 */
    private fun matches(rel: String, pattern: String): Boolean {
        if (pattern.endsWith("/**")) {
            return rel.startsWith(pattern.removeSuffix("**"))
        }
        if (pattern.contains("*")) {
            val regex = Regex(
                Regex.escape(pattern.substringBefore("*")) + ".*" +
                    Regex.escape(pattern.substringAfterLast("*"))
            )
            return regex.matches(rel)
        }
        return rel == pattern
    }
}
