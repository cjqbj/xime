package com.kingzcheung.xime.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputConnection
import android.widget.Toast
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.speech.AsrBackendFactory
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.speech.SpeechRecognitionManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger

class VoiceRecognitionHandler(
    private val context: Context,
    private val onStateChanged: (InputUIState) -> Unit,
    private val getState: () -> InputUIState,
    private val getInputConnection: () -> InputConnection?,
    private val onVoiceComplete: () -> Unit = {},
    private val onAmplitudeChanged: (Float) -> Unit = {},
    private val onSpectrumChanged: (FloatArray) -> Unit = {}
) {
    companion object {
        private const val TAG = "VoiceRecognition"
        // 抬手后等待完整 final 的时长：热按实测 final 约 1.2s 到（含 400ms 尾音 +
        // 500ms 尾静音 + finalize）。超时未到才兜底提交最后 partial，防止 IPC 丢结果
        private const val RELEASE_COMMIT_FALLBACK_MS = 2_000L
        // 兜底已提交后，允许迟到 final 安全替换的时间窗
        private const val REPLACE_WINDOW_MS = 3_000L
    }

    private lateinit var speechRecognitionManager: SpeechRecognitionManager

    var textBeforeVoiceInput = ""
    var textLengthBeforeVoiceInput = 0

    fun initialize() {
        FileLogger.i(TAG, "Initializing speech recognition system")

        speechRecognitionManager = SpeechRecognitionManager(context)

        speechRecognitionManager.setCallbacks(
            onResult = { text ->
                handleSpeechResult(text)
            },
            onPartialResult = { text ->
                handlePartialResult(text)
            },
            onStateChange = { state ->
                handleSpeechStateChange(state)
            },
            onError = { error, userVisible ->
                handleSpeechError(error, userVisible)
            },
            onAmplitude = { amplitude ->
                handleAmplitudeUpdate(amplitude)
            },
            onSpectrum = { spectrum ->
                handleSpectrumUpdate(spectrum)
            }
        )

        val providerName = resolveProviderName()

        onStateChanged(getState().copy(voicePluginName = providerName))
        FileLogger.i(TAG, "STT provider: $providerName")

        // 若"使用本地模型"开关已开启，启动时即加载模型并常驻，
        // 保证语音时绝不现场加载模型（避免丢开头音频）
        if (SettingsPreferences.isSttUseLocal(context) &&
            AsrBackendFactory.getLocalName() != null
        ) {
            Thread {
                AsrBackendFactory.warmup(context)
            }.start()
        }
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val delayedPreStartRunnable = Runnable {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.startPreStart()
        }
    }

    fun startDelayedPreStart(delayMs: Long = 150) {
        mainHandler.removeCallbacks(delayedPreStartRunnable)
        mainHandler.postDelayed(delayedPreStartRunnable, delayMs)
    }

    fun cancelPreStart() {
        mainHandler.removeCallbacks(delayedPreStartRunnable)
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.cancelPreStart()
        }
    }

    fun startRecognition() {
        if (!::speechRecognitionManager.isInitialized) {
            Log.e(TAG, "speechRecognitionManager not initialized")
            onStateChanged(getState().copy(
                isVoiceMode = false,
                voiceSticky = false,
                voiceRecognitionState = RecognitionState.ERROR
            ))
            return
        }

        // 新链路下录音线程在 startRecognition() 中已立即开麦；先前排队的预启动
        // 回调若再执行会重复创建第二个 AudioRecord，两个录音实例并发抢占输入流，
        // 会造成实时音频异常（冷模型走缓存回放时不易暴露，热模型实时推送时识别为空）
        mainHandler.removeCallbacks(delayedPreStartRunnable)
        speechRecognitionManager.cancelPreStart()

        textBeforeVoiceInput = getInputConnection()?.getTextBeforeCursor(1000, 0)?.toString() ?: ""
        textLengthBeforeVoiceInput = textBeforeVoiceInput.length

        val providerName = resolveProviderName()
        onStateChanged(getState().copy(voicePluginName = providerName))

        speechRecognitionManager.startRecognition()
    }

    fun stopRecognition() {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.stopRecognition()
        }
        // handleFinalResult is now called from within handleSpeechResult
        // when the final stopRecognition result arrives
    }

    fun release() {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.release()
        }
    }

    fun isInitialized(): Boolean = ::speechRecognitionManager.isInitialized

    private fun resolveProviderName(): String {
        // 用户开启"本地识别"且当前构建支持离线语音时，优先显示本地引擎名
        if (SettingsPreferences.isSttUseLocal(context)) {
            val localName = AsrBackendFactory.getLocalName()
            if (localName != null) return localName
        }
        val enabledPlugins = ExtensionManager.getEnabledAsrPlugins(context)
        if (enabledPlugins.isNotEmpty()) {
            val selectedId = SettingsPreferences.getSttOnlinePluginId(context)
            val plugin = enabledPlugins.firstOrNull { it.first == selectedId }?.second
                ?: enabledPlugins.firstOrNull()?.second
            if (plugin != null) return plugin.getDisplayName()
        }
        return "未配置"
    }

    private var lastPartialText = ""
    private var lastAmplitudeUpdate = 0L
    private var smoothedAmplitude = 0f
    private var smoothedSpectrum = FloatArray(16)
    // 抬手后进入"等完整 final"状态：partial 回调忽略，final 到则一次性上屏完整句
    private var suppressDuplicateFinal = false
    // 抬手后正在等待 final（兜底提交尚未执行）
    private var releaseAwaitingFinal = false
    // 超时兜底已把 partial 提交上屏
    private var releaseCommitted = false
    // 兜底提交的完整文本（含标点）与时间：极少数 final 晚于兜底到时，仅在能安全
    // 校验光标前缀的普通 app 内替换；Termux 等终端读不到前缀也删不准，一律不替换
    private var committedOnReleaseFull = ""
    private var committedOnReleaseAtMs = 0L
    // 输入法窗口隐藏等场景：丢弃本会话，迟到结果不得写入任何输入框
    private var sessionAbandoned = false
    // 抬手后录音线程仍在 finalize（采尾音/等模型/等 final 回调）：UI 保持"识别中"
    @Volatile
    private var releaseFinalizing = false

    fun isFinalizing(): Boolean = releaseFinalizing

    private var errorToast: Toast? = null

    private val releaseFallbackRunnable = Runnable {
        if (!releaseAwaitingFinal) return@Runnable
        val partial = lastPartialText
        val ic = getInputConnection()
        if (ic != null && partial.isNotEmpty()) {
            val punctuated = addPunctuation(partial)
            commitFinal(ic, punctuated, partial)
            committedOnReleaseFull = punctuated
            committedOnReleaseAtMs = System.currentTimeMillis()
            Log.d(TAG, "Release fallback committed partial after timeout: '$punctuated'")
        }
        releaseCommitted = true
        releaseAwaitingFinal = false
    }

    private fun resetReleaseState() {
        mainHandler.removeCallbacks(releaseFallbackRunnable)
        suppressDuplicateFinal = false
        releaseAwaitingFinal = false
        releaseCommitted = false
        releaseFinalizing = false
        committedOnReleaseFull = ""
    }

    /** 输入法隐藏/切换输入框时调用：丢弃当前会话的未识别文本，忽略迟到的最终结果 */
    fun abandonSession() {
        sessionAbandoned = true
        lastPartialText = ""
        resetReleaseState()
    }

    // 语音按钮长按抬起时调用：不立即上屏 partial，而是等待完整 final（实测约 1.2s）
    // 一次性提交，避免"先上屏半句→删除重提"在 Termux 等终端删不干净产生重复文本；
    // final 超时丢失时由兜底 Runnable 提交最后 partial
    fun commitPendingOnRelease() {
        if (sessionAbandoned) return
        val partial = lastPartialText
        Log.d(TAG, "commitPendingOnRelease: ic=${getInputConnection() != null}, partial='$partial', suppress=$suppressDuplicateFinal")
        // 抬手后进入 finalize 等待（冷按 partial 为空也要等：final 是唯一上屏路径，
        // 录音线程有界等待模型 20s）；UI 据此保持"识别中"直到 final/错误
        releaseFinalizing = true
        if (partial.isEmpty()) return
        suppressDuplicateFinal = true
        releaseAwaitingFinal = true
        releaseCommitted = false
        committedOnReleaseFull = ""
        mainHandler.removeCallbacks(releaseFallbackRunnable)
        mainHandler.postDelayed(releaseFallbackRunnable, RELEASE_COMMIT_FALLBACK_MS)
    }

    private fun handleSpeechResult(text: String) {
        Log.d(TAG, "Speech result (final): $text")

        if (sessionAbandoned) {
            sessionAbandoned = false
            lastPartialText = ""
            resetReleaseState()
            onVoiceComplete()
            return
        }

        if (releaseAwaitingFinal || releaseCommitted) {
            mainHandler.removeCallbacks(releaseFallbackRunnable)
            val cleanText = text.replace(" ", "").trim()
            if (!releaseCommitted) {
                // final 在兜底超时前到达：直接一次性提交完整句，不做任何删除操作，
                // Termux 等终端也安全
                val ic = getInputConnection()
                if (ic != null && cleanText.isNotEmpty() && !cleanText.startsWith("错误:")) {
                    commitFinal(ic, addPunctuation(cleanText), lastPartialText)
                }
            } else {
                // 兜底已提交 partial，final 更晚才到：仅在能读光标前缀的普通 app 替换
                replaceCommittedIfVerified(cleanText)
            }
            lastPartialText = ""
            resetReleaseState()
            onVoiceComplete()
            return
        }

        val cleanText = text.replace(" ", "")
        val ic = getInputConnection()
        if (ic != null && cleanText.isNotEmpty() && !cleanText.startsWith("错误:")) {
            val punctuatedText = addPunctuation(cleanText)
            commitFinal(ic, punctuatedText, lastPartialText)
        }
        lastPartialText = ""
        onVoiceComplete()
    }

    /**
     * 兜底已上屏 partial 后迟到的更完整 final：只在 InputConnection 能读回光标前缀、
     * 且前缀确实以已提交文本结尾时才删除重提。Termux 等终端 getTextBeforeCursor
     * 返回空串且 deleteSurroundingText 行为不可靠，直接放弃替换（保留兜底文本，
     * 宁可丢尾字也绝不产生重复乱序文本）。
     */
    private fun replaceCommittedIfVerified(cleanFinal: String) {
        val committed = committedOnReleaseFull
        if (committed.isEmpty()) return
        if (System.currentTimeMillis() - committedOnReleaseAtMs > REPLACE_WINDOW_MS) {
            Log.d(TAG, "Fuller final arrived too late; skip replace")
            return
        }
        if (cleanFinal.isEmpty() || cleanFinal.startsWith("错误:")) return
        val committedCore = committed.trimEnd(*"。！？，、；：,.!?;:".toCharArray())
        if (cleanFinal.length <= committedCore.length) return

        val ic = getInputConnection() ?: return
        val before = ic.getTextBeforeCursor(committed.length + 4, 0)?.toString()
        if (before.isNullOrEmpty() || !before.endsWith(committed)) {
            Log.d(TAG, "Fuller final but prefix unverifiable (beforeLen=${before?.length ?: -1}); skip replace")
            return
        }
        ic.finishComposingText()
        ic.deleteSurroundingText(committed.length, 0)
        val replacement = addPunctuation(cleanFinal)
        ic.commitText(replacement, 1)
        Log.d(TAG, "Replaced fallback text with verified final: '$committed' -> '$replacement'")
    }
    
    // 增量语音模式：先结束 composing，再只提交增量，避免重复与整段重写。
    private fun commitFinal(ic: InputConnection, finalText: String, partial: String) {
        ic.finishComposingText()
        if (partial.isNotEmpty() && finalText.startsWith(partial)) {
            val remainder = finalText.substring(partial.length)
            if (remainder.isNotEmpty()) {
                ic.commitText(remainder, 1)
            } else {
                Log.d(TAG, "commitFinal: remainder empty, only finished composing")
            }
        } else {
            // 最终结果与部分结果不一致：删除已上屏的部分，再提交完整结果
            if (partial.isNotEmpty()) {
                ic.deleteSurroundingText(partial.length, 0)
            }
            ic.commitText(finalText, 1)
        }
        Log.d(TAG, "commitFinal: final='$finalText', partial='$partial'")
    }
    
    private fun addPunctuation(text: String): String {
        val cleanText = text.trim().replace(" ", "")
        if (cleanText.isEmpty()) return text

        // 若文本末尾已带句末标点（如 funasr/volc 等自带标点的后端），不再追加，避免"。。"
        if (cleanText.last() in "。！？；：，、；：,.!?;:，") return cleanText

        return "$cleanText${heuristicPunctuation(cleanText)}"
    }

    private fun heuristicPunctuation(text: String): String {
        return when {
            text.any { it in "吗呢么吧" } || text.contains("什么") || text.contains("怎么") || text.contains("为什么") || text.contains("如何") || text.contains("哪") -> "？"
            text.length < 4 -> "，"
            else -> "。"
        }
    }

    private fun handlePartialResult(text: String) {
        // 等待 final 期间继续让尾音 partial 刷新 composing：最终 final 多以 partial 为
        // 前缀，只需补提交增量（Termux 等终端 composing 已落盘时同样正确）；
        // 兜底已提交或会话丢弃后才屏蔽，避免污染已上屏文本
        if (sessionAbandoned || releaseCommitted) return
        if (text == lastPartialText) return
        lastPartialText = text
        Log.d(TAG, "Speech result (partial): $text")
        
        // 过滤掉空格，避免显示空白
        val cleanText = text.replace(" ", "")
        if (cleanText.isEmpty()) return
        
        val ic = getInputConnection()
        if (ic != null) {
            ic.setComposingText(cleanText, 1)
        }
        onStateChanged(getState().copy(voiceRecognizedText = cleanText))
    }

    private fun handleSpeechStateChange(state: RecognitionState) {
        Log.d(TAG, "Speech state changed: $state")
        if (state == RecognitionState.LISTENING) {
            lastPartialText = ""
            sessionAbandoned = false
            resetReleaseState()
        } else if (state == RecognitionState.IDLE) {
            // AsrStop 线程保证 IDLE 在 final 回调之后：收尾完成，复位抬手等待态
            releaseFinalizing = false
        }
        onStateChanged(getState().copy(voiceRecognitionState = state))
    }

    private fun handleSpeechError(error: String, userVisible: Boolean) {
        Log.e(TAG, "Speech error: $error")
        FileLogger.e(TAG, "Speech error: $error")
        lastPartialText = ""
        resetReleaseState()
        if (userVisible && error.isNotBlank()) {
            errorToast?.cancel()
            errorToast = Toast.makeText(context, error, Toast.LENGTH_LONG)
            errorToast?.show()
        }
        onVoiceComplete()
    }

    private fun handleAmplitudeUpdate(amplitude: Float) {
        val now = System.currentTimeMillis()
        if (now - lastAmplitudeUpdate < 80) return
        lastAmplitudeUpdate = now
        smoothedAmplitude = smoothedAmplitude * 0.45f + amplitude * 0.55f
        onAmplitudeChanged(smoothedAmplitude)
    }

    private fun handleSpectrumUpdate(spectrum: FloatArray) {
        val smoothed = smoothedSpectrum
        for (i in spectrum.indices) {
            smoothed[i] = smoothed[i] * 0.5f + spectrum[i] * 0.5f
        }
        onSpectrumChanged(smoothed.copyOf())
    }
}