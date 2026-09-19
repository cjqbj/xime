package com.kingzcheung.xime.service

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 构建键盘回调集合（KeyboardCallbacks）。
 *
 * 所有回调直接操作服务层状态与方法；service 内部成员对同模块可见（internal）。
 * 与原始实现在 onCreateInputView 内联构建的行为完全一致：
 * 仅当 [floatingMinY] 变化时重建（remember key 与原实现相同）。
 */
@Composable
internal fun rememberImeKeyboardCallbacks(
    service: XimeInputMethodService,
    floatingMinY: Int,
    state: InputUIState,
    effectiveScreenH: Int,
): KeyboardCallbacks {
    val view = LocalView.current
    return remember(floatingMinY) {
        KeyboardCallbacks(
            onKeyPress = { key, isShifted ->
                service.keyRouter.handleKeyPress(key, isShifted)
            },
            onKeyPressDown = { key ->
                service.feedbackManager.performKeyPressDownEffect(key, view)
            },
            onKeyRelease = { key ->
                service.feedbackManager.hapticFeedback(view, keyUp = true)
            },
            onCandidateSelect = { index ->
                service.keyRouter.selectCandidate(index)
            },
            onAssociationSelect = { index ->
                service.feedbackManager.performKeyPressEffect(view = view)
                val cs = service.candidateState.value
                val adjustedCandidates = if (cs.pendingEnglishText.isNotEmpty()) {
                    listOf(cs.pendingEnglishText) + cs.associationCandidates
                } else {
                    cs.associationCandidates
                }
                if (index >= 0 && index < adjustedCandidates.size) {
                    val text = adjustedCandidates[index]
                    val pendingEnglish = cs.pendingEnglishText
                    if (pendingEnglish.isNotEmpty()) {
                        if (index == 0 && text == pendingEnglish) {
                            service.commitText(text)
                            service.candidateState.value = service.candidateState.value.copy(
                                pendingEnglishText = "",
                                associationCandidates = emptyList()
                            )
                        } else {
                            // 键入时已用 setComposingText 建立 composing region，
                            // commitText 自然替换 composing 文本，终端也兼容。
                            service.commitText(text)
                            service.candidateState.value = service.candidateState.value.copy(
                                pendingEnglishText = "",
                                associationCandidates = emptyList()
                            )
                        }
                    } else {
                        service.commitText(text)
                        service.updateUI()
                    }
                }
            },
            onClearAssociation = {
                service.candidateState.value = service.candidateState.value.copy(associationCandidates = emptyList())
            },
            onToggleDarkMode = { service.toggleDarkMode() },
            onClipboard = {},
            onClipboardSelect = { text -> service.textCommit.selectClipboardItem(text) },
            onClipboardPullRemote = { service.clipboardSyncBridge?.pullOnce() },
            onCommitText = { text -> service.textCommit.commitClipboardText(text) },
            onDeleteText = { count -> service.textCommit.deleteClipboardChars(count) },
            onQuickSend = {},
            onKeyboardResize = {
                val config = service.resources.configuration
                val isLandscape = config.screenWidthDp > config.screenHeightDp
                val currentHeight = SettingsPreferences.getKeyboardHeightDp(service, isLandscape)
                service.uiState.value = service.uiState.value.copy(
                    showKeyboardResize = true,
                    resizePreviewHeightDp = currentHeight,
                )
            },
            onReloadConfig = { service.schemaController.reloadConfig() },
            onSettings = { service.schemaController.openSettings() },
            onSwitchSchema = { schemaId -> service.schemaController.switchSchema(schemaId) },
            onToggleSchemaSwitch = { sw -> service.sessionController.toggleSchemaSwitch(sw) },
            onHideKeyboard = { service.hideKeyboard() },
            onSwitchKeyboard = {
                val imm = service.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                @Suppress("DEPRECATION")
                imm.showInputMethodPicker()
            },
            onToolbarEditingAction = { action -> service.schemaController.handleToolbarEditingAction(action) },
            onCommitImage = { imagePath ->
                val success = service.textCommit.commitImage(imagePath)
                if (!success) {
                    android.widget.Toast.makeText(
                        service,
                        "发送失败，已复制到剪贴板",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    service.clipboardManager.copyImageToSystemClipboard(imagePath)
                }
            },
            onVoiceModeChange = { enabled ->
                if (!enabled) {
                    // 长按抬手：结束语音会话（提交当前已识别文本并停止识别）
                    service.endVoiceSession()
                } else if (!service.uiState.value.isVoiceMode) {
                    service.uiState.value = service.uiState.value.copy(
                        isVoiceMode = true,
                        voiceSticky = false,
                        voiceButtonState = VoiceButtonState(bottomActive = true),
                        voiceRecognizedText = ""
                    )
                    service.keyboardViewModel.enterVoice()
                    service.feedbackManager.performVibration()
                    service.isTrackingVoiceButtons = true
                    service.keyboardContainer.enableVoiceButtonTracking()
                    service.voiceRecordingStarted = true
                    // 立即提前启动麦克风录音（等 150ms 的话模型常驻时加载极快，
                    // preStarted 可能还没创建好就被 startRecording 跳过，导致开头丢失）
                    service.voiceRecognitionHandler.startDelayedPreStart(0)
                    service.voiceRecognitionHandler.startRecognition()
                }
            },
            onVoiceStickyToggle = {
                val state = service.uiState.value
                if (state.isVoiceMode && state.voiceSticky) {
                    // 常驻语音中：点按空格/再次点击工具栏即结束
                    service.endVoiceSession()
                } else if (!state.isVoiceMode) {
                    // 进入常驻语音：保持正常键盘布局，候选栏显示频谱，空格键轻触结束
                    service.uiState.value = service.uiState.value.copy(
                        isVoiceMode = true,
                        voiceSticky = true,
                        voiceButtonState = VoiceButtonState(bottomActive = true),
                        voiceRecognizedText = ""
                    )
                    service.voiceRecordingStarted = true
                    service.voiceRecognitionHandler.startDelayedPreStart(0)
                    service.voiceRecognitionHandler.startRecognition()
                }
            },
            onPageDown = { service.keyRouter.pageDown() },
            onPageUp = { service.keyRouter.pageUp() },
            onCursorMove = { direction ->
                val ic = service.currentInputConnection
                if (ic != null && direction != 0) {
                    if (SettingsPreferences.getInputTextLocation(service) == SettingsPreferences.INPUT_TEXT_INPUT_BOX &&
                        service.candidateState.value.isComposing
                    ) {
                        // 输入框模式：移动光标前先结束 composing 并清空 RIME 组成，
                        // 避免再次输入时 composing 区域与光标位置错乱
                        ic.finishComposingText()
                        service.keyRouter.postRimeJob {
                            service.rimeEngine.clearComposition()
                            withContext(Dispatchers.Main) {
                                service.mainHandler.post { service.updateUI() }
                            }
                        }
                    }
                    var movedBySelection = false
                    try {
                        val req = android.view.inputmethod.ExtractedTextRequest()
                        val extracted = ic.getExtractedText(req, 0)
                        if (extracted != null && extracted.selectionStart >= 0) {
                            val newPos = (extracted.selectionStart + direction)
                                .coerceIn(0, extracted.text?.length ?: 0)
                            ic.setSelection(newPos, newPos)
                            movedBySelection = true
                        }
                    } catch (_: Exception) {}
                    if (!movedBySelection) {
                        val keyCode = if (direction < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                        repeat(abs(direction)) {
                            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                        }
                    }
                }
            },
            onGestureAction = { action, value ->
                action.execute(service, value)
            },
            onUpdateToolbarButtons = { buttons ->
                SettingsPreferences.setToolbarButtons(service, buttons)
                service.uiState.value = service.uiState.value.copy(toolbarButtons = buttons)
            },
            onKeyboardModeChange = { chineseMode ->
                if (service.isChineseMode != chineseMode) {
                    service.isChineseMode = chineseMode
                    if (!chineseMode) {
                        service.candidateState.value = service.candidateState.value.copy(associationCandidates = emptyList())
                    }
                }
            },
            onDismissDeploying = { service.notifyDeploymentStatus(false, "") },
            onFloatingModeChange = { enabled -> service.schemaController.toggleFloatingMode(enabled, floatingMinY) },
            onFloatingKeyboardDrag = { dx, dy ->
                val s = service.uiState.value
                val screenW = service.resources.configuration.screenWidthDp
                val screenH = if (state.isFloatingMode) effectiveScreenH else service.resources.configuration.screenHeightDp
                val portraitWidth = minOf(screenW, screenH)
                val cardWidth = (portraitWidth * 0.85f).roundToInt()
                val halfMargin = ((screenW - cardWidth) / 2f).roundToInt()
                val newX = (s.floatingOffsetX + dx).roundToInt().coerceIn(-halfMargin, halfMargin)
                val newY_raw = (s.floatingOffsetY + dy).roundToInt()
                val actualCardH = if (service.currentFloatingCardHeightDp > 0) service.currentFloatingCardHeightDp else service.currentEffectiveKeyboardHeight
                val maxOffsetY = (screenH - actualCardH).coerceAtLeast(floatingMinY)
                val newY = newY_raw.coerceIn(0, maxOffsetY)
                service.uiState.value = s.copy(
                    floatingOffsetX = newX,
                    floatingOffsetY = newY,
                )
            },
            onFloatingKeyboardDragEnd = {
                val s = service.uiState.value
                val isLandscape = service.resources.configuration.screenWidthDp > service.resources.configuration.screenHeightDp
                SettingsPreferences.setFloatingOffsetX(service, s.floatingOffsetX, isLandscape)
                SettingsPreferences.setFloatingOffsetY(service, s.floatingOffsetY, isLandscape)
            },
            onT9ReplaceFullPinyin = { pinyin ->
                service.serviceScope.launch(service.keyProcessingDispatcher) {
                    when {
                        pinyin == T9InputController.CLEAR_COMPOSITION_ONLY -> {
                            service.rimeEngine.clearComposition()
                        }
                        pinyin == T9InputController.CLEAR_ALL -> {
                            service.t9PartialSegments.clear()
                            service.rimeEngine.setInput("")
                            service.rimeEngine.clearComposition()
                        }
                        pinyin.isEmpty() -> {
                            service.rimeEngine.clearComposition()
                        }
                        else -> {
                            service.rimeEngine.setInput(pinyin)
                        }
                    }
                    val composition = service.rimeEngine.getComposition()
                    withContext(Dispatchers.Main) {
                        service.mainHandler.post { service.sessionController.applyComposition(composition) }
                    }
                }
            },
            onT9RightCommitUndone = { count ->
                // 半提交文本在 composing 区域时无法用 deleteSurroundingText 删除，
                // 需通过 endComposingInputBox 清空，交由后续 applyComposition 重建。
                if (SettingsPreferences.getInputTextLocation(service)
                    == SettingsPreferences.INPUT_TEXT_INPUT_BOX) {
                    service.endComposingInputBox()
                } else {
                    service.currentInputConnection?.deleteSurroundingText(count, 0)
                }
                // undo 联动：撤销 right commit 段时回滚用户词典调频。
                val undone = service.t9PartialSegments.removeLastOrNull()
                if (undone != null) {
                    service.serviceScope.launch(service.keyProcessingDispatcher) {
                        service.rimeEngine.t9Forget(undone.text, undone.pinyin)
                    }
                }
            },
            onT9RefreshComposition = { composition ->
                // composition 由 T9 控制器在 flush 后一次取回并传入，
                // 避免在此再次 getComposition 造成重复 JNI 往返。
                service.mainHandler.post { service.sessionController.applyComposition(composition) }
            },
            onT9SwitchAway = {
                service.keyRouter.postRimeJob {
                    service.sessionController.commitFirstCandidateAndClearT9()
                }
            },
            onCommitCandidateBeforeModeChange = {
                val cs = service.candidateState.value
                if (cs.pendingEnglishText.isNotEmpty()) {
                    service.commitText(cs.pendingEnglishText)
                    service.candidateState.value = cs.copy(
                        pendingEnglishText = "",
                        associationCandidates = emptyList()
                    )
                } else if (!isT9Schema(service.uiState.value.currentSchemaId)
                    && cs.isComposing) {
                    if (cs.candidates.isNotEmpty()) {
                        if (service.rimeEngine.selectCandidate(0)) {
                            val text = service.rimeEngine.commit()
                            if (text.isNotEmpty()) service.commitText(text)
                        }
                    } else if (cs.preeditText.isNotEmpty()) {
                        service.commitText(cs.preeditText)
                        service.rimeEngine.clearComposition()
                    }
                }
            },
            onShowQuickSendForm = {
                val current = service.uiState.value
                service.uiState.value = current.copy(
                    showQuickSendForm = true,
                    quickSendFormFocused = true,
                    quickSendEditingItemId = null,
                    quickSendEditingItemText = "",
                    enterKeyText = "确定",
                )
            },
            onQuickSendEditItem = { id, text ->
                service.uiState.value = service.uiState.value.copy(
                    showQuickSendForm = true,
                    quickSendFormFocused = true,
                    quickSendEditingItemId = id,
                    quickSendEditingItemText = text,
                    enterKeyText = "确定",
                )
                QuickSendFormEditTextHolder.editText?.let { et ->
                    et.setText(text)
                    et.setSelection(text.length)
                }
            },
            onHideQuickSendForm = {
                service.uiState.value = service.uiState.value.copy(
                    showQuickSendForm = false,
                    quickSendFormFocused = false,
                    quickSendEditingItemId = null,
                    quickSendEditingItemText = "",
                    enterKeyText = "发送",
                )
                QuickSendFormEditTextHolder.editText = null
                service.keyboardViewModel.showOverlay(OverlayRoute.Clipboard(1))
            },
            onQuickSendFormFocusChange = { focused: Boolean ->
                service.uiState.value = service.uiState.value.copy(
                    quickSendFormFocused = focused,
                    enterKeyText = if (focused) "确定" else "发送",
                )
            },
        )
    }
}
