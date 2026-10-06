package com.kingzcheung.xime.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.kingzcheung.xime.clipboard.db.ClipboardDao
import com.kingzcheung.xime.clipboard.db.ClipboardEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 剪贴板 Room 库（databases/clipboard.db）的外置备份与恢复。
 *
 * 策略：内置是唯一活库（/sdcard FUSE 上 SQLite 锁不可靠，不直接在上面跑）。
 *  - 建库前恢复 [restoreIfNeeded]：外置备份有数据而内置缺失/为空库时复制回来。
 *    「空库」场景真实存在：重装后用户在授予所有文件权限之前就唤起过键盘，
 *    Room 已建出零行空库；仅判断文件是否存在会漏恢复，进而用空库覆盖外置备份。
 *  - 活库对齐 [onLiveSnapshot]：表观察回调驱动。有数据时去抖备份；
 *    活库为空且外置有数据时，把外置条目行级插回活库（库已在同进程打开，
 *    无法再用文件替换）。空活库永不覆盖外置非空备份。
 *  - 备份前先 PRAGMA wal_checkpoint(TRUNCATE)，再原子复制单一 .db 到外置。
 */
object ClipboardBackup {
    private const val TAG = "ClipboardBackup"
    private const val DIR = "clipboard"
    private const val DB_NAME = "clipboard.db"
    private const val TABLE = "clipboard_entries"
    private const val BACKUP_DEBOUNCE_MS = 5000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var backing = false
    @Volatile private var importAttempted = false

    private fun internalDb(context: Context) =
        File(context.applicationInfo.dataDir, "databases/$DB_NAME")

    private fun externalDb(context: Context): File? =
        StorageRoots.ensureExternalDir(context, DIR)?.let { File(it, DB_NAME) }

    /** Room 建库前调用：外置有数据、内置缺失或为空库时复制恢复。 */
    fun restoreIfNeeded(context: Context) {
        val ctx = context.applicationContext
        val internal = internalDb(ctx)
        val ext = externalDb(ctx) ?: return  // 外置不可用：纯内置
        if (!ext.isFile) return
        try {
            if (!internal.exists()) {
                internal.parentFile?.mkdirs()
                val ok = copyDbWithWal(ext, internal)
                Log.i(TAG, "检测到外置剪贴板备份，已恢复: $ok")
                return
            }
            val extRows = countRows(ext)
            val inRows = countRows(internal)
            if (extRows > 0 && inRows == 0) {
                val ok = copyDbWithWal(ext, internal)
                Log.i(TAG, "内置为空库、外置有 $extRows 条，已用外置备份覆盖恢复: $ok")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "剪贴板恢复失败: ${e.message}")
        }
    }

    /**
     * 整库复制：主库 + 非空 -wal（连同 -shm）。
     * 外置备份历史上可能残留尚未 checkpoint 的 -wal（数据行只在 wal 里），
     * 只复制主库会恢复成空库；Room 随后打开时按 wal 自动恢复。
     */
    private fun copyDbWithWal(ext: File, internal: File): Boolean {
        // 内置旧库的 wal/shm 与待恢复主库不配套，先删掉避免错误回放
        File(internal.absolutePath + "-wal").delete()
        File(internal.absolutePath + "-shm").delete()
        if (!StorageRoots.copyFile(ext, internal, overwrite = true)) return false
        val extWal = File(ext.absolutePath + "-wal")
        if (extWal.isFile && extWal.length() > 0) {
            val inWal = File(internal.absolutePath + "-wal")
            val inShm = File(internal.absolutePath + "-shm")
            StorageRoots.copyFile(extWal, inWal, overwrite = true)
            val extShm = File(ext.absolutePath + "-shm")
            if (extShm.isFile) StorageRoots.copyFile(extShm, inShm, overwrite = true)
        }
        return true
    }

    /**
     * 活库表观察回调。
     * @param isEmpty 活库当前是否零行
     */
    fun onLiveSnapshot(context: Context, dao: ClipboardDao, isEmpty: Boolean) {
        if (isEmpty) {
            importRowsOnce(context, dao)
        } else {
            requestBackup(context)
        }
    }

    /** 活库已在本进程打开时（授权前建过空库），把外置条目行级插回活库。每进程仅尝试一次。 */
    private fun importRowsOnce(context: Context, dao: ClipboardDao) {
        val ext = prepareImport(context) ?: return
        importAttempted = true
        scope.launch { doImport(dao, ext) }
    }

    /**
     * 授权回调等时机显式触发：无论 Room 是否已建库，都把外置条目对齐到活库。
     * - 库尚未建：getInstance 内的 restoreIfNeeded 会先做文件级恢复；
     * - 库已建空库：行级插入恢复。
     */
    fun recoverLive(context: Context) {
        val ext = prepareImport(context) ?: return
        importAttempted = true
        scope.launch {
            val dao = com.kingzcheung.xime.clipboard.db.ClipboardDatabase
                .getInstance(context.applicationContext).clipboardDao()
            doImport(dao, ext)
        }
    }

    /** 恢复前置检查；返回外置库文件时表示可以尝试，null 表示不可用/已尝试过。 */
    private fun prepareImport(context: Context): File? {
        if (importAttempted) return null
        val ext = externalDb(context.applicationContext) ?: return null // 外置尚不可用：保持可重试
        if (!ext.isFile) {
            importAttempted = true
            return null
        }
        return ext
    }

    private suspend fun doImport(dao: ClipboardDao, ext: File) {
        try {
            if (countRows(ext) == 0) return
            if (dao.countAll() > 0) return
            val rows = readEntries(ext)
            if (rows.isEmpty()) return
            dao.insertAll(rows)
            Log.i(TAG, "外置剪贴板行级恢复 ${rows.size} 条到活库")
        } catch (e: Throwable) {
            Log.w(TAG, "剪贴板行级恢复失败: ${e.message}")
        }
    }

    /** 写操作后调用：去抖备份（仅由非空快照触发，空库不覆盖外置）。 */
    fun requestBackup(context: Context) {
        val ctx = context.applicationContext
        job?.cancel()
        job = scope.launch {
            delay(BACKUP_DEBOUNCE_MS)
            doBackup(ctx)
        }
    }

    private fun doBackup(context: Context) {
        if (backing) return
        val internal = internalDb(context)
        val ext = externalDb(context) ?: return
        if (!internal.isFile) return
        // 最后防线：外置已有数据而待复制的库是空库，放弃本轮
        if (ext.isFile && countRows(internal) == 0 && countRows(ext) > 0) {
            Log.i(TAG, "活库为空、外置备份非空，跳过备份以免覆盖")
            return
        }
        backing = true
        try {
            // 外置只保存单一主库：先清掉历史残留的 wal/shm，避免恢复时回放旧数据
            File(ext.absolutePath + "-wal").delete()
            File(ext.absolutePath + "-shm").delete()
            // WAL 合入主库；busy（有并发读写）则跳过本轮，下次写后再试
            runCatching {
                SQLiteDatabase.openDatabase(
                    internal.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
                ).use { db ->
                    db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                }
            }
            val ok = StorageRoots.copyFile(internal, ext, overwrite = true)
            if (ok) Log.i(TAG, "剪贴板库已备份到外置（${internal.length()}B）")
        } catch (e: Throwable) {
            Log.w(TAG, "剪贴板备份失败: ${e.message}")
        } finally {
            backing = false
        }
    }

    private fun countRows(dbFile: File): Int {
        SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            db.rawQuery("select count(*) from $TABLE", null).use { c ->
                return if (c.moveToFirst()) c.getInt(0) else 0
            }
        }
    }

    private fun readEntries(dbFile: File): List<ClipboardEntry> {
        val out = ArrayList<ClipboardEntry>()
        SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            db.rawQuery(
                "select id, text, timestamp, isPinned, isQuickSend, consumed from $TABLE",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        ClipboardEntry(
                            id = c.getLong(c.getColumnIndexOrThrow("id")),
                            text = c.getString(c.getColumnIndexOrThrow("text")),
                            timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp")),
                            isPinned = c.getInt(c.getColumnIndexOrThrow("isPinned")) != 0,
                            isQuickSend = c.getInt(c.getColumnIndexOrThrow("isQuickSend")) != 0,
                            consumed = c.getInt(c.getColumnIndexOrThrow("consumed")) != 0
                        )
                    )
                }
            }
        }
        return out
    }
}
