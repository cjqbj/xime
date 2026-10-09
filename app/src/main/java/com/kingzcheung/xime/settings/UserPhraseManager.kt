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
    /** 每次输入最多注入候选栏的自动词数量。 */
    private const val MAX_INJECT_COUNT = 8

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileMutex = Mutex()
    @Volatile private var deployScheduled = false

    // ── 自动学习词：内存直管，独立存储，不经过 rime 会话 ──
    /** schemaId -> 有序词条；所有读写在 object 监视器内同步。 */
    private val autoCache = HashMap<String, List<String>>()
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

    /** 读取当前方案的自动学习词（有序），首次访问从磁盘载入内存。 */
    @Synchronized
    fun getAutoPhrases(context: Context, schemaId: String): List<String> {
        if (schemaId.isBlank()) return emptyList()
        autoCache[schemaId]?.let { return it }
        val loaded = PersonalDictManager.loadAutoPhrases(context.applicationContext, schemaId)
        autoCache[schemaId] = loaded
        return loaded
    }

    /** 新增自动词：已存在则不重复；内存立即更新并异步落盘。 */
    @Synchronized
    fun addAutoPhrase(context: Context, schemaId: String, word: String) {
        val cur = getAutoPhrases(context, schemaId)
        if (cur.any { it == word }) return
        autoCache[schemaId] = cur + word
        bumpPhraseRevision()
        persistAutoAsync(context, schemaId)
        FileLogger.i(TAG, "learned auto phrase: $word (schema=$schemaId)")
    }

    /** 删除自动词：内存立即移除并异步落盘。 */
    @Synchronized
    fun deleteAutoWord(context: Context, schemaId: String, word: String) {
        val cur = getAutoPhrases(context, schemaId)
        if (cur.none { it == word }) return
        autoCache[schemaId] = cur.filterNot { it == word }
        bumpPhraseRevision()
        persistAutoAsync(context, schemaId)
    }

    /** 把自动词移动到有序列表末尾（真正改变顺序，候选栏即沉底）。 */
    @Synchronized
    fun moveAutoWordToEnd(context: Context, schemaId: String, word: String) {
        val cur = getAutoPhrases(context, schemaId)
        val idx = cur.indexOf(word)
        if (idx < 0 || idx == cur.lastIndex) return
        val next = cur.toMutableList().apply { removeAt(idx); add(word) }
        autoCache[schemaId] = next
        bumpPhraseRevision()
        persistAutoAsync(context, schemaId)
    }

    /** 判断某词是否为自动学习词（供长按动作路由）。 */
    @Synchronized
    fun isAutoWord(context: Context, schemaId: String, word: String): Boolean =
        getAutoPhrases(context, schemaId).any { it == word }

    /** 前缀匹配：返回自动词中以当前输入（小写）开头的词，按既有顺序。 */
    fun matchAutoPhrases(context: Context, schemaId: String, input: String): List<String> {
        val key = input.trim().lowercase()
        if (key.isEmpty()) return emptyList()
        return getAutoPhrases(context, schemaId)
            .filter { it.lowercase().startsWith(key) }
            .take(MAX_INJECT_COUNT)
    }

    /** 异步落盘：锁内保存内存中的最新快照，保证快速连续修改后最终一致。 */
    private fun persistAutoAsync(context: Context, schemaId: String) {
        val app = context.applicationContext
        ioScope.launch {
            autoSaveMutex.withLock {
                val snapshot = synchronized(this@UserPhraseManager) {
                    autoCache[schemaId].orEmpty()
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
