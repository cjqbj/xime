package com.kingzcheung.xime.util

import android.content.Context
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object RpcUiController {
    private const val MAX_LOG_CHARS = 100 * 1024
    private const val MAX_LOG_LINES = 1000
    private const val MEMORY_LOG_KEY = "log.memory"

    /**
     * 活动 IME 提供的"输入/候选"直达测试桥。
     * 由 XimeInputMethodService 在创建时挂载、销毁时摘除；RPC 据此直接驱动
     * 真实输入管线（设置编码/敲单键/点候选/读状态），无需 adb 模拟点击。
     */
    interface ImeTestBridge {
        /** 设置整段编码（setInput + 走生产映射刷新候选栏），返回状态 JSON。 */
        fun type(code: String): String
        /** 真实敲入一个字符（processKey 单键管线），返回状态 JSON。 */
        fun pressKey(letter: String): String
        /** 点击第 index 个候选（走 selectCandidateAsync 生产路径），返回 {before,after}。 */
        fun pick(index: Int): String
        /** 清空组合与候选栏，返回状态 JSON。 */
        fun reset(): String
        /** 只读当前完整输入/候选状态 JSON。 */
        fun snapshot(): String
    }

    private var imeBridge: ImeTestBridge? = null

    /** 供活动 IME 挂载/摘除测试桥。 */
    fun attachIme(bridge: ImeTestBridge) { imeBridge = bridge }
    fun detachIme(bridge: ImeTestBridge) { if (imeBridge === bridge) imeBridge = null }

    private var context: Context? = null
    private val _state = MutableStateFlow<Map<String, String>>(emptyMap())
    val state: StateFlow<Map<String, String>> = _state.asStateFlow()

    fun initialize(appContext: Context) {
        context = appContext.applicationContext
        if (_state.value[MEMORY_LOG_KEY].isNullOrBlank()) {
            _state.value = _state.value + (MEMORY_LOG_KEY to "")
        }
    }

    @JvmStatic
    fun setState(key: String, value: String) {
        require(key.isNotBlank()) { "UI state key must not be blank" }
        _state.update { it + (key to value) }
    }

    @JvmStatic
    fun appendLog(message: String) {
        if (message.isBlank()) return
        _state.update { current ->
            val previous = current[MEMORY_LOG_KEY].orEmpty()
            val combined = (previous + message)
            val lines = combined.split('\n')
            val trimmed = if (lines.size > MAX_LOG_LINES) {
                lines.takeLast(MAX_LOG_LINES)
            } else {
                lines
            }.joinToString("\n")
            val bounded = if (trimmed.length > MAX_LOG_CHARS) trimmed.takeLast(MAX_LOG_CHARS) else trimmed
            current + (MEMORY_LOG_KEY to bounded)
        }
    }

    @JvmStatic
    fun clearLog() {
        _state.update { it + (MEMORY_LOG_KEY to "") }
    }

    @JvmStatic
    fun removeState(key: String) {
        _state.update { it - key }
    }

    @JvmStatic
    fun getState(key: String): String? = _state.value[key]

    @JvmStatic
    fun clearState() {
        _state.value = emptyMap()
    }

    @JvmStatic
    fun listSchemas(): String {
        val appContext = context ?: return JSONObject().put("error", "controller_not_initialized").toString()
        val enabled = SchemaManager.getEnabledSchemas(appContext).toSet()
        val current = SettingsPreferences.getCurrentSchema(appContext)
        val schemas = JSONArray()
        SchemaManager.discoverSchemas(appContext).forEach { schema ->
            schemas.put(JSONObject()
                .put("id", schema.schemaId)
                .put("name", schema.name)
                .put("version", schema.version)
                .put("author", schema.author)
                .put("description", schema.description)
                .put("enabled", schema.schemaId in enabled)
                .put("current", schema.schemaId == current))
        }
        return JSONObject()
            .put("current", current)
            .put("enabled", JSONArray(enabled.toList()))
            .put("schemas", schemas)
            .toString()
    }

    @JvmStatic
    fun setOnlySchema(schemaId: String): String {
        val appContext = context ?: return errorResult("controller_not_initialized")
        val schema = SchemaManager.discoverSchemas(appContext)
            .firstOrNull { it.schemaId == schemaId }
            ?: return errorResult("schema_not_found:$schemaId")

        SchemaManager.setEnabledSchemas(appContext, listOf(schema.schemaId))
        SettingsPreferences.setCurrentSchema(appContext, schema.schemaId)

        val rime = if (RimeEngine.isInitialized()) RimeEngine.getInstance() else null
        val switched = rime?.let { engine ->
            schema.schemaId in engine.getAvailableSchemas() && engine.switchSchema(schema.schemaId)
        } ?: false

        return JSONObject()
            .put("ok", true)
            .put("id", schema.schemaId)
            .put("name", schema.name)
            .put("switched", switched)
            .put("requires_deploy", !switched)
            .toString()
    }

    @JvmStatic
    fun listUserDicts(): String {
        val appContext = context ?: return errorResult("controller_not_initialized")
        val names = File(appContext.filesDir, "rime")
            .listFiles { file -> file.exists() && file.name.endsWith(".userdb") }
            ?.map { it.name.removeSuffix(".userdb") }
            ?.sorted()
            ?: emptyList()
        return JSONObject()
            .put("ok", true)
            .put("dicts", JSONArray(names))
            .toString()
    }

    @JvmStatic
    fun readUserDict(dictName: String): String {
        val appContext = context ?: return errorResult("controller_not_initialized")
        if (!RimeEngine.isInitialized()) return errorResult("rime_not_initialized")
        if (!dictName.matches(Regex("[A-Za-z0-9_.-]+"))) {
            return errorResult("invalid_dict_name")
        }

        val rimeDir = File(appContext.filesDir, "rime")
        val source = File(rimeDir, "$dictName.userdb")
        if (!source.exists()) return errorResult("user_dict_not_found:$dictName")

        val exported = File.createTempFile("rpc-$dictName-", ".userdb.txt", appContext.cacheDir)
        return try {
            val count = RimeEngine.getInstance().exportUserDict(dictName, exported.absolutePath)
            if (count < 0 || !exported.isFile) return errorResult("user_dict_export_failed:$dictName")
            JSONObject()
                .put("ok", true)
                .put("dict", dictName)
                .put("count", count)
                .put("tsv", exported.readText(Charsets.UTF_8))
                .toString()
        } finally {
            exported.delete()
        }
    }

    @JvmStatic
    fun readAllUserDicts(): String {
        val listed = JSONObject(listUserDicts())
        if (!listed.optBoolean("ok", false)) return listed.toString()
        val result = JSONArray()
        val dicts = listed.optJSONArray("dicts") ?: JSONArray()
        for (index in 0 until dicts.length()) {
            result.put(JSONObject(readUserDict(dicts.getString(index))))
        }
        return JSONObject()
            .put("ok", true)
            .put("dicts", result)
            .toString()
    }

    /** 将外部备份的原始 .userdb 文件恢复到 Rime 用户词典目录。重启 Rime 后生效。 */
    @JvmStatic
    fun importUserDict(dictName: String, backupPath: String): String {
        val appContext = context ?: return errorResult("controller_not_initialized")
        if (!dictName.matches(Regex("[A-Za-z0-9_.-]+"))) {
            return errorResult("invalid_dict_name")
        }
        val source = File(backupPath)
        if (!source.isFile) return errorResult("backup_not_found:$backupPath")
        if (source.length() == 0L) return errorResult("backup_empty:$dictName")

        return try {
            val targetDir = File(appContext.filesDir, "rime")
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return errorResult("rime_dir_create_failed")
            }
            val target = File(targetDir, "$dictName.userdb")
            source.copyTo(target, overwrite = true)
            JSONObject()
                .put("ok", true)
                .put("dict", dictName)
                .put("path", target.absolutePath)
                .put("bytes", target.length())
                .put("restart_required", true)
                .toString()
        } catch (error: Exception) {
            errorResult("user_dict_import_failed:${error.message}")
        }
    }

    private fun errorResult(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()

    // ── 输入/候选直达测试入口（经 Chaquopy RPC 调用，无 IME 时返回错误）──
    private fun imeUnavailable(): String = errorResult("ime_not_active")

    /** RPC：直接设置编码并刷新候选，如 RpcUiController.imeType('hgi')。 */
    @JvmStatic
    fun imeType(code: String): String = imeBridge?.type(code) ?: imeUnavailable()

    /** RPC：真实敲入一个字符，如 RpcUiController.imeKey('h')。 */
    @JvmStatic
    fun imeKey(letter: String): String {
        if (letter.isEmpty()) return errorResult("empty_letter")
        return imeBridge?.pressKey(letter) ?: imeUnavailable()
    }

    /** RPC：点击候选（索引即候选栏顺序），如 RpcUiController.imePick(5)，返回前后状态。 */
    @JvmStatic
    fun imePick(index: Int): String = imeBridge?.pick(index) ?: imeUnavailable()

    /** RPC：清空组合与候选栏。 */
    @JvmStatic
    fun imeReset(): String = imeBridge?.reset() ?: imeUnavailable()

    /** RPC：读当前完整输入/候选状态。 */
    @JvmStatic
    fun imeState(): String = imeBridge?.snapshot() ?: imeUnavailable()
}