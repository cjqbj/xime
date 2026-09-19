package com.kingzcheung.xime.clipboard.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardDao {

    @Query("SELECT * FROM clipboard_entries WHERE isQuickSend = 0 ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<ClipboardEntry>>

    @Query("SELECT * FROM clipboard_entries WHERE isQuickSend = 1 ORDER BY timestamp DESC")
    fun observeQuickSend(): Flow<List<ClipboardEntry>>

    @Query("SELECT * FROM clipboard_entries WHERE text = :text AND isQuickSend = 0 LIMIT 1")
    suspend fun findByText(text: String): ClipboardEntry?

    @Query("SELECT * FROM clipboard_entries WHERE text = :text AND isQuickSend = 1 LIMIT 1")
    suspend fun findQuickSendByText(text: String): ClipboardEntry?

    @Query("SELECT * FROM clipboard_entries WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ClipboardEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ClipboardEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<ClipboardEntry>)

    @Query("UPDATE clipboard_entries SET timestamp = :timestamp WHERE id = :id")
    suspend fun updateTimestamp(id: Long, timestamp: Long)

    @Query("DELETE FROM clipboard_entries WHERE isQuickSend = 0 AND id = :id")
    suspend fun deleteClipboardById(id: Long)

    @Query("DELETE FROM clipboard_entries WHERE isQuickSend = 1 AND id = :id")
    suspend fun deleteQuickSendById(id: Long)

    @Query("DELETE FROM clipboard_entries WHERE isQuickSend = 0 AND id IN (:ids)")
    suspend fun deleteClipboardByIds(ids: List<Long>)

    @Query("DELETE FROM clipboard_entries WHERE isQuickSend = 0")
    suspend fun clearAllClipboard()

    @Query("DELETE FROM clipboard_entries WHERE isPinned = 0")
    suspend fun clearUnpinned()

    @Query("SELECT COUNT(*) FROM clipboard_entries WHERE isPinned = 0")
    suspend fun countUnpinned(): Int

    @Query("DELETE FROM clipboard_entries WHERE isPinned = 0 AND id IN (SELECT id FROM clipboard_entries WHERE isPinned = 0 ORDER BY timestamp ASC LIMIT :limit)")
    suspend fun trimUnpinned(limit: Int)

    @Query("DELETE FROM clipboard_entries WHERE isQuickSend = 1 AND id IN (SELECT id FROM clipboard_entries WHERE isQuickSend = 1 ORDER BY timestamp ASC LIMIT :limit)")
    suspend fun trimQuickSend(limit: Int)

    @Query("UPDATE clipboard_entries SET text = :text, timestamp = :now WHERE id = :id")
    suspend fun updateText(id: Long, text: String, now: Long)

    @Query("UPDATE clipboard_entries SET consumed = 1 WHERE id = :id")
    suspend fun markConsumed(id: Long)

    @Query("DELETE FROM clipboard_entries")
    suspend fun deleteAll()

    @Transaction
    suspend fun upsertAndTrim(text: String, now: Long, maxItems: Int) {
        val existing = findByText(text)
        if (existing != null) {
            updateTimestamp(existing.id, now)
        } else {
            insert(ClipboardEntry(text = text, timestamp = now))
            val unpinned = countUnpinned()
            if (unpinned > maxItems) {
                trimUnpinned(unpinned - maxItems)
            }
        }
    }

    @Transaction
    suspend fun addQuickSend(sourceId: Long, now: Long, maxQuickSend: Int) {
        val source = findById(sourceId) ?: return
        val existing = findQuickSendByText(source.text)
        if (existing != null) {
            updateTimestamp(existing.id, now)
        } else {
            insert(
                ClipboardEntry(
                    text = source.text,
                    timestamp = now,
                    isPinned = true,
                    isQuickSend = true
                )
            )
        }
        val count = countQuickSend()
        if (count > maxQuickSend) {
            trimQuickSend(count - maxQuickSend)
        }
    }

    @Query("SELECT COUNT(*) FROM clipboard_entries WHERE isQuickSend = 1")
    suspend fun countQuickSend(): Int

    @Transaction
    suspend fun insertQuickSend(text: String, now: Long, maxQuickSend: Int) {
        val existing = findQuickSendByText(text)
        if (existing != null) {
            updateTimestamp(existing.id, now)
        } else {
            insert(
                ClipboardEntry(
                    text = text,
                    timestamp = now,
                    isPinned = true,
                    isQuickSend = true
                )
            )
        }
        val count = countQuickSend()
        if (count > maxQuickSend) {
            trimQuickSend(count - maxQuickSend)
        }
    }
}
