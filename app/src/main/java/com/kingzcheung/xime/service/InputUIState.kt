package com.kingzcheung.xime.service

import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.viewmodel.SchemaSwitchUiState

data class InputUIState(
    val isAsciiMode: Boolean = false,
    val schemaName: String = "",
    val currentSchemaId: String = "",
    val schemas: List<SchemaInfo> = emptyList(),
    val schemaSwitches: List<SchemaSwitchUiState> = emptyList(),
    val enterKeyText: String = "发送",
    val darkMode: Int = 0,
    val themeId: String = "ocean_blue",
    val isSttEnabled: Boolean = false,
    val keyboardHeightDp: Int = 0,
    val keyboardBottomPaddingDp: Int = 0,
    val showKeyboardResize: Boolean = false,
    val resizePreviewHeightDp: Int = 0,
    val associationEnabled: Boolean = false,
    val isVoiceMode: Boolean = false,
    val voiceSticky: Boolean = false,
    val voiceButtonState: VoiceButtonState = VoiceButtonState(),
    // 录音时静音其他应用（持久开关），语音页上滑热区据此显示与切换
    val sttMuteOthers: Boolean = false,
    // 系统悬浮状态窗是否生效（开关开且已授予悬浮窗权限）；为 true 时键盘内小胶囊让位
    val floatingVoiceLabel: Boolean = false,
    val voicePluginName: String = "",
    val voiceRecognitionState: RecognitionState = RecognitionState.IDLE,
    val voiceRecognizedText: String = "",
    val voiceAmplitude: Float = 0f,
    val stretchFactor: Float = 1f,
    val isDeploying: Boolean = false,
    val deploymentMessage: String = "",
    val inputSessionId: Long = 0,
    val t9ResetSignal: Long = 0,
    val swipeCancelEpoch: Long = 0,
    val t9RightCandidateSelectedCount: Long = 0,
    val t9SelectedCandidatePinyin: String = "",
    val toolbarButtons: List<String> = ToolbarButton.DEFAULT_VISIBLE.map { it.id },
    val isCompact: Boolean = false,
    val isFloatingMode: Boolean = false,
    val floatingOffsetX: Int = 0,
    val floatingOffsetY: Int = 0,
    val cursorX: Int = 0,
    val cursorY: Int = 0,
    val cursorVisible: Boolean = false,
    val showQuickSendForm: Boolean = false,
    val quickSendFormFocused: Boolean = false,
    val quickSendEditingItemId: Long? = null,
    val quickSendEditingItemText: String = "",
    val clipboardSyncEnabled: Boolean = false,
    // 剪贴板历史搜索态：开启后 IME 窗口撑满全屏，剪贴板结果占满键盘上方空间，
    // 自研键盘保留在底部；按键仍走 RIME 引擎（中文可组词），提交文本重定向写入搜索框。
    val clipboardSearchActive: Boolean = false,
    val clipboardSearchQuery: String = "",
)
