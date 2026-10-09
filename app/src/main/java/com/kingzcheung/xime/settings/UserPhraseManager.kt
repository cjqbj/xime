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
 * 用户候选词的"学习 + 管理"统一入口，分两条独立链路：
 *
 * 1) 自动学习词（回车原始上屏的英文串，[learnRawCommit]）：
 *    由本管理器在内存中直管（[getAutoPhrases]/add/delete/moveToEnd），
 *    输入时以前缀匹配直接注入候选栏前部，点击直接上屏。
 *    独立持久化到 app filesDir（auto_phrase/<schema>.txt），rime 完全不加载，
 *    因此增删改立即生效，且绝不销毁/重建 rime 会话、不持有引擎全局锁
 *    （根除重建会话导致的卡死、丢候选、第二次无候选）。
 *
 * 2) 手动 custom_phrase（设置页维护，db_class=stabledb）：
 *    仍由 rime table_translator 在会话启动时一次性载入，手动删除/沉底后
 *    需要重建会话才生效（低频操作，沿用防抖重建）。
 */
object UserPhraseManager {
    private const val TAG = "UserPhraseManager"
    /** 自动学习的最小长度，避免 a、ok 这类短串污染词库。 */
    private const val MIN_AUTO_LEARN_LEN = 3
    /** 沉底权重：table_translator 按 weight 排序，统一极小负值即可稳定排到同码末尾。 */
    private const val BOTTOM_WEIGHT = -100000
    private const val DEPLOY_DEBOUNCE_MS = 2000L
    /** 每段（前置/沉底）最多注入候选栏的自动词数量。 */
    private const val MAX_INJECT_COUNT = 8
    /** 沉底分值：低于此分（负分）的词注入到 rime 候选之后；沉底动作直接置为此值。 */
    private const val DEMOTED_SCORE = -5L
    /** 每点击上屏一次自动词，其分值加一，逐渐提升排序，沉底词连选 |DEMOTED_SCORE| 次重回前排。 */
    private const val SELECT_BUMP = 1L

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileMutex = Mutex()
    @Volatile private var deployScheduled = false

    // ── 自动学习词：内存直管，独立存储，不经过 rime 会话 ──
    /** 自动词条目：word + 分值。分值 >=0 的词前缀匹配时前置注入；负分词追加到 rime 候选之后。 */
    private data class AutoEntry(val word: String, val score: Long)

    /** 前缀匹配结果：[front] 为正常词（置候选栏前部），[tail] 为沉底词（置 rime 候选之后）。 */
    class AutoMatch(val front: List<String>, val tail: List<String>)

    /** schemaId -> 词条（始终按分值降序、同分稳定保序）；所有读写在 object 监视器内同步。 */
    private val autoCache = HashMap<String, List<AutoEntry>>()
    private val autoSaveMutex = Mutex()

    /**
     * 自定义词磁盘内容修订号：学习 / 删除 / 沉底写盘成功后（在 [fileMutex] 内）递增。
     * 候选栏观察此值并重新加载词条集合，从而即时刷新长按菜单的可用性。
     * 用 Compose state 承载，可在任意线程写、在组合中读。
     */
    val phraseRevision = mutableLongStateOf(0L)
    private fun bumpPhraseRevision() { phraseRevision.longValue += 1L }

    /** 当前方案可长按管理的全部词条：手动 custom_phrase + 自动学习词。 */
    fun loadWords(context: Context, schemaId: String?): Set<String> {
        if (schemaId.isNullOrBlank()) return emptySet()
        return try {
            val manual = PersonalDictManager.loadCustomPhrases(context, schemaId).map { it.word }
            (manual + getAutoPhrases(context, schemaId)).toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * 回车未选词、原始上屏时学习该串。
     * 仅学习以字母为主、长度达标的串；直接写入内存并异步落盘，立即生效，不重建 rime 会话。
     * 返回 true 表示受理了学习。
     */
    fun learnRawCommit(context: Context, schemaId: String?, raw: String): Boolean {
        val word = raw.trim()
        if (schemaId.isNullOrBlank() || word.length < MIN_AUTO_LEARN_LEN) return false
        // 以英文字母/数字为主的串才学习（允许内部 - _ . 等连接符，但首字符须是字母）
        if (!word.first().isLetter() || word.count { it.isLetterOrDigit() } < word.length * 0.6f) return false

        addAutoPhrase(context, schemaId, word)
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

    // ── 自动学习词内存操作（即时，不碰 rime 会话/全局锁）──

    /** 解析一行磁盘记录：兼容新格式 `word<TAB>score` 与旧格式（裸词按 0 分）。 */
    private fun parseAutoEntry(line: String): AutoEntry {
        val tab = line.lastIndexOf('\t')
        if (tab > 0) {
            line.substring(tab + 1).toLongOrNull()?.let { return AutoEntry(line.substring(0, tab), it) }
        }
        return AutoEntry(line, 0L)
    }

    /** 按分值降序重排（稳定排序，同分保持既有先后）。 */
    private fun List<AutoEntry>.resorted(): List<AutoEntry> = sortedByDescending { it.score }

    /** 读取当前方案的自动词条目（分值降序），首次访问从磁盘载入内存。 */
    @Synchronized
    private fun getEntries(context: Context, schemaId: String): List<AutoEntry> {
        if (schemaId.isBlank()) return emptyList()
        autoCache[schemaId]?.let { return it }
        val loaded = PersonalDictManager.loadAutoPhrases(context.applicationContext, schemaId)
            .map { parseAutoEntry(it) }
            .resorted()
        autoCache[schemaId] = loaded
        return loaded
    }

    /** 读取当前方案的自动学习词（分值降序），首次访问从磁盘载入内存。 */
    @Synchronized
    fun getAutoPhrases(context: Context, schemaId: String): List<String> {
        if (schemaId.isBlank()) return emptyList()
        return getEntries(context, schemaId).map { it.word }
    }

    /** 新增自动词：已存在则不重复；新词取当前最高分 +1（立即排到最前），内存更新并异步落盘。 */
    @Synchronized
    fun addAutoPhrase(context: Context, schemaId: String, word: String) {
        val cur = getEntries(context, schemaId)
        if (cur.any { it.word == word }) return
        val topScore = (cur.maxOfOrNull { it.score } ?: 0L).coerceAtLeast(0L) + 1L
        autoCache[schemaId] = (cur + AutoEntry(word, topScore)).resorted()
        bumpPhraseRevision()
        persistAutoAsync(context, schemaId)
        FileLogger.i(TAG, "learned auto phrase: $word score=$topScore (schema=$schemaId)")
    }

    /** 删除自动词：内存立即移除并异步落盘。 */
    @Synchronized
    fun deleteAutoWord(context: Context, schemaId: String, word: String) {
        val cur = getEntries(context, schemaId)
        if (cur.none { it.word == word }) return
        autoCache[schemaId] = cur.filterNot { it.word == word }
        bumpPhraseRevision()
        persistAutoAsync(context, schemaId)
    }

    /**
     * 沉底自动词：分值置为 [DEMOTED_SCORE]（负分），候选栏立即把它追加到 rime 候选之后。
     * 以后点击该词每次加 [SELECT_BUMP]，分值回到 0 即重新前置注入（多次使用逐步提升）。
     */
    @Synchronized
    fun moveAutoWordToEnd(context: Context, schemaId: String, word: String) {
        val cur = getEntries(context, schemaId)
        val idx = cur.indexOfFirst { it.word == word }
        if (idx < 0 || cur[idx].score == DEMOTED_SCORE) return
        val next = cur.toMutableList().also { it[idx] = AutoEntry(word, DEMOTED_SCORE) }.resorted()
        autoCache[schemaId] = next
        bumpPhraseRevision()
        persistAutoAsync(context, schemaId)
    }

    /** 点击上屏自动词后调用：分值 +[SELECT_BUMP] 并重排（落盘异步），使常用词逐步靠前。 */
    @Synchronized
    fun bumpAutoWordOnPick(context: Context, schemaId: String, word: String) {
        val cur = getEntries(context, schemaId)
        val idx = cur.indexOfFirst { it.word == word }
        if (idx < 0) return
        val next = cur.toMutableList().also { it[idx] = AutoEntry(word, it[idx].score + SELECT_BUMP) }.resorted()
        autoCache[schemaId] = next
        persistAutoAsync(context, schemaId)
    }

    /** 判断某词是否为自动学习词（供长按动作路由）。 */
    @Synchronized
    fun isAutoWord(context: Context, schemaId: String, word: String): Boolean =
        getEntries(context, schemaId).any { it.word == word }

    /**
     * 前缀匹配：
     * - front：分值 >= 0 的词，按分值降序，注入候选栏前部；
     * - tail：负分（沉底）词，按分值升序（最沉的排最后），追加到 rime 候选之后。
     */
    @Synchronized
    fun matchAutoPhrases(context: Context, schemaId: String, input: String): AutoMatch {
        val key = input.trim().lowercase()
        if (key.isEmpty()) return AutoMatch(emptyList(), emptyList())
        val matched = getEntries(context, schemaId).filter { it.word.lowercase().startsWith(key) }
        val front = matched.filter { it.score >= 0L }.take(MAX_INJECT_COUNT).map { it.word }
        // 缓存按降序存储，负分段反转即得升序（-1 在前、DEMOTED_SCORE 在最后）。
        val tail = matched.filter { it.score < 0L }.asReversed().take(MAX_INJECT_COUNT).map { it.word }
        return AutoMatch(front, tail)
    }

    /** 异步落盘：锁内保存内存中的最新快照（word<TAB>score 每行），保证快速连续修改后最终一致。 */
    private fun persistAutoAsync(context: Context, schemaId: String) {
        val app = context.applicationContext
        ioScope.launch {
            autoSaveMutex.withLock {
                val snapshot = synchronized(this@UserPhraseManager) {
                    autoCache[schemaId].orEmpty().map { "${it.word}\t${it.score}" }
                }
                PersonalDictManager.saveAutoPhrases(app, schemaId, snapshot)
            }
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
