package com.kingzcheung.xime.settings

import android.content.Context
import androidx.compose.runtime.mutableLongStateOf
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 用户自定义候选词（custom_phrase 表）的"学习 + 管理"统一入口。
 *
 * 两类词条共用当前方案的 custom_phrase.txt：
 *  - 回车原始上屏的英文串（[learnRawCommit]），自动学习，下次敲同串字母时出现在候选栏；
 *  - 用户在设置页手动维护的自定义短语。
 *
 * custom_phrase 采用 db_class=stabledb，词条以纯文本直读、build 目录无编译产物，
 * 因此文件改动后无需重新部署，只要重建一次 Rime 会话让 translator 重新打开 txt。
 * 为避免连续学习时反复重建会话，采用"脏标记 + 停手 2 秒合并一次"的防抖策略，
 * 因此刚学的词下一次输入时才会出现，属预期行为。
 */
object UserPhraseManager {
    private const val TAG = "UserPhraseManager"
    /** 自动学习的最小长度，避免 a、ok 这类短串污染词库。 */
    private const val MIN_AUTO_LEARN_LEN = 3
    /** 沉底权重：table_translator 按 weight 排序，统一极小负值即可稳定排到同码末尾。 */
    private const val BOTTOM_WEIGHT = -100000
    /** 默认权重（学习词），initial_quality 已让 custom_phrase 整体优先，这里给 1。 */
    private const val DEFAULT_WEIGHT = 1
    private const val DEPLOY_DEBOUNCE_MS = 2000L

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileMutex = Mutex()
    @Volatile private var deployScheduled = false

    /**
     * 自定义词磁盘内容修订号：学习 / 删除 / 沉底写盘成功后（在 [fileMutex] 内）递增。
     * 候选栏观察此值并重新加载词条集合，从而即时刷新长按菜单的可用性。
     * 用 Compose state 承载，可在任意线程写、在组合中读。
     */
    val phraseRevision = mutableLongStateOf(0L)
    private fun bumpPhraseRevision() { phraseRevision.longValue += 1L }

    /** 当前方案 custom_phrase 中的全部词条文本，供候选栏判定长按菜单是否可用。 */
    fun loadWords(context: Context, schemaId: String?): Set<String> {
        if (schemaId.isNullOrBlank()) return emptySet()
        return try {
            PersonalDictManager.loadCustomPhrases(context, schemaId).map { it.word }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * 回车未选词、原始上屏时学习该串。
     * 仅学习以字母为主、长度达标的串；word 与 code 相同（敲原串即可回显该词）。
     * 返回 true 表示发生了实际写入。
     */
    fun learnRawCommit(context: Context, schemaId: String?, raw: String): Boolean {
        val word = raw.trim()
        if (schemaId.isNullOrBlank() || word.length < MIN_AUTO_LEARN_LEN) return false
        // 以英文字母/数字为主的串才学习（允许内部 - _ . 等连接符，但首字符须是字母）
        if (!word.first().isLetter() || word.count { it.isLetterOrDigit() } < word.length * 0.6f) return false

        val app = context.applicationContext
        var changed = false
        ioScope.launch {
            fileMutex.withLock {
                PersonalDictManager.ensureCustomPhraseFileExists(app, schemaId)
                val entries = PersonalDictManager.loadCustomPhrases(app, schemaId).toMutableList()
                if (entries.any { it.word == word }) return@withLock
                entries.add(DictEntry(word, word.lowercase(), DEFAULT_WEIGHT))
                PersonalDictManager.saveCustomPhrases(app, schemaId, entries)
                changed = true
                bumpPhraseRevision()
                FileLogger.i(TAG, "learned raw commit: $word (schema=$schemaId)")
            }
            if (changed) scheduleReload(app, schemaId)
        }
        return true
    }

    /** 删除一个自定义词条；找不到则忽略。 */
    fun deleteWord(context: Context, schemaId: String?, word: String, onDone: () -> Unit = {}) {
        if (schemaId.isNullOrBlank()) { onDone(); return }
        val app = context.applicationContext
        ioScope.launch {
            var changed = false
            fileMutex.withLock {
                val entries = PersonalDictManager.loadCustomPhrases(app, schemaId).toMutableList()
                changed = entries.removeAll { it.word == word }
                if (changed) {
                    PersonalDictManager.saveCustomPhrases(app, schemaId, entries)
                    bumpPhraseRevision()
                }
            }
            if (changed) scheduleReload(app, schemaId)
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    /** 把一个自定义词条的权重调到极小，使其在同码候选中稳定沉到末尾。 */
    fun moveToEnd(context: Context, schemaId: String?, word: String, onDone: () -> Unit = {}) {
        if (schemaId.isNullOrBlank()) { onDone(); return }
        val app = context.applicationContext
        ioScope.launch {
            var changed = false
            fileMutex.withLock {
                val entries = PersonalDictManager.loadCustomPhrases(app, schemaId).toMutableList()
                val idx = entries.indexOfFirst { it.word == word }
                if (idx >= 0) {
                    val e = entries[idx]
                    if (e.weight != BOTTOM_WEIGHT) {
                        entries[idx] = e.copy(weight = BOTTOM_WEIGHT)
                        PersonalDictManager.saveCustomPhrases(app, schemaId, entries)
                        changed = true
                        bumpPhraseRevision()
                    }
                }
            }
            if (changed) scheduleReload(app, schemaId)
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    /**
     * 合并重载：把多次文件改动收敛为一次 Rime 会话重建。
     * 先确保 custom_phrase 翻译器补丁已注入当前方案，再重建会话让 translator
     * 重新打开纯文本 custom_phrase.txt（stabledb 无需编译部署）。
     */
    private fun scheduleReload(app: Context, schemaId: String) {
        if (deployScheduled) return
        deployScheduled = true
        ioScope.launch {
            delay(DEPLOY_DEBOUNCE_MS)
            deployScheduled = false
            try {
                PersonalDictManager.ensureSchemaPack(app, schemaId)
                val ok = if (RimeEngine.isInitialized()) RimeEngine.getInstance().reloadStableUserDict() else false
                FileLogger.i(TAG, "custom phrase reload schema=$schemaId ok=$ok")
            } catch (e: Exception) {
                FileLogger.w(TAG, "custom phrase reload failed: ${e.message}")
            }
        }
    }
}
