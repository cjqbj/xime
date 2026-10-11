package com.kingzcheung.xime.association

import java.io.File
import java.util.LinkedHashMap
import java.util.Locale

class EnglishWordHistory(
    private val maxEntries: Int = 500,
) {
    private data class Entry(
        val word: String,
        val frequency: Int,
        val recency: Long,
    )

    private val lock = Any()
    private val words = LinkedHashMap<String, Entry>()
    private var nextRecency = 0L
    private var storageFile: File? = null

    fun load(file: File) {
        val loaded = LinkedHashMap<String, Entry>()
        if (file.exists()) {
            file.readLines(Charsets.UTF_8).forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                val parts = line.split('\t', limit = 2)
                require(parts.size == 2 && parts[1].isNotBlank()) {
                    "Invalid English history entry at line ${index + 1}"
                }
                val frequency = parts[0].toInt()
                require(frequency > 0 && parts[1].all { it.code in 0x20..0x7e }) {
                    "Invalid English history entry at line ${index + 1}"
                }
                val key = parts[1].lowercase(Locale.ROOT)
                loaded.remove(key)
                loaded[key] = Entry(parts[1], frequency, index.toLong())
            }
        }
        synchronized(lock) {
            words.clear()
            loaded.entries.toList().takeLast(maxEntries).forEach { (key, value) -> words[key] = value }
            nextRecency = (loaded.size + 1).toLong()
            storageFile = file
        }
    }

    fun recordText(text: String): Boolean {
        val matches = ENGLISH_WORD.findAll(text).map { it.value }.toList()
        if (matches.isEmpty()) return false

        synchronized(lock) {
            matches.forEach { word ->
                val key = word.lowercase(Locale.ROOT)
                val frequency = (words[key]?.frequency ?: 0) + 1
                words.remove(key)
                words[key] = Entry(word, frequency, nextRecency++)
            }
            while (words.size > maxEntries) {
                words.remove(words.keys.first())
            }
        }
        return true
    }

    fun suggest(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        val normalizedPrefix = prefix.lowercase(Locale.ROOT)
        return synchronized(lock) {
            words.values
                .asSequence()
                .filter { it.word.lowercase(Locale.ROOT).startsWith(normalizedPrefix) }
                .sortedWith(compareByDescending<Entry> { it.frequency }.thenByDescending { it.recency })
                .take(limit)
                .map { it.word }
                .toList()
        }
    }

    fun save() {
        val file = synchronized(lock) { storageFile } ?: return
        val snapshot = synchronized(lock) {
            words.values.map { "${it.frequency}\t${it.word}" }
        }
        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IllegalStateException("Unable to create English history directory: $parent")
            }
        }
        file.writeText(snapshot.joinToString(separator = "\n", postfix = if (snapshot.isEmpty()) "" else "\n"), Charsets.UTF_8)
    }

    companion object {
        private val ENGLISH_WORD = Regex("[A-Za-z][A-Za-z0-9]*(?:['-][A-Za-z0-9]+)*")

        fun hasEnglishWords(text: String): Boolean = ENGLISH_WORD.containsMatchIn(text)
    }
}
