package com.kingzcheung.xime.service

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import com.kingzcheung.xime.ui.keyboard.LocalStretchFactor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.ui.keyboard.KeyboardResizeOverlay
import com.kingzcheung.xime.ui.keyboard.HardwareKeyboardCandidateBar
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.asCoroutineDispatcher
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kingzcheung.xime.MainActivity
import com.kingzcheung.xime.association.AssociationManager
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import com.kingzcheung.xime.association.AssociationService
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.clipboard.sync.ClipboardSyncBridge
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.rime.buildT9DisplayState
import com.kingzcheung.xime.rime.resolveRimeCandidateIndex

import com.kingzcheung.xime.settings.SchemaConfigHelper
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.keyboardBackground
import kotlin.math.abs
import kotlin.math.roundToInt
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.theme.XimeTheme
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.util.PreeditMergeHelper
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.keyboard.ActionExecutor
import com.kingzcheung.xime.keyboard.HANDWRITING_SCHEMA_ID
import com.kingzcheung.xime.keyboard.OverlayRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import android.os.Bundle
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.core.view.updateLayoutParams
import java.io.File
import java.io.FileInputStream

object QuickSendFormEditTextHolder {
    var editText: android.widget.EditText? = null
}

/**
 * T9 九键 partial commit 段：右选部分提交时累积的（文本, 拼音）对。
 * 文本用于 preedit 拼接与上屏，拼音用于用户词典调频/回滚，两者同源同步维护。
 */
data class T9PartialSegment(val text: String, val pinyin: String)

class XimeInputMethodService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, ActionExecutor {

    companion object {
        internal const val TAG = "XimeInputMethodService"
        private const val DARK_MODE_LIGHT = 0
        private const val DARK_MODE_DARK = 1
        private const val DARK_MODE_SYSTEM = 2
        private const val HARDWARE_CANDIDATE_BAR_HEIGHT = 72
        internal const val SAFE_TEXT_LIMIT = 262144

    }

    /** release 构建不输出调试日志，减少 logcat 写入开销。 */
    private fun debugLog(msg: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, msg)
        }
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    internal val rimeEngine = RimeEngine.getInstance()
    
    internal lateinit var clipboardManager: ClipboardManager

    internal var clipboardSyncBridge: ClipboardSyncBridge? = null
    
    internal lateinit var keyboardContainer: VoiceKeyboardContainer
    
    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    internal val keyProcessingDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "key-process").also { it.isDaemon = true }
    }.asCoroutineDispatcher()
    
    internal val keyJobs = Channel<Job>(Channel.UNLIMITED)
    internal val uiEventChannel = Channel<suspend () -> Unit>(Channel.CONFLATED)

    /**
     * 长按退格合并锁/状态。
     *
     * 长按退格以约 80ms 的固定频率重复派发，而 rime 退格（JNI + 输入重组）耗时可能
     * 超过 80ms。若每次重复都排队，keyJobs 会堆积，候选栏 UI 更新变成"迟到的跳帧"
     * 突发式刷新（一闪一闪）。这里把高频重复的退格合并为单个 job：处理完一次后
     * 立即消费累积的 [pendingDeleteCount]，删除速率被 rime 吞吐自然限制，
     * UI 更新平滑，抬手后也不会洪水式多删。
     */
    internal val deleteCoalesceLock = Any()
    internal var deleteJobActive = false
    internal var pendingDeleteCount = 0

    init {
        serviceScope.launch {
            keyJobs.consumeEach { job ->
                job.join()
            }
        }
        serviceScope.launch(Dispatchers.Main) {
            uiEventChannel.consumeEach { work -> work() }
        }
    }
    
    internal val mainHandler = Handler(Looper.getMainLooper())
    
    internal val uiState = mutableStateOf(InputUIState())
    internal val candidateState = mutableStateOf(CandidateState())
    private val clipboardItemsState = mutableStateOf<List<com.kingzcheung.xime.clipboard.ClipboardItem>>(emptyList())
    private val voiceAmplitudeState = mutableFloatStateOf(0f)
    private val voiceSpectrumState = mutableStateOf(FloatArray(16))
    private val quickSendItemsState = mutableStateOf<List<com.kingzcheung.xime.clipboard.ClipboardItem>>(emptyList())
    internal val recentClipboardItemsState = mutableStateOf<List<com.kingzcheung.xime.clipboard.ClipboardItem>>(emptyList())


    private val bottomInsetPxState = mutableStateOf(0)
    private var hasHardwareKeyboard = false
    private var floatingWinX = 100
    private var floatingWinY = 300
    
    internal var isTrackingVoiceButtons = false
    internal var voiceRecordingStarted = false
    private var pendingVoiceAction: (() -> Unit)? = null
    internal var composeViewRef: View? = null
    internal var lastClearedText: String = ""
    /** 累积的 partial commit 段列表（多段选词场景下逐段追加，文本+拼音同源，供调频/回滚） */
    internal val t9PartialSegments = mutableListOf<T9PartialSegment>()
    /** 键盘回调引用，用于在 RIME selectCandidate 前同步通知 T9 控制器 */
    internal var keyboardCallbacks: KeyboardCallbacks? = null
    internal var isChineseMode = true
    internal var currentEffectiveKeyboardHeight: Int = 0
    internal var currentFloatingCardHeightDp: Int = 0
    internal var previousSchemaId: String = ""
    /** 键盘内容 Box 顶部在窗口中的 y 坐标（px），由 onGloballyPositioned 实测更新 */
    private var keyboardContentTopPx: Int = -1
    
    internal val calculatorEngine = com.kingzcheung.xime.calculator.CalculatorEngine()

    private val _viewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = _viewModelStore

    internal val keyboardViewModel: KeyboardViewModel by lazy {
        ViewModelProvider(
            _viewModelStore,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(applicationContext as android.app.Application)
        ).get(KeyboardViewModel::class.java)
    }
    
    internal val predictionManager = PredictionManager(
        context = this,
        serviceScope = serviceScope,
        onPredictionResult = { candidates ->
            candidateState.value = candidateState.value.copy(
                associationCandidates = candidates
            )
        },
    )
    
    internal val voiceRecognitionHandler = VoiceRecognitionHandler(
        context = this,
        onStateChanged = { newState -> uiState.value = newState },
        getState = { uiState.value },
        getInputConnection = { currentInputConnection },
        onVoiceComplete = {
            val action = pendingVoiceAction
            pendingVoiceAction = null
            action?.invoke()

            endVoiceSession()
        },
        onAmplitudeChanged = { amplitude ->
            voiceAmplitudeState.floatValue = amplitude
        },
        onSpectrumChanged = { spectrum ->
            voiceSpectrumState.value = spectrum
        }
    )

    /**
     * 结束语音会话的统一出口：提交已识别文本、停止识别与预启动、恢复键盘状态。
     * 幂等：识别已停止/无文本时各步骤自动跳过。
     */
    internal fun endVoiceSession() {
        voiceRecognitionHandler.commitPendingOnRelease()
        voiceRecognitionHandler.stopRecognition()
        voiceRecognitionHandler.cancelPreStart()
        keyboardViewModel.exitVoice()
        isTrackingVoiceButtons = false
        voiceRecordingStarted = false
        voiceAmplitudeState.floatValue = 0f
        uiState.value = uiState.value.copy(
            isVoiceMode = false,
            voiceSticky = false,
            voiceButtonState = VoiceButtonState(),
            voiceRecognitionState = RecognitionState.IDLE,
            voiceRecognizedText = "",
            voiceAmplitude = 0f
        )
    }
    
    private var sharedPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var clipboardCollectorJob: kotlinx.coroutines.Job? = null
    
    internal val feedbackManager = FeedbackManager(this)

    internal val keyRouter = ImeKeyRouter(this)

    internal val sessionController = ImeSessionController(this)

    internal val schemaController = ImeSchemaController(this)

    internal val textCommit = ImeTextCommit(this)
    
    private val inlineSuggestionManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        InlineSuggestionManager(this)
    } else null
    
    private fun loadDarkModePreference() {
        val isLandscape = resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        val isFloatingMode = SettingsPreferences.isFloatingMode(this, isLandscape)
        SettingsPreferences.setFloatingMode(this, isFloatingMode, !isLandscape)
        val loadedX = SettingsPreferences.getFloatingOffsetX(this, isLandscape)
        val loadedY = SettingsPreferences.getFloatingOffsetY(this, isLandscape)
        SettingsPreferences.setFloatingOffsetX(this, loadedX, !isLandscape)
        SettingsPreferences.setFloatingOffsetY(this, loadedY, !isLandscape)
        val screenW = resources.configuration.screenWidthDp
        val screenH = resources.configuration.screenHeightDp
        val portraitWidth = minOf(screenW, screenH)
        val cardWidth = (portraitWidth * 0.85f).roundToInt()
        val halfMargin = maxOf(0, (screenW - cardWidth) / 2)
        val kbH = SettingsPreferences.getKeyboardHeightDp(this, isLandscape)
        val cappedKbH = kbH.coerceAtMost((screenH * 8) / 10)
        val cardH = (cappedKbH * 0.85f).roundToInt() + 18
        val navBarDp = tryGetNavBarHeightDp(this, window.window)
        val minY = if (isFloatingMode) navBarDp else 0
        val effectiveH = if (isFloatingMode) screenH - tryGetStatusBarHeightDp(this, window.window) else screenH
        val maxY = maxOf(minY, effectiveH - cardH - 20)
        val clampedX = loadedX.coerceIn(-halfMargin, halfMargin)
        val clampedY = loadedY.coerceIn(minY, maxY)
        if (clampedX != loadedX || clampedY != loadedY) {
            SettingsPreferences.setFloatingOffsetX(this, clampedX, isLandscape)
            SettingsPreferences.setFloatingOffsetY(this, clampedY, isLandscape)
        }
        uiState.value = uiState.value.copy(
            darkMode = SettingsPreferences.getDarkMode(this),
            themeId = SettingsPreferences.getKeyboardTheme(this),
            isSttEnabled = SettingsPreferences.isSttEnabled(this@XimeInputMethodService),
            keyboardHeightDp = SettingsPreferences.getKeyboardHeightDp(this, isLandscape),
            keyboardBottomPaddingDp = SettingsPreferences.getKeyboardBottomPaddingDp(this),
            toolbarButtons = SettingsPreferences.getToolbarButtons(this),
            isFloatingMode = isFloatingMode,
            floatingOffsetX = clampedX,
            floatingOffsetY = clampedY,
        )
    }
    
    private fun registerSharedPrefsListener() {
        val prefs = SettingsPreferences.getPrefsPublic(this)
        sharedPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "dark_mode", "keyboard_theme", "show_bottom_buttons", "keyboard_height_dp", "keyboard_bottom_padding_dp" -> {
                    loadDarkModePreference()
                    applyWindowBackground()
                }
                "floating_mode", "floating_mode_landscape" -> {
                    loadDarkModePreference()
                    applyWindowBackground()
                }
                "stt_enabled" -> {
                    uiState.value = uiState.value.copy(isSttEnabled = SettingsPreferences.isSttEnabled(this@XimeInputMethodService))
                }
                SettingsPreferences.KEY_SMART_PREDICTION_ENABLED -> onPredictionSettingChanged()
                SettingsPreferences.KEY_CLIPBOARD_SYNC_ENABLED -> updateClipboardSync()
                SettingsPreferences.KEY_CLIPBOARD_SYNC_PLUGIN_ID -> {
                    stopClipboardSync()
                    updateClipboardSync()
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(sharedPrefsListener)
    }

    /** 设置驱动：智能联想开启时加载联想模型，关闭时卸载。 */
    private fun onPredictionSettingChanged() {
        val enabled = SettingsPreferences.isSmartPredictionEnabled(this)
        if (enabled) {
            serviceScope.launch(Dispatchers.IO) {
                com.kingzcheung.xime.association.AssociationManager.initialize(this@XimeInputMethodService)
            }
        } else {
            com.kingzcheung.xime.association.AssociationManager.release()
        }
    }
    
    private fun saveDarkModePreference(mode: Int) {
        SettingsPreferences.setDarkMode(this, mode)
        uiState.value = uiState.value.copy(darkMode = mode)
    }
    
    fun toggleDarkMode() {
        val currentMode = uiState.value.darkMode
        val newMode = when (currentMode) {
            DARK_MODE_LIGHT -> DARK_MODE_DARK
            DARK_MODE_DARK -> DARK_MODE_LIGHT
            else -> { // DARK_MODE_SYSTEM: 切换到当前系统主题的反面
                if (isDarkTheme()) DARK_MODE_LIGHT else DARK_MODE_DARK
            }
        }
        saveDarkModePreference(newMode)
    }
    
    fun isDarkTheme(): Boolean {
        return when (uiState.value.darkMode) {
            DARK_MODE_DARK -> true
            DARK_MODE_SYSTEM -> {
                val nightModeFlags = resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            else -> false
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 允许 IME 窗口绘制到摄像头挖孔/刘海区域（横屏时背景覆盖全屏）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.window?.attributes?.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        savedStateRegistryController.performRestore(null)
        window.window?.decorView?.setViewTreeLifecycleOwner(this)
        window.window?.decorView?.setViewTreeSavedStateRegistryOwner(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        
        
        FileLogger.init(this)
        FileLogger.i(TAG, "XimeInputMethodService created")
        
        feedbackManager.initialize()
        
        loadDarkModePreference()
        registerSharedPrefsListener()
        
        initRimeEngine()
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    initClipboardManager()
                }
                initAssociationEngine()
                initSpeechRecognition()

                withContext(Dispatchers.Main) {
                    FileLogger.i(TAG, "Service initialization completed")
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Initialization failed: ${e.message}")
            }
        }
    }
    
    private fun initSpeechRecognition() {
        voiceRecognitionHandler.initialize()
    }
    
    private fun initAssociationEngine() {
        predictionManager.initialize()
    }
    
    
    private fun getPredictionFromPlugin(contextText: String) {
        predictionManager.getPrediction(contextText)
    }
    
    private fun initRimeEngine() {
        Log.d(TAG, "initRimeEngine: Starting initialization...")
        
        // 必须在任何异步操作之前同步加载键盘按键配置，
        // 否则 KeyboardLayout 组合时 swipeUp/swipeDown 配置可能尚未就绪，
        // 导致按键上的符号不显示、上滑/下滑手势不触发。
        runBlocking(Dispatchers.IO) {
            KeysConfigHelper.loadConfig(this@XimeInputMethodService)
        }
        
        RimeEngine.setDeploymentCallback { isDeploying, message ->
            serviceScope.launch(Dispatchers.Main) {
                uiState.value = uiState.value.copy(
                    isDeploying = isDeploying,
                    deploymentMessage = message
                )
            }
        }
        
        val initJob = serviceScope.launch(Dispatchers.IO) {
            try {
                notifyDeploymentStatus(true, "正在初始化...")
                
                val (userDataDir, sharedDataDir) = RimeConfigHelper.initializeRimeDataAsync(this@XimeInputMethodService)
                
                notifyDeploymentStatus(true, "正在加载输入法引擎...")
                rimeEngine.initialize(userDataDir, sharedDataDir)

                // 检查词库是否已部署（deploymentDone 标记 + 部署 hash 一致）
                val deploymentDone = SettingsPreferences.isDeploymentDone(this@XimeInputMethodService)
                val needsDeployment = !deploymentDone || !RimeConfigHelper.isDeploymentComplete(this@XimeInputMethodService)

                if (needsDeployment) {
                    // 统一部署入口（进程内互斥，hash 一致时内部跳过）。
                    // 与 XimeApplication 预初始化共享，避免两者并发触发两次全量编译。
                    notifyDeploymentStatus(true, "正在编译词库...")
                    if (RimeConfigHelper.ensureDeployment(this@XimeInputMethodService)) {
                        rimeEngine.updateLastBuildTime()
                    } else {
                        Log.e(TAG, "initRimeEngine: ensureDeployment failed, deployment may not have completed")
                    }
                } else {
                    Log.d(TAG, "initRimeEngine: Already deployed, creating session directly")
                }

                // 创建 session（已部署时跳过 maintenance 直接创建）
                val sessionReady = rimeEngine.ensureSession(180_000L)
                if (sessionReady) {
                    Log.d(TAG, "initRimeEngine: Session ready")
                    // 确保部署成功后才标记完成，避免首次部署超时后误标记
                    if (needsDeployment) {
                        SettingsPreferences.setDeploymentDone(this@XimeInputMethodService, true)
                        RimeConfigHelper.storeDeploymentHash(this@XimeInputMethodService)
                    }
                } else {
                    Log.w(TAG, "initRimeEngine: Session not ready after 60s, continuing in background")
                }
                notifyDeploymentStatus(false, "")

                withContext(Dispatchers.Main) {
                    val savedSchema = SettingsPreferences.getCurrentSchema(this@XimeInputMethodService)
                    val availableSchemas = rimeEngine.getAvailableSchemas()
                    val currentSchema = rimeEngine.getCurrentSchema()
                    Log.d(TAG, "initRimeEngine: currentSchema=$currentSchema, savedSchema=$savedSchema, availableSchemas=${availableSchemas.joinToString()}")
                    
                    when {
                        savedSchema == HANDWRITING_SCHEMA_ID -> {
                            // 手写方案：不要调 rimeEngine.switchSchema（Rime 没有手写引擎），
                            // 也不要覆盖 savedSchema（由 onStartInput 恢复 UI）
                            Log.d(TAG, "initRimeEngine: savedSchema is handwriting, keeping current Rime schema")
                        }
                        savedSchema in availableSchemas -> {
                            // 即使 savedSchema == currentSchema 也要调用 switchSchema，
                            // 因为 nativeCreateSession 后 schema 的 processor/translator 等
                            // 可能未完全初始化，switchSchema 会触发完整的初始化流程
                            Log.d(TAG, "initRimeEngine: Switching to saved schema: $savedSchema")
                            schemaController.applyPageSizeSetting(savedSchema)
                            rimeEngine.switchSchema(savedSchema)
                        }
                        SchemaManager.isSchemaCompiled(this@XimeInputMethodService, savedSchema) -> {
                            Log.d(TAG, "initRimeEngine: Schema compiled but not in get_schema_list, switching anyway")
                            schemaController.applyPageSizeSetting(savedSchema)
                            rimeEngine.switchSchema(savedSchema)
                        }
                        availableSchemas.isNotEmpty() -> {
                            // savedSchema 不可用且未编译，退而求其次用第一个可用方案
                            val fallbackSchema = availableSchemas.first()
                            Log.d(TAG, "initRimeEngine: savedSchema '$savedSchema' not available, falling back to '$fallbackSchema'")
                            schemaController.applyPageSizeSetting(fallbackSchema)
                            rimeEngine.switchSchema(fallbackSchema)
                            SettingsPreferences.setCurrentSchema(this@XimeInputMethodService, fallbackSchema)
                        }
                    }
                    
                    sessionController.updateSchemaName()
                    // onStartInput 在部署进行中会跳过 schema 切换与选项恢复，
                    // 部署完成后这里补齐 UI 状态，保证键盘可用
                    sessionController.restorePersistedSchemaOptions()
                    updateUI()
                    Log.d(TAG, "initRimeEngine: Rime engine initialized successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "initRimeEngine: Failed to initialize Rime engine", e)
                notifyDeploymentStatus(false, "初始化失败")
            }
        }
        
        // Watchdog: force-clear loading state after 190s
        // withTimeout cannot cancel native JNI calls; if rimeEngine.initialize() hangs
        // in librime, the IO coroutine would block forever. This watchdog ensures the
        // user is never permanently stuck on the loading screen.
        // 首次编译最多等 120s + ensureSession 60s + 10s 缓冲
        serviceScope.launch(Dispatchers.Main) {
            delay(190_000L)
            if (uiState.value.isDeploying) {
                Log.w(TAG, "initRimeEngine: Watchdog triggered - native init appears stuck, forcing loading state cleared")
                uiState.value = uiState.value.copy(
                    isDeploying = false,
                    deploymentMessage = "初始化超时，请重启输入法"
                )
            }
        }
    }
    
    internal fun notifyDeploymentStatus(isDeploying: Boolean, message: String) {
        serviceScope.launch(Dispatchers.Main) {
            uiState.value = uiState.value.copy(
                isDeploying = isDeploying,
                deploymentMessage = message
            )
        }
    }
    
    private fun initClipboardManager() {
        Log.d(TAG, "initClipboardManager: Starting initialization...")
        try {
            clipboardManager = ClipboardManager.getInstance(this)
            clipboardItemsState.value = clipboardManager.clipboardItems.value
            quickSendItemsState.value = clipboardManager.quickSendItems.value

            serviceScope.launch {
                clipboardManager.clipboardItems.collect { items ->
                    clipboardItemsState.value = items
                }
            }
            serviceScope.launch {
                clipboardManager.quickSendItems.collect { items ->
                    quickSendItemsState.value = items
                }
            }
            startClipboardSyncIfEnabled()
            serviceScope.launch {
                PluginManager.pluginInstancesFlow.collect {
                    updateClipboardSync()
                }
            }
            Log.d(TAG, "initClipboardManager: Clipboard manager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "initClipboardManager: Failed to initialize clipboard manager", e)
        }
    }

    private fun startClipboardSyncIfEnabled() {
        if (clipboardSyncBridge != null) return
        try {
            if (!SettingsPreferences.isClipboardSyncEnabled(this)) {
                Log.d(TAG, "Clipboard sync disabled in settings")
                return
            }
            val enabled = ExtensionManager.getEnabledClipboardSyncPlugins(this)
            if (enabled.isEmpty()) return
            val preferredId = SettingsPreferences.getClipboardSyncPluginId(this)
            val selected = enabled.firstOrNull { it.first == preferredId } ?: enabled.first()
            val plugin = selected.second
            clipboardSyncBridge = ClipboardSyncBridge(
                clipboardManager,
                plugin,
                pluginId = selected.first
            )
            clipboardSyncBridge?.start()
            uiState.value = uiState.value.copy(clipboardSyncEnabled = true)
            Log.d(TAG, "Clipboard sync started: ${selected.first}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start clipboard sync", e)
        }
    }

    private fun stopClipboardSync() {
        clipboardSyncBridge?.release()
        clipboardSyncBridge = null
        uiState.value = uiState.value.copy(clipboardSyncEnabled = false)
    }

    /** 剪贴板同步设置或插件状态变化时调用，按条件动态启停。 */
    private fun updateClipboardSync() {
        if (!::clipboardManager.isInitialized) return
        if (clipboardSyncBridge == null) {
            startClipboardSyncIfEnabled()
            return
        }
        if (
            !SettingsPreferences.isClipboardSyncEnabled(this) ||
            ExtensionManager.getEnabledClipboardSyncPlugins(this).isEmpty()
        ) {
            stopClipboardSync()
            return
        }
        // 当前 bridge 使用的插件与偏好选中的插件不一致时，重启切换到偏好插件
        val enabled = ExtensionManager.getEnabledClipboardSyncPlugins(this)
        val preferredId = SettingsPreferences.getClipboardSyncPluginId(this)
        val shouldUse = (if (preferredId.isNotEmpty()) {
            enabled.firstOrNull { it.first == preferredId }
        } else null) ?: enabled.first()
        if (shouldUse.first != clipboardSyncBridge?.pluginId) {
            stopClipboardSync()
            startClipboardSyncIfEnabled()
        }
    }

    private fun ensureClipboardManagerInitialized() {
        if (!::clipboardManager.isInitialized) {
            Log.d(TAG, "ensureClipboardManagerInitialized: Initializing clipboard manager synchronously")
            try {
                clipboardManager = ClipboardManager.getInstance(this)
                clipboardItemsState.value = clipboardManager.clipboardItems.value
                quickSendItemsState.value = clipboardManager.quickSendItems.value
                Log.d(TAG, "ensureClipboardManagerInitialized: Clipboard manager initialized")
            } catch (e: Exception) {
                Log.e(TAG, "ensureClipboardManagerInitialized: Failed to initialize clipboard manager", e)
            }
        }
    }

    override fun onCreateInputView(): View {
        keyboardContainer = VoiceKeyboardContainer(
            context = this,
            uiStateProvider = { uiState.value },
            onUiStateChanged = { newState -> uiState.value = newState },
            onPerformVibration = { view -> feedbackManager.hapticFeedback(view) },
            onPerformUndo = { pendingVoiceAction = { textCommit.performUndo() } },
            onPerformSearch = { pendingVoiceAction = { textCommit.performSearch() } },
            onStopRecognition = {
                voiceRecognitionHandler.commitPendingOnRelease()
                voiceRecognitionHandler.stopRecognition()
            },
            isRecording = { voiceRecordingStarted },
            setRecording = { voiceRecordingStarted = it },
            onVoiceDismiss = {
                val action = pendingVoiceAction
                pendingVoiceAction = null
                action?.invoke()
                endVoiceSession()
            },
            onTouchCancel = {
                uiState.value = uiState.value.copy(
                    swipeCancelEpoch = uiState.value.swipeCancelEpoch + 1
                )
            }
        )
        
        bottomInsetPxState.value = getActiveBottomInsetPx(window.window)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            keyboardContainer.setOnApplyWindowInsetsListener { v, insets ->
                val px = extractBottomInset(insets)
                if (px != bottomInsetPxState.value) {
                    bottomInsetPxState.value = px
                }
                v.onApplyWindowInsets(insets)
            }
        }
        
        val composeView = ComposeView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            composeViewRef = this
            setContent {
                val cand = candidateState.value
                val state = uiState.value
                val page by keyboardViewModel.page.collectAsState(com.kingzcheung.xime.keyboard.KeyboardPage.Main(com.kingzcheung.xime.keyboard.MainType.FULL))
                val isHandwritingMode = (page as? com.kingzcheung.xime.keyboard.KeyboardPage.Main)?.type == com.kingzcheung.xime.keyboard.MainType.HANDWRITING
                val isDarkTheme = isDarkTheme()
                val screenHeightDp = resources.configuration.screenHeightDp
                val physicalScreenDp = (resources.displayMetrics.heightPixels / resources.displayMetrics.density).roundToInt()
                val statusBarHeightDp = tryGetStatusBarHeightDp(this@XimeInputMethodService, window.window)
                val navBarHeightDp = tryGetNavBarHeightDp(this@XimeInputMethodService, window.window)
                val visibleNavBarHeightDp = tryGetVisibleNavBarHeightDp(this@XimeInputMethodService, window.window)
                // 用物理屏幕高度减去状态栏，保证不同 Android 版本一致
                val effectiveScreenH = if (state.isFloatingMode) physicalScreenDp - statusBarHeightDp else screenHeightDp
                val windowVisibleHeightDp = effectiveScreenH
                val navBarAlreadyExcluded = (physicalScreenDp - screenHeightDp) >= (navBarHeightDp + statusBarHeightDp - 3)
                val floatingMinY = if (navBarAlreadyExcluded) 0 else visibleNavBarHeightDp

                val screenWidthDp = resources.configuration.screenWidthDp
                val screenIsLandscape = screenWidthDp > screenHeightDp
                val portraitScreenHeightDp = if (screenIsLandscape) screenWidthDp else screenHeightDp
                val isLandscape = !state.isFloatingMode && screenIsLandscape
                val orientationHeight = if (state.isFloatingMode) {
                    val prefs = SettingsPreferences.getPrefsPublic(this@XimeInputMethodService)
                    val storedPortrait = prefs.getInt("keyboard_height_dp", -1)
                    if (storedPortrait > 0) storedPortrait else {
                        val storedLandscape = prefs.getInt("keyboard_height_dp_landscape", -1)
                        if (storedLandscape > 0) storedLandscape else portraitScreenHeightDp * SettingsPreferences.DEFAULT_KEYBOARD_HEIGHT_PERCENT / 100
                    }
                } else {
                    SettingsPreferences.getKeyboardHeightDp(this@XimeInputMethodService, screenIsLandscape)
                }
                val displayHeight = orientationHeight.coerceAtMost((if (state.isFloatingMode) portraitScreenHeightDp else screenHeightDp) * 8 / 10)
                val keyboardHeight = if (state.showKeyboardResize) {
                    if (screenIsLandscape) (screenHeightDp * 7) / 10 else displayHeight.coerceAtLeast(screenHeightDp / 2)
                } else if (isHandwritingMode) {
                    screenHeightDp / 2
                } else {
                    displayHeight
                }
                val floatScale = if (state.isFloatingMode) 0.85f else 1f
                val effectiveKeyboardHeight = (keyboardHeight * floatScale).toInt()
                val floatingDragBarHeight = if (state.isFloatingMode) 18 else 0
                val floatingCardContentHeight = effectiveKeyboardHeight + floatingDragBarHeight
                
                val density = LocalDensity.current
                // 统一使用 View 层多类型检测的 insets，避免 Compose
                // navigationBars 恒为手势条高度导致与系统栏（三键导航）差异被抹平。
                val activeBottomPx = bottomInsetPxState.value
                val rawDp = if (activeBottomPx > 0) {
                    with(density) { activeBottomPx.toDp().value.toInt() }
                } else 0
                // 底部留白整体缩减量（dp）：让键盘比系统导航栏实际高度再低一点，
                // 键盘背景已 edge-to-edge 延伸到系统栏后，留白可小于系统栏高度。
                val bottomInsetShrinkDp = 8
                // 标准（三键）导航栏 inset 明显大于手势条，额外多减一点，
                // 让标准模式高度更接近抬高模式，但保留可辨识的差异。
                val extraShrinkDp = if (rawDp >= 120) 8 else 0
                val bottomSpaceDp = if (rawDp > 0) (rawDp - bottomInsetShrinkDp - extraShrinkDp).coerceAtLeast(0) else 0
                // 兜底仅用于彻底检测不到任何底部 inset 的场景（全屏沉浸），
                // 不再把已有差异（标准 44dp / 手势 16dp）强行垫平。
                val minBottomDp = 18
                val activeBottomDp = if (bottomSpaceDp == 0) minBottomDp else bottomSpaceDp
                android.util.Log.d("ImeWindowInsets", "viewState=${bottomInsetPxState.value} rawDp=$rawDp shrink=$bottomInsetShrinkDp extra=$extraShrinkDp activeBottomDp=$activeBottomDp")
                val navBarDp = activeBottomDp.dp
                val hasNavBar = navBarDp > 0.dp

                val quickSendFormExtra = if (state.showQuickSendForm) 200 else 0

                XimeTheme(darkTheme = isDarkTheme, themeId = state.themeId) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Sync FrameLayout height with Compose content height
                        val contentHeight = if (state.showKeyboardResize) state.resizePreviewHeightDp else floatingCardContentHeight + quickSendFormExtra
                        val totalDp = if (state.isCompact || state.isFloatingMode) effectiveScreenH
                            else contentHeight + state.keyboardBottomPaddingDp + activeBottomDp
                        SideEffect {
                            // 非浮动模式（含键盘调节）容器保持 MATCH_PARENT（setInputView 已设置），
                            // 键盘内容在 Compose 内贴底，由 onComputeInsets 报告键盘内容顶部。
                            if (state.isCompact || state.isFloatingMode) {
                                keyboardContainer.updateHeight(totalDp)
                            } else {
                                // 从悬浮/紧凑模式切回时恢复容器为 MATCH_PARENT，
                                // 否则容器高度残留悬浮时的固定值导致布局异常。
                                keyboardContainer.resetHeight()
                            }
                            currentEffectiveKeyboardHeight = if (state.isFloatingMode) keyboardHeight + floatingDragBarHeight + 50 + state.keyboardBottomPaddingDp
                                else if (state.isCompact) HARDWARE_CANDIDATE_BAR_HEIGHT
                                else effectiveKeyboardHeight + quickSendFormExtra
                        }
                        val kbColors = KeysConfigHelper.getKeyboardColors()
                        val longToColor: (Long) -> androidx.compose.ui.graphics.Color = { if (it == 0L)  { androidx.compose.ui.graphics.Color(0xE61E1E1E) } else if (it > 0xFFFFFF) { androidx.compose.ui.graphics.Color(it) } else { androidx.compose.ui.graphics.Color(0xFF000000 or it) } }
                        val isDark = isDarkTheme
                        val cardBg = if (isDark) longToColor(com.kingzcheung.xime.settings.KeyboardColorsConfig.FALLBACK_BG_DARK) else longToColor(com.kingzcheung.xime.settings.KeyboardColorsConfig.FALLBACK_BG_LIGHT)
                        val candidateTextCol = com.kingzcheung.xime.ui.theme.KeyboardThemes.getCandidateTextColorOverride(state.themeId, isDark)
                            ?: if (isDark) longToColor(kbColors.candidateTextColorDark) else longToColor(kbColors.candidateTextColor)
                        val accentCol = com.kingzcheung.xime.ui.theme.KeyboardThemes.getAccentColor(state.themeId, isDark)
                        val selectedTextCol = com.kingzcheung.xime.ui.theme.KeyboardThemes.getCandidateSelectedTextColor(state.themeId, isDark)
                        val keyboardBgColor = cardBg
                        val rootTheme = com.kingzcheung.xime.ui.theme.KeyboardThemes.getThemeById(state.themeId)
                        if (state.isCompact && (cand.candidates.isNotEmpty() || cand.isShowingRecentClipboard || cand.inputText.isNotEmpty())) {
                            HardwareKeyboardCandidateBar(
                                inputText = cand.inputText,
                                preeditText = cand.preeditText,
                                candidates = cand.candidates,
                                hasNextPage = cand.hasNextPage,
                                hasPrevPage = cand.hasPrevPage,
                                cursorX = state.cursorX,
                                cursorY = state.cursorY,
                                cursorVisible = state.cursorVisible,
                                highlightIndex = highlightIndex.intValue,
                                cardBackgroundColor = cardBg,
                                candidateTextColor = candidateTextCol,
                                activeColor = accentCol,
                                selectedTextColor = selectedTextCol,
                            )
                        } else if (state.isCompact) {
                            Box(modifier = Modifier.fillMaxSize())
                        } else {
                        // 非浮动：背景与键盘内容同区域，贴底覆盖键盘内容高度 + 底部导航栏留白，
                        // 键盘内容通过 offset 上移 activeBottomDp 留出导航栏空间（对齐参考实现 bottomPaddingSpace）。
                        // 浮动模式：卡片由 KeyboardView 内部 FloatingKeyboardContainer 自绘背景与定位，此处不做背景/偏移。
                        if (!state.isFloatingMode) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(if (state.showKeyboardResize) (state.resizePreviewHeightDp + state.keyboardBottomPaddingDp + activeBottomDp).dp else (floatingCardContentHeight + state.keyboardBottomPaddingDp + quickSendFormExtra + activeBottomDp).dp)
                                    .align(androidx.compose.ui.Alignment.BottomCenter)
                                    .keyboardBackground(rootTheme.keyboardBackground, isDark, keyboardBgColor)
                            )
                        }
                        Box(
                            modifier = Modifier

                                .fillMaxWidth()
                                .height(if (state.showKeyboardResize) (state.resizePreviewHeightDp + state.keyboardBottomPaddingDp).dp else (floatingCardContentHeight + state.keyboardBottomPaddingDp + quickSendFormExtra).dp)
                                .align(androidx.compose.ui.Alignment.BottomCenter)
                                .then(if (state.isFloatingMode) Modifier else Modifier.offset(y = (-activeBottomDp).dp))
                                .onGloballyPositioned {
                                    if (!state.isFloatingMode && !state.isCompact) {
                                        keyboardContentTopPx = it.positionInWindow().y.toInt()
                                    }
                                }
                        ) {
                        CompositionLocalProvider(LocalStretchFactor provides state.stretchFactor) {
                            // 注意：kbState 只承载键盘按键/布局状态，不承载候选数据。
                            // 候选数据单独通过 candidateState 传给 KeyboardView。
                            // remember 的 key 均为候选无关依赖：候选变化时 kbState 实例保持不变，
                            // KeyboardView（按键区）跳过重组，只有读取 candidateState 的候选栏重组，
                            // 避免长按退格时高频候选更新触发整个键盘重组导致候选栏闪烁。
                            val kbState = remember(
                                state,
                                isDarkTheme,
                                effectiveKeyboardHeight,
                                floatingMinY,
                            isHandwritingMode,
                            clipboardItemsState.value,
                            quickSendItemsState.value,
                            recentClipboardItemsState.value,
                            calculatorEngine.isActive(),
                            ) {
                                KeyboardUiState(
                                    isAsciiMode = state.isAsciiMode,
                                    schemaName = state.schemaName,
                                    currentSchemaId = state.currentSchemaId,
                                    schemas = state.schemas,
                                    schemaSwitches = state.schemaSwitches,
                                    enterKeyText = state.enterKeyText,
                                    isDarkTheme = isDarkTheme,
                                    darkMode = state.darkMode,
                                    themeId = state.themeId,
                                    keyboardHeightDp = effectiveKeyboardHeight,
                                    keyboardBottomPaddingDp = state.keyboardBottomPaddingDp,
                                    clipboardItems = clipboardItemsState.value,
                                    quickSendItems = quickSendItemsState.value,
                                    recentClipboardItems = recentClipboardItemsState.value,
                                    isVoiceMode = state.isVoiceMode,
                                    voiceSticky = state.voiceSticky,
                                    voiceBottomActive = state.voiceButtonState.bottomActive,
                                    voiceLeftActive = state.voiceButtonState.leftActive,
                                    voiceRightActive = state.voiceButtonState.rightActive,
                                    voicePluginName = state.voicePluginName,
                                    voiceRecognitionState = state.voiceRecognitionState,
                                    voiceRecognizedText = state.voiceRecognizedText,
                                    isSttEnabled = state.isSttEnabled,
                                    toolbarButtons = state.toolbarButtons,
                                    isCalculatorMode = calculatorEngine.isActive(),
                                    inputSessionId = state.inputSessionId,
                                    isFloatingMode = state.isFloatingMode,
                                    isHandwritingMode = isHandwritingMode,
                                    floatingOffsetX = state.floatingOffsetX,
                                    floatingOffsetY = state.floatingOffsetY,
                                    floatingMinOffsetY = floatingMinY,
                                    t9ResetSignal = state.t9ResetSignal,
                                    swipeCancelEpoch = state.swipeCancelEpoch,
                                    t9RightCandidateSelectedCount = state.t9RightCandidateSelectedCount,
                                    t9SelectedCandidatePinyin = state.t9SelectedCandidatePinyin,
                                    showQuickSendForm = state.showQuickSendForm,
                                    quickSendFormFocused = state.quickSendFormFocused,
                                    quickSendEditingItemId = state.quickSendEditingItemId,
                                    quickSendEditingItemText = state.quickSendEditingItemText,
                                    clipboardSyncEnabled = state.clipboardSyncEnabled,
                                )
                            }
                            val callbacks = rememberImeKeyboardCallbacks(this@XimeInputMethodService, floatingMinY, state, effectiveScreenH)
                            keyboardCallbacks = callbacks
                            KeyboardView(
                                viewModel = keyboardViewModel,
                                state = kbState,
                                candidateState = candidateState,
                                voiceAmplitudeState = this@XimeInputMethodService.voiceAmplitudeState,
                                voiceSpectrumState = this@XimeInputMethodService.voiceSpectrumState,
                                callbacks = callbacks,
                                inlineSuggestions = inlineSuggestionManager?.suggestions.orEmpty(),
                                onCardPositioned = { _: Int, top: Int, _: Int, bottom: Int ->
                                    val cardHeightPx = bottom - top
                                    if (cardHeightPx > 0) {
                                        currentEffectiveKeyboardHeight = (cardHeightPx / density.density).roundToInt()
                                    }
                                },
                            )
                           }
                           if (state.showKeyboardResize) {
                              KeyboardResizeOverlay(
                                     initialHeightDp = state.resizePreviewHeightDp,
                                     defaultHeightDp = SettingsPreferences.getDefaultKeyboardHeightDp(this@XimeInputMethodService, isLandscape),
                                     currentBottomPaddingDp = state.keyboardBottomPaddingDp,
                                     onHeightChange = { newHeight ->
                                       uiState.value = uiState.value.copy(
                                           resizePreviewHeightDp = newHeight
                                       )
                                   },
                                  onBottomPaddingChange = { newPadding ->
                                       uiState.value = uiState.value.copy(
                                           keyboardBottomPaddingDp = newPadding
                                       )
                                   },
                                  onReset = { defaultHeight ->
                                       uiState.value = uiState.value.copy(
                                           resizePreviewHeightDp = defaultHeight,
                                           keyboardBottomPaddingDp = 0,
                                           stretchFactor = 1f
                                       )
                                   },
                                  onConfirm = { newHeight, newPadding ->
                                       schemaController.setKeyboardHeight(newHeight)
                                       SettingsPreferences.setKeyboardBottomPaddingDp(this@XimeInputMethodService, newPadding)
                                       uiState.value = uiState.value.copy(
                                           showKeyboardResize = false,
                                           keyboardHeightDp = newHeight,
                                           keyboardBottomPaddingDp = newPadding,
                                       )
                                    },
                                    onCancel = {
                                        val restoreHeight = SettingsPreferences.getKeyboardHeightDp(this@XimeInputMethodService, isLandscape)
                                        val restorePadding = SettingsPreferences.getKeyboardBottomPaddingDp(this@XimeInputMethodService)
                                        uiState.value = uiState.value.copy(
                                            showKeyboardResize = false,
                                            keyboardHeightDp = restoreHeight,
                                            keyboardBottomPaddingDp = restorePadding,
                                        )
                                    },
                                    modifier = Modifier
                                       .fillMaxSize()
                              )
                          }
                           }
                            if (!state.isFloatingMode && navBarDp > 0.dp) {
                                Spacer(modifier = Modifier.fillMaxWidth().height(navBarDp))
                            }
                       }
                      }
                 }
             }
         }
        
        keyboardContainer.addView(composeView)

        applyWindowBackground()

        return keyboardContainer
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        win.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        try {
            window.window?.decorView
                ?.findViewById<FrameLayout>(android.R.id.inputArea)
                ?.updateLayoutParams<android.view.ViewGroup.LayoutParams> {
                    height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                }
            view.updateLayoutParams<android.view.ViewGroup.LayoutParams> {
                height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            }
        } catch (_: Exception) {}
    }
    
    // ── ActionExecutor 实现 ──

    override fun performEditorMenuAction(actionId: Int) {
        when (actionId) {
            android.R.id.undo -> {
                // performContextMenuAction 对 undo 支持不一致，改用 Ctrl+Z 键盘快捷键
                val now = SystemClock.uptimeMillis()
                currentInputConnection?.sendKeyEvent(
                    KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON)
                )
                currentInputConnection?.sendKeyEvent(
                    KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON)
                )
            }
            else -> currentInputConnection?.performContextMenuAction(actionId)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val e = event ?: return super.onKeyDown(keyCode, event)
        if (hasHardwareKeyboard && candidateState.value.candidates.isNotEmpty()) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (candidateState.value.hasNextPage) { keyRouter.pageDown(); highlightIndex.intValue = 0; return true }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (candidateState.value.hasPrevPage) { keyRouter.pageUp(); highlightIndex.intValue = 0; return true }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val maxIdx = candidateState.value.candidates.size - 1
                    highlightIndex.intValue = (highlightIndex.intValue + 1).coerceAtMost(maxIdx)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    highlightIndex.intValue = (highlightIndex.intValue - 1).coerceAtLeast(0)
                    return true
                }
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER -> {
                    if (candidateState.value.candidates.isNotEmpty()) {
                        keyRouter.selectCandidate(highlightIndex.intValue)
                        highlightIndex.intValue = 0
                        return true
                    }
                }
                KeyEvent.KEYCODE_1 -> { keyRouter.selectCandidate(0); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_2 -> { keyRouter.selectCandidate(1); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_3 -> { keyRouter.selectCandidate(2); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_4 -> { keyRouter.selectCandidate(3); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_5 -> { keyRouter.selectCandidate(4); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_6 -> { keyRouter.selectCandidate(5); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_7 -> { keyRouter.selectCandidate(6); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_8 -> { keyRouter.selectCandidate(7); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_9 -> { keyRouter.selectCandidate(8); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_0 -> { keyRouter.selectCandidate(9); highlightIndex.intValue = 0; return true }
            }
        }
        val isShifted = e.isShiftPressed
        val key = keyCodeToKey(keyCode, isShifted)
        if (key != null) {
            keyRouter.handleKeyPress(key, isShifted)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun sendKeyEvent(keyCode: Int) {
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    override fun executeCommand(name: String) {
        when (name) {
            "clear_composition" -> {
                keyRouter.postRimeJob {
                    rimeEngine.clearComposition()
                    withContext(Dispatchers.Main) {
                        mainHandler.post { updateUI() }
                    }
                }
            }
            "show_ime_picker" -> {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                @Suppress("DEPRECATION")
                imm.showInputMethodPicker()
            }
            else -> Log.w(TAG, "Unknown command: $name")
        }
    }

    override fun repeatLastInput() {
        val lastText = predictionManager.lastCommittedText
        if (lastText.isNotEmpty()) {
            currentInputConnection?.commitText(lastText, 1)
        }
    }

    // ── 原有方法 ──

    
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        loadDarkModePreference()

        predictionManager.clearCommittedText()
        // 新输入会话清空 partial commit 累积：外部 UI（如设置页输入框"清除"按钮仅清 Compose
        // state）会触发 restartInput → 此处重建 T9，若残留累积会被 buildT9DisplayState 拼进
        // preedit 回灌输入框（2026-08-07 日志实证：清除后 testText 从 '' 回灌为 '几乎'）。
        t9PartialSegments.clear()
        debugLog("onStartInput: cleared lastCommittedText")

        // 跨进程同步文件日志开关（开关在主进程设置页切换）
        FileLogger.setVerboseLoggingEnabled(
            SettingsPreferences.isVerboseLoggingEnabled(this)
        )
        
        if (RimeEngine.isInitialized()) {
            // 部署/全量编译进行中：不执行 schema 切换（switchSchema 会等待 rimeLock，
            // 60MB 词库编译可达 30s+，主线程等待会导致 ANR）。部署完成后
            // initRimeEngine 的流程会自动切换到正确方案，这里只做 UI 状态恢复。
            if (!rimeEngine.isMaintaining()) {
                val savedSchema = SettingsPreferences.getCurrentSchema(this)
                val currentSchema = rimeEngine.getCurrentSchema()
                val availableSchemas = rimeEngine.getAvailableSchemas()
                debugLog("onStartInput: saved=$savedSchema, current=$currentSchema, available=${availableSchemas.joinToString()}")
                
                val actualSchema: String
                when {
                    savedSchema == HANDWRITING_SCHEMA_ID -> {
                        debugLog("onStartInput: saved schema is handwriting, checking model files")
                        val hwDir = com.kingzcheung.xime.model.ModelStorage.getModelDir(this, "ochwpro")
                        com.kingzcheung.xime.model.ModelStorage.migrateLegacyForModel(this, "ochwpro")
                        val modelFile = java.io.File(hwDir, "ochwpro.onnx")
                        val charIndexFile = java.io.File(hwDir, "char_index.json")
                        if (!modelFile.exists() || !charIndexFile.exists()) {
                            Log.w(TAG, "Handwriting model not found, falling back to first available schema")
                            android.widget.Toast.makeText(
                                this, "请先下载手写模型", android.widget.Toast.LENGTH_LONG
                            ).show()
                            val fallbackSchema = if (availableSchemas.isNotEmpty()) {
                                availableSchemas.first()
                            } else {
                                savedSchema
                            }
                            schemaController.applyPageSizeSetting(fallbackSchema)
                            rimeEngine.switchSchema(fallbackSchema)
                            SettingsPreferences.setCurrentSchema(this, fallbackSchema)
                            actualSchema = fallbackSchema
                        } else {
                            debugLog("onStartInput: saved schema is handwriting, keeping handwriting mode")
                            keyboardViewModel.switchMain(com.kingzcheung.xime.keyboard.MainType.HANDWRITING)
                            actualSchema = savedSchema
                        }
                    }
                    savedSchema in availableSchemas -> {
                        if (savedSchema != currentSchema) {
                            debugLog("onStartInput: Switching to saved schema: $savedSchema")
                            schemaController.applyPageSizeSetting(savedSchema)
                            rimeEngine.switchSchema(savedSchema)
                        } else {
                            // 即使 schema 相同也重新 switch 一下，确保 processor 完全初始化
                            debugLog("onStartInput: Schema already matches, re-switching to init processors")
                            schemaController.applyPageSizeSetting(savedSchema)
                            rimeEngine.switchSchema(savedSchema)
                        }
                        actualSchema = savedSchema
                    }
                    SchemaManager.isSchemaCompiled(this@XimeInputMethodService, savedSchema) -> {
                        debugLog("onStartInput: Schema compiled but not in get_schema_list, switching anyway")
                        schemaController.applyPageSizeSetting(savedSchema)
                        rimeEngine.switchSchema(savedSchema)
                        actualSchema = savedSchema
                    }
                    availableSchemas.isNotEmpty() -> {
                        val fallbackSchema = availableSchemas.first()
                        debugLog("onStartInput: savedSchema '$savedSchema' not available, falling back to '$fallbackSchema'")
                        schemaController.applyPageSizeSetting(fallbackSchema)
                        rimeEngine.switchSchema(fallbackSchema)
                        SettingsPreferences.setCurrentSchema(this, fallbackSchema)
                        actualSchema = fallbackSchema
                    }
                    else -> actualSchema = savedSchema
                }
                sessionController.updateSchemaName()
                
                // 从 user.yaml 恢复方案选项（中/西、简/繁等，含 ascii_mode）
                sessionController.restorePersistedSchemaOptions()
                updateUI()
            } else {
                debugLog("onStartInput: deployment in progress, skipping schema switch")
            }
        }

        uiState.value = uiState.value.copy(
            inputSessionId = System.nanoTime(),
            isSttEnabled = SettingsPreferences.isSttEnabled(this@XimeInputMethodService),
        )

        // 重置键盘布局到初始状态，避免切换应用后仍残留之前的布局（如英文、数字、符号）。
        // 必须携带当前 schemaId，否则 T9/笔画等专用布局会被错误重置为默认全键盘。
        // restarting=true 表示同一输入会话内的状态刷新（应用 restartInput），此时不应
        // 重置布局，否则数字/符号面板会在输入中被切回全键盘。
        if (RimeEngine.isInitialized() && !restarting) {
            val rimeAscii = rimeEngine.isAsciiMode()
            FileLogger.i(TAG, "onStartInput: reset keyboard, rimeAscii=$rimeAscii")
            uiState.value = uiState.value.copy(isAsciiMode = rimeAscii)
            // currentSchemaId 为空（如引擎重建后 updateSchemaName 尚未完成）时，
            // 用持久化方案兜底，避免布局退化为 26 键全键盘
            val schemaId = uiState.value.currentSchemaId
                .ifBlank { SettingsPreferences.getCurrentSchema(this) }
            keyboardViewModel.resetKeyboard(rimeAscii, schemaId)
        } else {
            val rimeAscii = if (RimeEngine.isInitialized()) rimeEngine.isAsciiMode() else "n/a"
            FileLogger.i(TAG, "onStartInput: skip keyboard reset, restarting=$restarting, rimeAscii=$rimeAscii, ui=${uiState.value.isAsciiMode}")
        }

        // 先重置候选状态到初始值，避免前一 session 的残留状态影响新输入
        candidateState.value = CandidateState()

        // 获取最近30秒的剪切板内容
        ensureClipboardManagerInitialized()
        try {
            recentClipboardItemsState.value = clipboardManager.getRecentItems(30)
            // 将最近剪切板内容显示在候选栏
            candidateState.value = candidateState.value.copy(
                candidates = recentClipboardItemsState.value.map { it.text },
                candidateComments = emptyList(),
                isShowingRecentClipboard = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get recent clipboard items", e)
        }

        // 监听clipboardItems变化，更新候选栏
        clipboardCollectorJob?.cancel()
        clipboardCollectorJob = serviceScope.launch {
            clipboardManager.clipboardItems.collect { _ ->
                val items = clipboardManager.getRecentItems(30)
                recentClipboardItemsState.value = items
                if (items.isNotEmpty()) {
                    // 清空Rime联想词等
                    rimeEngine.clearComposition()
                    candidateState.value = candidateState.value.copy(
                        candidates = items.map { it.text.take(8) + if (it.text.length > 8) "..." else "" },
                        candidateComments = emptyList(),
                        inputText = "",
                        isComposing = false,
                        associationCandidates = emptyList(),
                        isShowingRecentClipboard = true
                    )
                } else if (candidateState.value.isShowingRecentClipboard) {
                    // 如果没有recent items，清空候选栏
                    candidateState.value = candidateState.value.copy(
                        candidates = emptyList(),
                        candidateComments = emptyList(),
                        isShowingRecentClipboard = false
                    )
                }
            }
        }

        attribute?.let { updateEnterKeyText(it) }
    }
    
    private val highlightIndex = mutableIntStateOf(0)

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        info?.let { updateEnterKeyText(it) }
        hasHardwareKeyboard = resources.configuration.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS
        applyCompactMode()
        applyWindowBackground()
        if (hasHardwareKeyboard) {
            currentInputConnection?.requestCursorUpdates(
                InputConnection.CURSOR_UPDATE_MONITOR or InputConnection.CURSOR_UPDATE_IMMEDIATE
            )
        }
    }

    private var anchorCoords = floatArrayOf(0f, 0f, 0f, 0f)

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        if (!hasHardwareKeyboard) return
        try {
            val bounds = info.getCharacterBounds(0)
            if (bounds != null) {
                anchorCoords[0] = bounds.left
                anchorCoords[1] = bounds.bottom
                anchorCoords[2] = bounds.left
                anchorCoords[3] = bounds.top
            } else {
                anchorCoords[0] = info.insertionMarkerHorizontal
                anchorCoords[1] = info.insertionMarkerBottom
                anchorCoords[2] = info.insertionMarkerHorizontal
                anchorCoords[3] = info.insertionMarkerTop
            }
            if (anchorCoords.any(Float::isNaN)) return
            info.matrix.mapPoints(anchorCoords)
            val screenY = anchorCoords[1].toInt().coerceIn(0, resources.displayMetrics.heightPixels)
            val screenX = anchorCoords[0].toInt().coerceIn(0, resources.displayMetrics.widthPixels)
            uiState.value = uiState.value.copy(
                cursorX = screenX,
                cursorY = screenY,
                cursorVisible = true,
            )
        } catch (e: Exception) {
            Log.e(TAG, "onUpdateCursorAnchorInfo failed", e)
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }

    override fun onEvaluateInputViewShown(): Boolean {
        return true
    }

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        return true
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        hasHardwareKeyboard = newConfig.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS
        super.onConfigurationChanged(newConfig)
        applyCompactMode()
        loadDarkModePreference()
        applyWindowBackground()
        if (hasHardwareKeyboard) {
            currentInputConnection?.requestCursorUpdates(
                InputConnection.CURSOR_UPDATE_MONITOR or InputConnection.CURSOR_UPDATE_IMMEDIATE
            )
        }
    }

    internal fun applyWindowBackground() {
        val state = uiState.value
        val isDark = isDarkTheme()
        try {
            val theme = com.kingzcheung.xime.ui.theme.KeyboardThemes.getThemeById(state.themeId)
            // 图片背景无法映射到 window 层，用主题主色作为导航栏/窗口兜底色；
            // solid / gradient 用解析出的键盘背景兜底色。
            val bgColor = if (theme.keyboardBackground?.type == "image") {
                com.kingzcheung.xime.ui.theme.KeyboardThemes.getPrimaryColor(state.themeId, isDark)
            } else {
                com.kingzcheung.xime.ui.theme.KeyboardThemes.getKeyboardBackgroundColor(state.themeId, isDark)
            }
            val argb = (bgColor.alpha * 255).toInt() shl 24 or
                (bgColor.red * 255).toInt() shl 16 or
                (bgColor.green * 255).toInt() shl 8 or
                (bgColor.blue * 255).toInt()
            window.window?.let { win ->
                if (state.isCompact) {
                    // 硬件键盘候选栏模式：窗口透明
                    win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    win.setDimAmount(0f)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        win.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
                    }
                } else if (state.isFloatingMode) {
                    win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    win.setDimAmount(0f)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        win.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
                    }
                } else {
                    // 非浮动模式：参考成熟输入法 FULL 方案的背景/高度布局。
                    // 1) edge-to-edge：窗口绘制到系统导航栏后面，键盘背景（渐变/图片）可延伸到底部；
                    // 2) 窗口背景透明：键盘内容由 Compose 绘制，键盘上方露出应用内容而不是白色/主题色块；
                    // 3) 导航栏透明 + 关闭强制对比度：底部导航栏区域由键盘背景覆盖，不会露出系统白色。
                    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(win, false)
                    win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    win.setDimAmount(0f)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        win.isNavigationBarContrastEnforced = false
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        win.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    win.decorView?.let { decor ->
                        val controller = androidx.core.view.WindowInsetsControllerCompat(win, decor)
                        controller.isAppearanceLightNavigationBars = !isDark
                    }
                }
                // setDecorFitsSystemWindows(false) 后必须重新分发 insets，
                // 否则 onApplyWindowInsets 不会触发、底部导航栏高度检测不到。
                win.decorView?.requestApplyInsets()
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyWindowBackground failed", e)
        }
    }

    private fun applyCompactMode() {
        val current = uiState.value
        val isCompact = hasHardwareKeyboard
        if (current.isCompact != isCompact) {
            uiState.value = current.copy(isCompact = isCompact)
            if (isCompact) {
                window.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
        }
    }

    private fun moveFloatingWindow(dx: Int, dy: Int) {
        window.window?.let { win ->
            val lp = win.attributes
            if (lp.gravity != (android.view.Gravity.TOP or android.view.Gravity.START)) {
                lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
            lp.x = (lp.x + dx).coerceAtLeast(0)
            lp.y = (lp.y + dy).coerceAtLeast(0)
            win.attributes = lp
        }
    }

    private fun updateEnterKeyText(editorInfo: EditorInfo) {
        val imeOptions = editorInfo.imeOptions
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val enterText = when {
            noEnterAction -> "换行"
            action == EditorInfo.IME_ACTION_GO -> "前往"
            action == EditorInfo.IME_ACTION_SEARCH -> "搜索"
            action == EditorInfo.IME_ACTION_SEND -> "发送"
            action == EditorInfo.IME_ACTION_NEXT -> "下一项"
            action == EditorInfo.IME_ACTION_DONE -> "完成"
            else -> "换行"
        }
        uiState.value = uiState.value.copy(enterKeyText = enterText)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        inlineSuggestionManager?.clear()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        clearInputState()
        recentClipboardItemsState.value = emptyList()
    }
    
    override fun onWindowHidden() {
        super.onWindowHidden()
        clearInputState()
        recentClipboardItemsState.value = emptyList()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        clipboardSyncBridge?.pullOnce()
    }
    
    private fun clearInputState() {
        calculatorEngine.clear()
        rimeEngine.clearComposition()
        t9PartialSegments.clear()
        // 输入法隐藏/结束输入：静默停止语音会话，丢弃未识别文本，避免迟到结果写入新输入框
        if (uiState.value.isVoiceMode || voiceRecordingStarted) {
            voiceRecognitionHandler.abandonSession()
            voiceRecognitionHandler.stopRecognition()
            voiceRecognitionHandler.cancelPreStart()
            isTrackingVoiceButtons = false
            voiceRecordingStarted = false
            voiceAmplitudeState.floatValue = 0f
            uiState.value = uiState.value.copy(
                isVoiceMode = false,
                voiceSticky = false,
                voiceButtonState = VoiceButtonState(),
                voiceRecognitionState = RecognitionState.IDLE,
                voiceRecognizedText = "",
                voiceAmplitude = 0f
            )
            keyboardViewModel.exitVoice()
        }
        uiState.value = uiState.value.copy(
            t9ResetSignal = uiState.value.t9ResetSignal + 1,
            t9RightCandidateSelectedCount = 0,
            t9SelectedCandidatePinyin = ""
        )
        candidateState.value = candidateState.value.copy(
            candidates = emptyList(),
            candidateComments = emptyList(),
            inputText = "",
            isComposing = false,
            isShowingRecentClipboard = false,
            associationCandidates = emptyList(),
            pendingEnglishText = "",
            hasNextPage = false,
            hasPrevPage = false
        )
        endComposingInputBox()
    }

    /**
     * 结束输入框中的 composing span，先清空内容再结束，避免转为 committed text。
     *
     * 无论输入位置设置（输入框/候选栏）都执行：英文输入（pendingEnglishText）始终通过
     * setComposingText 写入编辑器，清空时若跳过会残留英文 composing 文本。
     * 中文候选栏模式下编辑器无 composing 文本，本方法为空操作。
     */
    internal fun endComposingInputBox() {
        currentInputConnection?.let {
            it.setComposingText("", 0)
            it.finishComposingText()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sharedPrefsListener?.let {
            SettingsPreferences.getPrefsPublic(this).unregisterOnSharedPreferenceChangeListener(it)
        }
        RimeEngine.setDeploymentCallback { _, _ -> }
        stopClipboardSync()
        if (::clipboardManager.isInitialized) {
            clipboardManager.release()
        }
        _viewModelStore.clear()
        feedbackManager.release()
        rimeEngine.destroy()
        AssociationManager.release()
        voiceRecognitionHandler.release()
        com.kingzcheung.xime.handwriting.HandwritingEngine.release()
        ExtensionManager.release()
        com.kingzcheung.xime.association.NativeOnnxEngine.releaseSharedEnv()
        serviceScope.cancel()
        keyProcessingDispatcher.close()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
    
    internal fun hideKeyboard() {
        clearInputState()
        requestHideSelf(0)
    }
    
    internal fun updateUI() {
        sessionController.applyComposition(rimeEngine.getComposition())
    }

    /**
     * 用户开始输入时清除候选栏中的 inline suggestions，让位于正常输入候选。
     */
    internal fun dismissInlineSuggestions() {
        inlineSuggestionManager?.clear()
    }


    

    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (inlineSuggestionManager == null) return null
        updateInlineSuggestionTheme()
        val result = inlineSuggestionManager.onCreateInlineSuggestionsRequest(uiExtras)
        return result
    }

    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return inlineSuggestionManager?.onInlineSuggestionsResponse(response) ?: false
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun updateInlineSuggestionTheme() {
        val state = uiState.value
        val isDark = when (state.darkMode) {
            1 -> true
            2 -> (resources.configuration.uiMode.and(
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            )) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> false
        }
        val t = com.kingzcheung.xime.ui.theme.KeyboardThemes
        inlineSuggestionManager?.apply {
            val c = t.getCandidateTextColor(state.themeId, isDark)
            candidateTextColorArgb = (c.alpha * 255).toInt() shl 24 or
                (c.red * 255).toInt() shl 16 or
                (c.green * 255).toInt() shl 8 or
                (c.blue * 255).toInt()
            val label = c.copy(alpha = 0.6f)
            labelTextColorArgb = (label.alpha * 255).toInt() shl 24 or
                (label.red * 255).toInt() shl 16 or
                (label.green * 255).toInt() shl 8 or
                (label.blue * 255).toInt()
            isDarkTheme = isDark
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        val state = uiState.value
        if (state.isCompact) {
            try {
                val decor = window.window?.decorView
                if (decor != null) {
                    val navBarBg = decor.findViewById<View>(android.R.id.navigationBarBackground)
                    val navBarH = navBarBg?.height ?: 0
                    val h = (decor.height - navBarH).coerceAtLeast(0)
                    outInsets.contentTopInsets = h
                    outInsets.visibleTopInsets = h
                    outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
                    return
                }
            } catch (_: Exception) { }
            super.onComputeInsets(outInsets)
        } else if (state.isFloatingMode) {
            outInsets.apply {
                contentTopInsets = resources.displayMetrics.heightPixels
                visibleTopInsets = resources.displayMetrics.heightPixels
                touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                val decor = window.window?.decorView ?: return
                if (currentEffectiveKeyboardHeight <= 0) {
                    val isLandscape = resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
                    val kbH = SettingsPreferences.getKeyboardHeightDp(this@XimeInputMethodService, isLandscape)
                        .coerceAtMost((resources.configuration.screenHeightDp * 8) / 10)
                    currentEffectiveKeyboardHeight = kbH + 18 + 50 + state.keyboardBottomPaddingDp
                }
                val density = resources.displayMetrics.density
                val inputViewWidthPx = decor.width
                val statusBarHeightDp = tryGetStatusBarHeightDp(this@XimeInputMethodService, window.window)
                val physicalHeightPx = resources.displayMetrics.heightPixels
                val inputViewHeightPx = (physicalHeightPx - (statusBarHeightDp * density).toInt()).coerceAtLeast(1)
                val cardWidthPx = (inputViewWidthPx * 0.85f).toInt()
                val leftPaddingPx = ((inputViewWidthPx - cardWidthPx) / 2f).toInt()
                val offsetXPx = (state.floatingOffsetX * density).toInt()
                val cardHeightPx = (currentEffectiveKeyboardHeight * density).toInt()
                val offsetYPx = (state.floatingOffsetY * density).toInt()
                touchableRegion.set(
                    leftPaddingPx + offsetXPx,
                    inputViewHeightPx - cardHeightPx - offsetYPx,
                    leftPaddingPx + offsetXPx + cardWidthPx,
                    inputViewHeightPx - offsetYPx
                )
            }
        } else {
            // 非浮动模式：窗口全屏，键盘内容贴底。
            // contentTopInsets 直接使用 Compose 实测的键盘内容顶部位置（px），
            // 避免 window 全屏后 super 误判键盘占满全屏导致布局下沉。
            if (keyboardContentTopPx > 0) {
                outInsets.contentTopInsets = keyboardContentTopPx
                outInsets.visibleTopInsets = keyboardContentTopPx
                outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            } else {
                super.onComputeInsets(outInsets)
            }
        }
    }

    override fun commitText(text: String) {
        if (uiState.value.quickSendFormFocused) {
            mainHandler.post {
                QuickSendFormEditTextHolder.editText?.let { et ->
                    val start = et.selectionStart.coerceAtLeast(0)
                    val textLen = text.length
                    et.text?.replace(start, et.selectionEnd.coerceAtLeast(start), text)
                    try { et.setSelection(start + textLen) } catch (_: Exception) {}
                }
            }
            return
        }
        currentInputConnection?.commitText(text, 1)

        if (isChineseMode) {
            predictionManager.appendCommittedText(text)
            predictionManager.recordInput(text)

            mainHandler.post {
                if (!uiState.value.isAsciiMode) {
                    getPredictionFromPlugin(predictionManager.lastCommittedText)
                }
            }
        }
    }
    
    
}
