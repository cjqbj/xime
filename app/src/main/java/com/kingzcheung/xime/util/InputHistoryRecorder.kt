package com.kingzcheung.xime.util

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object InputHistoryRecorder {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "InputHistoryRecorder").apply { isDaemon = true }
    }
    private val monthFormat = SimpleDateFormat("yyyyMM", Locale.US)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    fun record(context: Context, key: String, shifted: Boolean) {
        val timestamp = Date()
        val entry = JSONObject()
            .put("time", timeFormat.format(timestamp))
            .put("key", key)
            .put("shifted", shifted)
            .toString() + "\n"
        executor.execute {
            runCatching {
                val dir = File(Environment.getExternalStorageDirectory(), "Alarms/input-history")
                if (!dir.exists()) dir.mkdirs()
                File(dir, "${monthFormat.format(timestamp)}.jsonl")
                    .appendText(entry, Charsets.UTF_8)
            }
        }
    }
}