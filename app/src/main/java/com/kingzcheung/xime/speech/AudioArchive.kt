package com.kingzcheung.xime.speech

import android.os.Environment
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class AudioArchive private constructor(
    private val file: File,
    private val startedAt: Date,
    private val output: RandomAccessFile
) {
    private var dataSize = 0L
    private var closed = false

    @Synchronized
    fun write(pcm: ByteArray, length: Int) {
        if (closed || length <= 0) return
        output.write(pcm, 0, length)
        dataSize += length
    }

    @Synchronized
    fun close(): File? {
        if (closed) return file.takeIf { it.exists() }
        closed = true
        return runCatching {
            output.seek(0)
            writeAscii("RIFF")
            writeIntLE((36 + dataSize).toInt())
            writeAscii("WAVEfmt ")
            writeIntLE(16)
            writeShortLE(1)
            writeShortLE(1)
            writeIntLE(SAMPLE_RATE)
            writeIntLE(SAMPLE_RATE * 2)
            writeShortLE(2)
            writeShortLE(16)
            writeAscii("data")
            writeIntLE(dataSize.toInt())
            output.close()
            file
        }.getOrNull()
    }

    fun renameWithText(text: String?): File? {
        val source = close() ?: return null
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(startedAt)
        val label = text?.trim()?.replace(Regex("[\\/:*?\"<>|\\r\\n]+"), " ")?.trim()
        val base = label?.takeIf { it.isNotEmpty() && !it.startsWith("错误:") }
            ?.take(80)
            ?: "未识别_$timestamp"
        val target = uniqueFile(source.parentFile ?: return source, "$base.wav")
        return if (source.renameTo(target)) target else source
    }

    private fun writeAscii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
    private fun writeIntLE(value: Int) {
        output.write(value and 0xff)
        output.write(value shr 8 and 0xff)
        output.write(value shr 16 and 0xff)
        output.write(value shr 24 and 0xff)
    }

    private fun writeShortLE(value: Int) {
        output.write(value and 0xff)
        output.write(value shr 8 and 0xff)
    }

    companion object {
        private const val SAMPLE_RATE = 16000

        fun create(): AudioArchive? {
            val now = Date()
            val month = SimpleDateFormat("yyyyMM", Locale.US).format(now)
            val root = File(Environment.getExternalStorageDirectory(), "Alarms/audio/$month")
            return runCatching {
                if (!root.exists()) root.mkdirs()
                val temp = File.createTempFile(".recording-", ".wav", root)
                val output = RandomAccessFile(temp, "rw")
                output.setLength(44)
                output.seek(44)
                AudioArchive(temp, now, output)
            }.getOrNull()
        }

        private fun uniqueFile(dir: File, requested: String): File {
            val base = requested.removeSuffix(".wav")
            var result = File(dir, requested)
            var index = 2
            while (result.exists()) {
                result = File(dir, "$base-$index.wav")
                index++
            }
            return result
        }
    }
}