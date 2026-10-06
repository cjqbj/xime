package com.kingzcheung.xime.storage

import android.content.Context
import android.util.Xml
import android.util.Log
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * SharedPreferences（kime_settings.xml）的外置镜像与恢复。
 *
 * 两条恢复路径：
 *  1. [reconcile]：Application.onCreate 最早期（任何业务读 prefs 前）。
 *     外置镜像存在 → 直接覆盖内置 XML 文件（此刻进程尚未加载 prefs，覆盖有效）；
 *     外置缺失 → 内置复制留种。
 *  2. [onExternalStorageAvailable]：用户重装后先启动了 App、之后才在系统设置里授予
 *     「所有文件访问」。此时 prefs 已被进程加载，覆盖文件无效，需要解析外置 XML，
 *     把每个键按原始类型写回活的 SharedPreferences。
 * 之后注册 prefs 变更监听，变更去抖 1.5s 自动导出。
 *
 * 说明：SharedPreferences 的落盘路径无法重定向到 /sdcard，因此采用
 * 「内置为活文件 + 外置 XML 镜像」，直接复制/解析 XML 能完整保留类型信息。
 */
object SettingsMirror {
    private const val TAG = "SettingsMirror"
    private const val PREFS_DIR = "prefs"
    private const val SETTINGS_XML = "kime_settings.xml"
    private const val EXPORT_DEBOUNCE_MS = 1500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var exportJob: Job? = null
    private var appContext: Context? = null
    @Volatile private var listenerRegistered = false

    fun reconcile(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val internalXml = File(ctx.applicationInfo.dataDir, "shared_prefs/$SETTINGS_XML")
        val extDir = StorageRoots.ensureExternalDir(ctx, PREFS_DIR)
        if (extDir == null) {
            // 外置尚不可用：先不注册监听，授权后由 onExternalStorageAvailable 补注册
            return
        }
        val extXml = File(extDir, SETTINGS_XML)

        if (extXml.isFile) {
            // 外置为准：直接覆盖内置文件（此刻进程内尚未 getSharedPreferences 该文件）
            val copied = StorageRoots.copyFile(extXml, internalXml, overwrite = true)
            Log.i(TAG, "外置镜像存在，恢复设置到内置: $copied")
        } else if (internalXml.isFile) {
            StorageRoots.copyFile(internalXml, extXml, overwrite = false)
            Log.i(TAG, "外置镜像缺失，已把内置设置复制到外置留种")
        }

        registerListener(ctx)
    }

    /**
     * 外置存储在运行期间变为可用（用户刚授予所有文件访问）时调用。
     * 外置镜像存在 → 外置为准，把内容按类型写回当前已加载的活 prefs。
     */
    fun onExternalStorageAvailable(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val internalXml = File(ctx.applicationInfo.dataDir, "shared_prefs/$SETTINGS_XML")
        val extDir = StorageRoots.ensureExternalDir(ctx, PREFS_DIR) ?: return
        val extXml = File(extDir, SETTINGS_XML)

        if (extXml.isFile) {
            val values = runCatching { parsePrefsXml(extXml) }
                .onFailure { Log.w(TAG, "解析外置设置 XML 失败: ${it.message}") }
                .getOrDefault(emptyMap())
            if (values.isNotEmpty()) {
                val prefs = SettingsPreferences.getPrefsPublic(ctx)
                val editor = prefs.edit()
                values.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is String -> editor.putString(key, value)
                        is Set<*> -> @Suppress("UNCHECKED_CAST")
                            editor.putStringSet(key, value as Set<String>)
                    }
                }
                editor.apply()
                Log.i(TAG, "授权后从外置镜像恢复 ${values.size} 个设置项")
            }
            // 磁盘文件也对齐（apply 异步落盘前的兜底，内容相同）
            StorageRoots.copyFile(extXml, internalXml, overwrite = true)
        } else if (internalXml.isFile) {
            StorageRoots.copyFile(internalXml, extXml, overwrite = false)
            Log.i(TAG, "授权后外置镜像缺失，已把内置设置复制到外置留种")
        }

        registerListener(ctx)
    }

    private fun registerListener(ctx: Context) {
        if (listenerRegistered) return
        listenerRegistered = runCatching {
            SettingsPreferences.getPrefsPublic(ctx)
                .registerOnSharedPreferenceChangeListener { _, _ -> scheduleExport() }
            true
        }.onFailure { Log.w(TAG, "注册设置监听失败: ${it.message}") }.getOrDefault(false)
    }

    /**
     * 解析 Android SharedPreferences 导出的 XML。
     * 支持 boolean/int/long/float/string 与 string-set。
     */
    private fun parsePrefsXml(file: File): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        val parser = Xml.newPullParser()
        parser.setInput(file.inputStream().buffered(), "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val name = parser.name
                val key = parser.getAttributeValue(null, "name")
                when (name) {
                    "boolean", "int", "long", "float" -> {
                        val raw = parser.getAttributeValue(null, "value")
                        if (key != null && raw != null) {
                            val v: Any? = when (name) {
                                "boolean" -> raw.toBooleanStrictOrNull()
                                "int" -> raw.toIntOrNull()
                                "long" -> raw.toLongOrNull()
                                "float" -> raw.toFloatOrNull()
                                else -> null
                            }
                            if (v != null) out[key] = v
                        }
                    }
                    "string" -> if (key != null) {
                        out[key] = if (parser.next() == XmlPullParser.TEXT) parser.text else ""
                    }
                    "set" -> if (key != null) {
                        val items = LinkedHashSet<String>()
                        var inner = parser.nextTag()
                        // nextTag 停在 set 内第一个子标签；循环到 set 的 END_TAG
                        while (!(inner == XmlPullParser.END_TAG && parser.name == "set")) {
                            if (inner == XmlPullParser.START_TAG && parser.name == "string") {
                                if (parser.next() == XmlPullParser.TEXT) items.add(parser.text)
                            }
                            inner = parser.nextTag()
                        }
                        out[key] = items
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    /** 变更后去抖导出，避免连续滑动调节时频繁拷贝。 */
    private fun scheduleExport() {
        exportJob?.cancel()
        exportJob = scope.launch {
            delay(EXPORT_DEBOUNCE_MS) // 等 apply() 的异步落盘完成
            appContext?.let { exportNow(it) }
        }
    }

    /** 立即把内置 XML 镜像到外置（外置不可用时静默跳过）。 */
    fun exportNow(context: Context) {
        val ctx = context.applicationContext
        val extDir = StorageRoots.ensureExternalDir(ctx, PREFS_DIR) ?: return
        val internalXml = File(ctx.applicationInfo.dataDir, "shared_prefs/$SETTINGS_XML")
        if (internalXml.isFile) {
            StorageRoots.copyFile(internalXml, File(extDir, SETTINGS_XML), overwrite = true)
        }
    }
}
