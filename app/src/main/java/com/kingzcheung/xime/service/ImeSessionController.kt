package com.kingzcheung.xime.service

import com.kingzcheung.xime.keyboard.HANDWRITING_SCHEMA_ID
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.rime.buildT9DisplayState
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 输入会话组合/UI 更新与方案开关。
 *
 * 承载 Rime composition 到候选栏状态的映射（applyComposition/updateUIWithResult）、
 * 方案名称/开关刷新与切换、T9 切离提交等逻辑。共享状态通过 service 引用访问。
 */
internal class ImeSessionController(private val service: XimeInputMethodService) {
    internal fun applyComposition(composition: com.kingzcheung.xime.rime.RimeComposition) {
        val inputText = composition.input
        val codeInInputBox = SettingsPreferences.getInputTextLocation(service) == SettingsPreferences.INPUT_TEXT_INPUT_BOX
        val preeditText = composition.preedit
        val candidatesWithComments = composition.candidates.toList()
        val isAsciiMode = composition.isAsciiMode
        val hasNextPage = composition.hasNextPage
        val hasPrevPage = composition.hasPrevPage

        val pendingEnglish = service.candidateState.value.pendingEnglishText

        val (filteredTexts, filteredComments) = if (isAsciiMode) {
            // 英语模式每个按键直接上屏，不展示 Rime 候选或英语联想。
            emptyList<String>() to emptyList()
        } else {
            candidatesWithComments.map { it.text } to candidatesWithComments.map { it.comment }
        }

        val isT9Schema = isT9Schema(service.uiState.value.currentSchemaId)
        // T9 候选词过滤：根据左侧选择历史过滤不匹配的候选词
        val (t9FilteredTexts, t9FilteredComments) = if (isT9Schema) {
            service.keyboardCallbacks?.onFilterT9Candidates?.invoke(filteredTexts, filteredComments)
                ?: (filteredTexts to filteredComments)
        } else {
            filteredTexts to filteredComments
        }
        val displayText: String
        val displayCandidates: List<String>
        val displayComments: List<String>
        val isComposing: Boolean
        if (isT9Schema) {
            val rawPreedit = if (preeditText.isNotEmpty()) preeditText else inputText
            // preedit 转换由 C++ t9_filter 完成，Kotlin 侧直接使用引擎输出的 preedit
            val display = buildT9DisplayState(
                service.t9PartialSegments.map { it.text }, rawPreedit, inputText, t9FilteredTexts, t9FilteredComments
            )
            displayText = display.displayText
            displayCandidates = display.displayCandidates
            displayComments = display.displayComments
            isComposing = display.isComposing
        } else {
            // 非 T9 方案（如双拼）使用原始输入文本显示，
            // 避免显示 rime speller 展开后的编码（如双拼 vjv → zhan b）
            displayText = inputText
            displayCandidates = filteredTexts
            displayComments = filteredComments
            isComposing = inputText.isNotEmpty()
        }

        // 用户开始实际输入时，清除候选栏中残留的 inline suggestions
        if (displayText.isNotEmpty() || displayCandidates.isNotEmpty()) {
            service.dismissInlineSuggestions()
        }

        service.candidateState.value = service.candidateState.value.copy(
            inputText = displayText,
            preeditText = displayText,
            candidates = displayCandidates,
            candidateComments = displayComments,
            isComposing = isComposing,
            associationCandidates = if ((isAsciiMode || !service.isChineseMode) && pendingEnglish.isEmpty()) emptyList() else service.candidateState.value.associationCandidates,
            isShowingRecentClipboard = false,
            hasNextPage = hasNextPage,
            hasPrevPage = hasPrevPage
        )
        if (isAsciiMode != service.uiState.value.isAsciiMode) {
            FileLogger.i(XimeInputMethodService.TAG, "applyComposition: ascii ${service.uiState.value.isAsciiMode}->$isAsciiMode")
        }
        service.uiState.value = service.uiState.value.copy(isAsciiMode = isAsciiMode)

        if (!isAsciiMode && pendingEnglish.isNotEmpty()) {
            service.serviceScope.launch {
                val candidates = service.predictionManager.getEnglishAssociations(pendingEnglish, PredictionManager.MAX_ASSOCIATION_COUNT)
                withContext(Dispatchers.Main) {
                    service.candidateState.value = service.candidateState.value.copy(associationCandidates = candidates)
                }
            }
        }

        if (codeInInputBox) {
            val ic = service.currentInputConnection
            if (isComposing && displayText.isNotEmpty()) {
                showInputBoxComposition(ic, displayText)
            } else {
                service.endComposingInputBox()
            }
        }
    }

    /** 在输入框模式向编辑器写入编码文本。 */
    private fun showInputBoxComposition(ic: android.view.inputmethod.InputConnection, displayText: String) {
        // 第二参数为 1：光标相对编码起始偏移 1 个字符，使光标落在编码末尾，
        // 避免传 displayText.length 时被 AOSP 钳制到整段文本末尾（光标跑到最右边）。
        ic.beginBatchEdit()
        try {
            ic.setComposingText(displayText, 1)
        } finally {
            ic.endBatchEdit()
        }
    }

    internal fun updateUIWithResult(result: com.kingzcheung.xime.rime.RimeProcessResult) {
        val isAsciiMode = result.isAsciiMode
        val candidatesWithComments = result.candidates

        val pendingEnglish = service.candidateState.value.pendingEnglishText

        val (filteredTexts, filteredComments) = if (isAsciiMode) {
            val filtered = candidatesWithComments.filterNot { candidate ->
                candidate.text.any { it.code in 0x4E00..0x9FFF }
            }
            filtered.map { it.text } to filtered.map { it.comment }
        } else {
            candidatesWithComments.map { it.text } to candidatesWithComments.map { it.comment }
        }

        // 非 T9 方案（如双拼）使用原始输入文本显示，
        // 避免显示 rime speller 展开后的编码（如双拼 i → ch）
        val isT9Schema = isT9Schema(service.uiState.value.currentSchemaId)
        // T9 候选词过滤：根据左侧选择历史（全拼/简拼）过滤不匹配的候选词
        val (t9FilteredTexts, t9FilteredComments) = if (isT9Schema) {
            service.keyboardCallbacks?.onFilterT9Candidates?.invoke(filteredTexts, filteredComments)
                ?: (filteredTexts to filteredComments)
        } else {
            filteredTexts to filteredComments
        }
        val displayText: String
        val displayCandidates: List<String>
        val displayComments: List<String>
        val isComposing: Boolean
        if (isT9Schema) {
            val rawPreedit = if (result.preeditText.isNotEmpty()) result.preeditText else result.inputText
            // preedit 转换由 C++ t9_filter 完成，Kotlin 侧直接使用引擎输出的 preedit
            val display = buildT9DisplayState(
                service.t9PartialSegments.map { it.text }, rawPreedit, result.inputText, t9FilteredTexts, t9FilteredComments
            )
            displayText = display.displayText
            displayCandidates = display.displayCandidates
            displayComments = display.displayComments
            isComposing = display.isComposing
        } else {
            val lowerInput = result.inputText.lowercase()
            val hasExtraContent = result.preeditText.any { c ->
                !c.isWhitespace() && c != '\'' && !lowerInput.contains(c.lowercaseChar())
            }
            displayText = if (result.preeditText.isNotEmpty() && hasExtraContent) result.preeditText else result.inputText
            displayCandidates = filteredTexts
            displayComments = filteredComments
            isComposing = result.inputText.isNotEmpty()
        }

        // 用户开始实际输入时，清除候选栏中残留的 inline suggestions
        if (displayText.isNotEmpty() || displayCandidates.isNotEmpty()) {
            service.dismissInlineSuggestions()
        }

        service.candidateState.value = service.candidateState.value.copy(
            inputText = displayText,
            preeditText = displayText,
            candidates = displayCandidates,
            candidateComments = displayComments,
            isComposing = isComposing,
            associationCandidates = if ((isAsciiMode || !service.isChineseMode) && pendingEnglish.isEmpty()) emptyList() else service.candidateState.value.associationCandidates,
            isShowingRecentClipboard = false,
            hasNextPage = result.hasNextPage,
            hasPrevPage = result.hasPrevPage
        )
        service.uiState.value = service.uiState.value.copy(isAsciiMode = isAsciiMode)
        
        if (pendingEnglish.isNotEmpty()) {
            service.serviceScope.launch {
                val candidates = service.predictionManager.getEnglishAssociations(pendingEnglish, PredictionManager.MAX_ASSOCIATION_COUNT)
                withContext(Dispatchers.Main) {
                    service.candidateState.value = service.candidateState.value.copy(associationCandidates = candidates)
                }
            }
        }

        if (SettingsPreferences.getInputTextLocation(service) == SettingsPreferences.INPUT_TEXT_INPUT_BOX) {
            val ic = service.currentInputConnection
            if (isComposing && displayText.isNotEmpty()) {
                showInputBoxComposition(ic, displayText)
            } else {
                service.endComposingInputBox()
            }
        }
    }

    internal fun updateSchemaName() {
        val context = service
        service.serviceScope.launch(Dispatchers.IO) {
            val page = withContext(Dispatchers.Main) {
                service.keyboardViewModel.page.value
            }
            val isHandwritingMode = (page as? com.kingzcheung.xime.keyboard.KeyboardPage.Main)?.type == com.kingzcheung.xime.keyboard.MainType.HANDWRITING
            val engineSchemaId = service.rimeEngine.getCurrentSchema()
            // session 未就绪时 getCurrentSchema() 返回空串：用持久化方案兜底，
            // 避免空值覆盖已正确的 currentSchemaId/schemaName 导致键盘退化为全键盘
            val currentSchemaId = if (isHandwritingMode) {
                HANDWRITING_SCHEMA_ID
            } else if (engineSchemaId.isNotEmpty()) {
                engineSchemaId
            } else {
                SettingsPreferences.getCurrentSchema(context)
            }
            val name = SchemaManager.getSchemaDisplayName(context, currentSchemaId)

            val enabledIds = SchemaManager.getEnabledSchemas(context)
            val allSchemas = SchemaManager.discoverSchemas(context)
            val schemas = allSchemas
                .filter { meta -> meta.schemaId in enabledIds && SchemaManager.isSchemaCompiled(context, meta.schemaId) }
                .map { meta ->
                    com.kingzcheung.xime.settings.SchemaInfo(
                        schemaId = meta.schemaId,
                        name = meta.name,
                        version = meta.version,
                        author = meta.author,
                        description = meta.description,
                        isDownloaded = true
                    )
                }

            withContext(Dispatchers.Main) {
                service.uiState.value = service.uiState.value.copy(
                    schemaName = name ?: currentSchemaId,
                    currentSchemaId = currentSchemaId,
                    schemas = schemas
                )
                refreshSchemaSwitches()
            }
        }
    }

    /** 读取当前引擎方案 `switches` 的实际取值，组装成菜单栏可展示的状态。 */
    private fun loadSchemaSwitches(schemaId: String): List<com.kingzcheung.xime.viewmodel.SchemaSwitchUiState> {
        if (schemaId.isEmpty()) return emptyList()
        val defs = SchemaManager.getSchemaSwitches(service, schemaId)
        return defs.map { def ->
            val index = when {
                def.name.isNotEmpty() && def.states.isNotEmpty() ->
                    if (service.rimeEngine.getOption(def.name)) minOf(1, def.states.size - 1) else 0
                def.options.isNotEmpty() -> {
                    val active = def.options.indexOfFirst { service.rimeEngine.getOption(it) }
                    if (active < 0) 0 else active
                }
                else -> 0
            }
            com.kingzcheung.xime.viewmodel.SchemaSwitchUiState(
                name = def.name,
                options = def.options,
                states = def.states,
                abbrev = def.abbrev,
                currentIndex = index
            )
        }
    }

    /** 在引擎线程上刷新菜单栏方案开关状态。 */
    private fun refreshSchemaSwitches() {
        service.serviceScope.launch(service.keyProcessingDispatcher) {
            val schemaId = service.rimeEngine.getCurrentSchema()
            val switches = loadSchemaSwitches(schemaId)
            withContext(Dispatchers.Main) {
                service.uiState.value = service.uiState.value.copy(schemaSwitches = switches)
            }
        }
    }

    /** 菜单栏方案开关点击：切换引擎选项并刷新状态。 */
    internal fun toggleSchemaSwitch(sw: com.kingzcheung.xime.viewmodel.SchemaSwitchUiState) {
        service.serviceScope.launch(service.keyProcessingDispatcher) {
            if (sw.name == "ascii_mode") {
                service.schemaController.switchInputMethod()
            } else if (sw.name.isNotEmpty()) {
                val newValue = !service.rimeEngine.getOption(sw.name)
                service.rimeEngine.setOption(sw.name, newValue)
                persistSchemaOption(sw.name, newValue)
                service.updateUI()
            } else if (sw.options.isNotEmpty()) {
                val nextIndex = (sw.currentIndex + 1) % sw.options.size
                sw.options.forEachIndexed { i, opt ->
                    service.rimeEngine.setOption(opt, i == nextIndex)
                    persistSchemaOption(opt, i == nextIndex)
                }
                service.updateUI()
            }
            refreshSchemaSwitches()
        }
    }

    /** 将方案选项状态写入 librime user.yaml（var/option/<name>）。 */
    internal fun persistSchemaOption(name: String, value: Boolean) {
        if (RimeEngine.isInitialized()) {
            service.rimeEngine.setUserConfigBool("var/option/$name", value)
        }
    }

    /** 从 librime user.yaml 恢复方案选项（中/西、简/繁等），在切换方案后调用。 */
    internal fun restorePersistedSchemaOptions() {
        if (!RimeEngine.isInitialized()) return
        val schemaId = service.rimeEngine.getCurrentSchema()
        if (schemaId.isEmpty()) return
        val rimeAsciiBefore = service.rimeEngine.isAsciiMode()
        val defs = SchemaManager.getSchemaSwitches(service, schemaId)
        for (def in defs) {
            if (def.name.isNotEmpty()) {
                service.rimeEngine.setOption(def.name, service.rimeEngine.getUserConfigBool("var/option/${def.name}"))
            } else if (def.options.isNotEmpty()) {
                val activeIndex = def.options.indexOfFirst { service.rimeEngine.getUserConfigBool("var/option/$it") }
                if (activeIndex >= 0) {
                    def.options.forEachIndexed { i, opt -> service.rimeEngine.setOption(opt, i == activeIndex) }
                }
            }
        }
        val rimeAsciiAfter = service.rimeEngine.isAsciiMode()
        if (rimeAsciiBefore != rimeAsciiAfter) {
            FileLogger.i(XimeInputMethodService.TAG, "restorePersistedSchemaOptions: ascii $rimeAsciiBefore -> $rimeAsciiAfter (ui=${service.uiState.value.isAsciiMode})")
        }
    }

    /**
     * T9 键盘切换离开时：提交右侧候选词列表首位候选词并清理 T9 和 Rime 状态。
     * 运行在 keyProcessingDispatcher 线程。
     */
    internal suspend fun commitFirstCandidateAndClearT9() {
        val isT9 = isT9Schema(service.uiState.value.currentSchemaId)
        if (!isT9) return

        val candState = service.candidateState.value
        val candidates = candState.candidates

        if (candidates.isNotEmpty()) {
            if (service.rimeEngine.selectCandidate(0)) {
                val committedText = service.rimeEngine.commit()
                if (committedText.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        service.commitText(committedText)
                    }
                }
            }
        }

        service.rimeEngine.clearComposition()

        withContext(Dispatchers.Main) {
            service.keyboardCallbacks?.onT9ReplaceFullPinyin?.invoke(T9InputController.CLEAR_ALL)
            service.uiState.value = service.uiState.value.copy(
                t9ResetSignal = service.uiState.value.t9ResetSignal + 1,
                t9RightCandidateSelectedCount = 0,
                t9SelectedCandidatePinyin = ""
            )
            service.t9PartialSegments.clear()
            service.candidateState.value = service.candidateState.value.copy(
                inputText = "",
                preeditText = "",
                candidates = emptyList(),
                candidateComments = emptyList(),
                isComposing = false,
                associationCandidates = emptyList(),
                hasNextPage = false,
                hasPrevPage = false
            )
        }
    }

}