package com.kingzcheung.xime.speech

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log

import androidx.annotation.RequiresPermission
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger

class SpeechRecognitionManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechRecognitionManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_SECONDS = 0.1f
        private const val SPEECH_THRESHOLD = 25
        // 引擎就绪前允许缓存的最长语音时长（模型加载通常 2~5s）：20s，约 640KB
        private const val MAX_PENDING_SECONDS = 20
        // 手指抬起时模型仍在加载：有界等待加载完成以识别缓存语音，超时放弃
        private const val FINISH_WAIT_BACKEND_MS = 20_000L
        // 抬手后继续排空麦克风的宽限时长：用户说完到抬手之间，最后若干 100ms 块
        // 可能还停在 AudioRecord/驱动缓冲里，立即停止会丢掉整句最后一两个字
        private const val TAIL_CAPTURE_MS = 400L
        // finalize 前补送的尾部静音时长：流式 Zipformer 需要尾静音才能把最后一个
        // token 合并输出（JNI Finalize 仅 InputFinished，不内部补零）
        private const val TAIL_SILENCE_MS = 500
    }

    private var backend: AsrBackend? = null
    private var recordingThread: RecordingThread? = null
    private var pendingAudioArchive: AudioArchive? = null
    private var audioFallbackRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 会话序号：用于区分连续语音会话，防止旧会话的回收线程误释放新会话的后端
    private var sessionId = 0
    // 后台加载 ASR 模型的进行中标记与取消标记
    @Volatile
    private var loadingInProgress = false
    @Volatile
    private var loadingCancelled = false

    private var resultCallback: ((String) -> Unit)? = null
    private var partialResultCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    private var errorCallback: ((String, Boolean) -> Unit)? = null
    private var amplitudeCallback: ((Float) -> Unit)? = null
    private var spectrumCallback: ((FloatArray) -> Unit)? = null

    // 预启动的 AudioRecord：手指按下后立即启动，语音激活时直接交给录音线程
    private var preStartedRecord: AudioRecord? = null
    private val preStartTimeoutRunnable = Runnable { cancelPreStart() }

    // 录音时静音其他应用：持有音频焦点的监听器；非 null 表示本会话已申请焦点
    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    /**
     * 按设置申请音频焦点：暂停/降低抖音、音乐等其他应用播放。
     * 用 TRANSIENT 焦点——多数媒体 App 收到后会暂停播放，松手放弃焦点后由用户自行恢复。
     */
    @Suppress("DEPRECATION")
    private fun acquireAudioFocusIfNeeded() {
        if (audioFocusListener != null) return
        if (!SettingsPreferences.isSttMuteOthers(context)) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val listener = AudioManager.OnAudioFocusChangeListener { }
        val result = am.requestAudioFocus(
            listener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        )
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusListener = listener
            FileLogger.i(TAG, "Audio focus acquired: other apps muted during recording")
        } else {
            FileLogger.w(TAG, "Audio focus request failed: $result")
        }
    }

    @Suppress("DEPRECATION")
    private fun releaseAudioFocus() {
        val listener = audioFocusListener ?: return
        audioFocusListener = null
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        am.abandonAudioFocus(listener)
        FileLogger.i(TAG, "Audio focus released: other apps unmuted")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecognition() {
        if (recordingThread != null) {
            FileLogger.w(TAG, "Recognition already running, ignoring start request")
            return
        }

        FileLogger.i(TAG, "Starting speech recognition (capture first)")
        stateCallback?.invoke(RecognitionState.PROCESSING)

        // 开关开启时请求音频焦点，暂停/降低其他应用播放（抖音、音乐等）
        acquireAudioFocusIfNeeded()

        synchronized(preloadLock) { sessionId++ }
        audioFallbackRunnable?.let(mainHandler::removeCallbacks)
        audioFallbackRunnable = null

        // 模型加载与麦克风采集并行：加载可能耗时数秒（154MB 模型），
        // 先让它在后台跑起来；录音线程同步开麦，PCM 先进内存缓冲，
        // 引擎就绪后按序回放，保证按下瞬间说的开头语音不丢。
        ensureBackendLoading()

        // 预启动的 AudioRecord 若已运行直接交给录音线程，否则线程内自建
        var preStarted: AudioRecord? = null
        synchronized(this) {
            preStarted = preStartedRecord
            preStartedRecord = null
        }
        mainHandler.removeCallbacks(preStartTimeoutRunnable)

        val thread = RecordingThread(preStarted)
        recordingThread = thread
        thread.start()
    }

    /** 后台线程加载 ASR 后端（模型），与录音采集并行；重复调用幂等。 */
    private fun ensureBackendLoading() {
        synchronized(preloadLock) {
            if (backend != null || loadingInProgress) return
            loadingInProgress = true
            loadingCancelled = false
        }
        Thread({
            try {
                val ok = preload()
                if (!ok) {
                    mainHandler.post {
                        errorCallback?.invoke("无法初始化语音引擎，请检查本地模型或在线语音插件配置", true)
                        stateCallback?.invoke(RecognitionState.ERROR)
                    }
                }
            } finally {
                synchronized(preloadLock) {
                    loadingInProgress = false
                    preloadLock.notifyAll()
                }
            }
        }, "AsrBackendLoad").start()
    }

    fun stopRecognition() {
        Log.d(TAG, "Stopping recognition")
        val thread = recordingThread
        if (thread == null) {
            mainHandler.post {
                stateCallback?.invoke(RecognitionState.IDLE)
            }
            return
        }
        recordingThread = null
        val session = synchronized(preloadLock) { sessionId }
        // 手指抬起：完成识别。模型仍在加载时也不取消，录音线程会有界等待
        // 加载完成并识别缓存的整段语音（用户可立即继续其它操作，结果迟到上屏）
        thread.requestFinish()
        Thread({
            try {
                thread.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            // release() 含跨进程 IPC，放到后台线程执行，避免阻塞主线程；
            // 仅当会话序号未变化时释放并置空，避免误释放新会话正在使用的后端
            val b = synchronized(preloadLock) {
                if (sessionId == session) {
                    val tmp = backend
                    backend = null
                    tmp
                } else null
            }
            b?.release()
            releaseAudioFocus()
            mainHandler.post {
                stateCallback?.invoke(RecognitionState.IDLE)
            }
            scheduleAudioFallback()
        }, "AsrStop").start()
    }

    fun cancelRecognition() {
        Log.d(TAG, "Canceling recognition")
        val thread = recordingThread
        if (thread == null) {
            // 模型仍在后台加载中：标记取消，加载完成后不再启动录音
            if (loadingInProgress) {
                loadingCancelled = true
                releaseAudioFocus()
                mainHandler.post {
                    stateCallback?.invoke(RecognitionState.IDLE)
                }
            }
            return
        }
        recordingThread = null
        val session = synchronized(preloadLock) { sessionId }
        // 放弃本会话：立即丢弃缓存语音，不等待模型加载
        thread.requestDiscard()
        Thread({
            try {
                thread.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val b = synchronized(preloadLock) {
                if (sessionId == session) {
                    val tmp = backend
                    backend = null
                    tmp
                } else null
            }
            b?.release()
            releaseAudioFocus()
            mainHandler.post {
                stateCallback?.invoke(RecognitionState.IDLE)
            }
            scheduleAudioFallback()
        }, "AsrCancel").start()
    }

    private fun scheduleAudioFallback() {
        audioFallbackRunnable?.let(mainHandler::removeCallbacks)
        val fallback = Runnable { finalizeAudio(null) }
        audioFallbackRunnable = fallback
        mainHandler.postDelayed(fallback, 2000L)
    }

    private fun finalizeAudio(text: String?) {
        val archive = synchronized(this) {
            val current = pendingAudioArchive
            pendingAudioArchive = null
            current
        } ?: return
        archive.renameWithText(text)
    }

    fun setCallbacks(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null,
        onStateChange: (RecognitionState) -> Unit,
        onError: (message: String, userVisible: Boolean) -> Unit,
        onAmplitude: ((Float) -> Unit)? = null,
        onSpectrum: ((FloatArray) -> Unit)? = null
    ) {
        resultCallback = onResult
        partialResultCallback = onPartialResult
        stateCallback = onStateChange
        errorCallback = onError
        amplitudeCallback = onAmplitude
        spectrumCallback = onSpectrum
    }

    fun startPreStart() {
        cancelPreStart()
        val record = createAudioRecord() ?: return
        record.startRecording()
        synchronized(this) {
            preStartedRecord = record
        }
        mainHandler.removeCallbacks(preStartTimeoutRunnable)
        mainHandler.postDelayed(preStartTimeoutRunnable, 2000)
    }

    fun cancelPreStart() {
        mainHandler.removeCallbacks(preStartTimeoutRunnable)
        synchronized(this) {
            val record = preStartedRecord
            preStartedRecord = null
            if (record != null) {
                try { record.stop() } catch (_: Exception) { }
                record.release()
            }
        }
    }

    fun release() {
        Log.d(TAG, "Releasing speech recognition")
        cancelPreStart()
        cancelRecognition()
        val b = synchronized(preloadLock) {
            val tmp = backend
            backend = null
            tmp
        }
        if (b != null) {
            // release() 含跨进程 IPC，放到后台线程执行，避免阻塞主线程（onDestroy 等场景）
            Thread {
                b.release()
            }.start()
        }
    }

    private var isPreloading = false
    private val preloadLock = Object()

    fun getState(): RecognitionState {
        return backend?.getState() ?: RecognitionState.IDLE
    }

    fun preload(): Boolean {
        synchronized(preloadLock) {
            if (backend != null) return true
            isPreloading = true
        }

        val newBackend = createBackend()
        if (newBackend == null) {
            synchronized(preloadLock) {
                isPreloading = false
                preloadLock.notifyAll()
            }
            return false
        }

        newBackend.setCallbacks(
            onResult = { text -> handleResult(text) },
            onPartialResult = { text -> handlePartialResult(text) },
            onStateChange = { state -> stateCallback?.invoke(state) },
            onError = { error -> handleError(error) }
        )

        if (!newBackend.initialize()) {
            synchronized(preloadLock) {
                isPreloading = false
                preloadLock.notifyAll()
            }
            return false
        }

        synchronized(preloadLock) {
            backend = newBackend
            isPreloading = false
            preloadLock.notifyAll()
        }

        return true
    }

    private fun createBackend(): AsrBackend? {
        // 用户开启"本地识别"时才使用离线后端，否则走在线插件
        val useLocal = SettingsPreferences.isSttUseLocal(context)
        return if (useLocal) {
            AsrBackendFactory.create(context) ?: createOnlineAsrBackend()
        } else {
            createOnlineAsrBackend()
        }
    }

    private fun createOnlineAsrBackend(): AsrBackend? {
        val enabledPlugins = ExtensionManager.getEnabledAsrPlugins(context)
        if (enabledPlugins.isEmpty()) return null

        val selectedId = SettingsPreferences.getSttOnlinePluginId(context)
        val selected = enabledPlugins.firstOrNull { it.first == selectedId }
            ?: enabledPlugins.firstOrNull()
        val (_, plugin) = selected ?: return null

        val backend = plugin.createBackend(context.applicationContext)
        return PluginAsrBackendAdapter(plugin.getDisplayName(), backend)
    }

    private fun createAudioRecord(bufferSecs: Float = 2.0f): AudioRecord? {
        val bufferSize = (SAMPLE_RATE * bufferSecs).toInt()
        return try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                null
            } else {
                record
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            null
        }
    }

    /**
     * 录音采集线程：开麦不等待模型。
     *
     * 引擎就绪前读取到的 PCM 块进入 [pendingChunks] 缓存（最长 20s）；
     * 引擎一旦就绪先 start()，再按序把缓存块经同一套 VAD 逻辑送入引擎，
     * 随后进入实时直送。手指抬起时若模型仍在加载，则有界等待加载完成，
     * 把缓存的整段语音识别完再产出最终结果。
     */
    private inner class RecordingThread(
        private val preStarted: AudioRecord? = null
    ) : Thread("AsrRecording") {

        private val spectrumAnalyzer = SpectrumAnalyzer()

        // 手指抬起=完成识别；release()/cancel=放弃，不等待模型
        @Volatile
        private var finishing = false
        @Volatile
        private var discarded = false

        // 引擎就绪前缓存的 PCM（16k/16bit/mono）
        private val pendingLock = Object()
        private val pendingChunks = ArrayDeque<ByteArray>()
        private var pendingBytes = 0
        private val maxPendingBytes = SAMPLE_RATE * 2 * MAX_PENDING_SECONDS

        // 本线程归属的会话序号；等待模型期间用户已开启新会话时，旧线程不得再触碰引擎
        private val mySession = synchronized(preloadLock) { sessionId }
        // 会话起点（按下时刻），用于度量引擎就绪延迟与缓存回放时长
        private val captureStartMs = System.currentTimeMillis()

        @Volatile
        private var engine: AsrBackend? = null
        @Volatile
        private var engineStarted = false
        // start() 已明确失败（如模型未下载）：收尾阶段不得再重试，避免错误/Toast 重复
        @Volatile
        private var startFailed = false

        private var speechDetected = false
        // 语音前缓冲：保存检测到语音前的若干块，检测到后一起送入 ASR，
        // 避免"你/觉"等弱开头的语音块因音量低于阈值被当作静音丢弃
        private val preSpeechBuffer = ArrayDeque<ByteArray>()
        private val maxPreSpeechChunks = 4  // 0.4s 语音前缓冲

        fun requestFinish() {
            // 不 interrupt：让阻塞在 audioRecord.read() 的采集线程自然返回，
            // 继续排空 TAIL_CAPTURE_MS 的尾音后再收尾（中断会直接丢弃最后一两个字）
            finishing = true
        }

        fun requestDiscard() {
            discarded = true
            finishing = true
            interrupt()
        }

        override fun run() {
            val audioRecord = preStarted ?: (createAudioRecord() ?: run {
                mainHandler.post {
                    errorCallback?.invoke("无法启动录音", false)
                    stateCallback?.invoke(RecognitionState.ERROR)
                }
                return
            })

            // 麦克风立即开启：不等模型加载，UI 立刻进入聆听态、频谱即时响应
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.startRecording()
            }
            mainHandler.post {
                stateCallback?.invoke(RecognitionState.LISTENING)
            }

            val audioArchive = AudioArchive.create()
            synchronized(this@SpeechRecognitionManager) {
                pendingAudioArchive = audioArchive
            }

            val buffer = ShortArray((SAMPLE_RATE * BUFFER_SIZE_SECONDS).toInt())
            val byteBuffer = ByteArray(buffer.size * 2)

            try {
                // 抬手后进入尾部宽限采集（最多 TAIL_CAPTURE_MS），把驱动缓冲里的
                // 最后几块语音读出来照常送引擎/入缓存；取消（discarded）立即结束
                var tailDeadline = 0L
                while (true) {
                    if (discarded || interrupted()) break
                    if (finishing) {
                        if (tailDeadline == 0L) {
                            tailDeadline = System.currentTimeMillis() + TAIL_CAPTURE_MS
                        } else if (System.currentTimeMillis() >= tailDeadline) {
                            break
                        }
                    }
                    val nread = audioRecord.read(buffer, 0, buffer.size)
                    if (nread > 0) {
                        var peak = 0
                        for (i in 0 until nread) {
                            val s = buffer[i].toInt()
                            byteBuffer[i * 2] = (s and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                            val abs = if (s < 0) -s else s
                            if (abs > peak) peak = abs
                        }
                        // 归一化振幅（0~1）与频段频谱，驱动频谱可视化
                        val normalized = (peak / 32768f).coerceIn(0f, 1f)
                        val spectrum = spectrumAnalyzer.analyze(buffer, nread)
                        mainHandler.post {
                            amplitudeCallback?.invoke(normalized)
                            spectrumCallback?.invoke(spectrum)
                        }
                        val chunk = byteBuffer.copyOf(nread * 2)
                        audioArchive?.write(chunk, chunk.size)
                        offerChunk(chunk)
                    } else if (nread < 0) {
                        break
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { audioRecord.stop() } catch (_: Exception) { }
                audioRecord.release()
            }

            // requestFinish()/requestDiscard() 的 interrupt() 仅用于唤醒阻塞在
            // audioRecord.read() 的采集循环；收尾阶段还要做跨进程 IPC（start/stop 内部
            // 是 runBlocking），残留中断会让它们立即抛 InterruptedException，必须清掉
            Thread.interrupted()

            finalizeAfterCapture(audioArchive)
        }

        /** 引擎未就绪则入缓存；就绪则先回放全部缓存再实时直送。 */
        private fun offerChunk(chunk: ByteArray) {
            if (engineStarted) {
                feedWithVad(chunk)
                return
            }
            val b = synchronized(preloadLock) { backend }
            if (b == null) {
                synchronized(pendingLock) {
                    pendingChunks.addLast(chunk)
                    pendingBytes += chunk.size
                    // 超长按 FIFO 丢弃最旧块（按住说话一般远小于 20s）
                    while (pendingBytes > maxPendingBytes && pendingChunks.isNotEmpty()) {
                        pendingBytes -= pendingChunks.removeFirst().size
                    }
                }
                return
            }
            if (discarded || !isCurrentSession()) return
            if (!startEngine(b)) return
            drainPending()
            feedWithVad(chunk)
        }

        private fun startEngine(b: AsrBackend): Boolean {
            if (engineStarted) return true
            if (startFailed) {
                // 首次 start 已失败并已提示，本次直接放弃，不再重复报错
                finishing = true
                return false
            }
            if (!b.start()) {
                startFailed = true
                mainHandler.post {
                    errorCallback?.invoke("启动引擎失败", false)
                    stateCallback?.invoke(RecognitionState.ERROR)
                }
                finishing = true
                return false
            }
            engine = b
            engineStarted = true
            FileLogger.i(
                TAG,
                "ASR engine started after ${System.currentTimeMillis() - captureStartMs}ms"
            )
            return true
        }

        private fun drainPending() {
            synchronized(pendingLock) {
                val count = pendingChunks.size
                // 缓存块全部来自用户按住录音期间，一块都不能被 VAD 阈值裁掉
                // （冷按轻声开头"你好"曾因此丢失）；回放直送引擎，不经过 feedWithVad
                val engineRef = engine
                while (pendingChunks.isNotEmpty()) {
                    val chunk = pendingChunks.removeFirst()
                    if (engineRef != null) {
                        try {
                            engineRef.processAudioChunk(chunk)
                        } catch (e: Exception) {
                            FileLogger.w(TAG, "replay chunk failed: ${e.message}")
                        }
                    }
                }
                pendingBytes = 0
                // 诊断：模型加载耗时期间被缓存、引擎就绪后补送的音频量（每块 100ms）
                if (count > 0) {
                    FileLogger.i(
                        TAG,
                        "Replayed $count buffered PCM chunks (~${count * 100}ms audio) " +
                            "after ${System.currentTimeMillis() - captureStartMs}ms engine wait"
                    )
                }
            }
        }

        /**
         * finalize 前补送尾部静音（绕过 VAD 直送引擎）。
         * 流式 Zipformer 需足够右上下文（尾静音帧）才能输出最后一个 token；
         * JNI Finalize 只做 InputFinished，不内部补零，故在采集侧补齐。
         */
        private fun feedTrailingSilence(b: AsrBackend) {
            val chunkSamples = (SAMPLE_RATE * 0.1f).toInt() // 100ms/块
            val silence = ByteArray(chunkSamples * 2)      // 16bit PCM，全 0 即静音
            val chunks = TAIL_SILENCE_MS / 100
            repeat(chunks) {
                try {
                    b.processAudioChunk(silence)
                } catch (_: Exception) { }
            }
        }

        private fun feedWithVad(chunk: ByteArray) {
            val b = engine ?: return
            if (!speechDetected) {
                preSpeechBuffer.addLast(chunk)
                // 缓冲满仍未检测到语音：放弃 VAD，直接开始识别，
                // 保证整段弱音内容也能送入 ASR（开头静音已被缓冲丢弃）
                if (preSpeechBuffer.size >= maxPreSpeechChunks) {
                    speechDetected = true
                    while (preSpeechBuffer.isNotEmpty()) {
                        b.processAudioChunk(preSpeechBuffer.removeFirst())
                    }
                } else if (isSpeech(chunk)) {
                    speechDetected = true
                    // 把语音前缓冲的块按顺序送入 ASR，保证开头不丢失
                    while (preSpeechBuffer.isNotEmpty()) {
                        b.processAudioChunk(preSpeechBuffer.removeFirst())
                    }
                }
            } else {
                b.processAudioChunk(chunk)
            }
        }

        /** 采集结束（手指抬起或放弃）后的收尾：按需等待模型、回放、产出最终结果。 */
        private fun finalizeAfterCapture(audioArchive: AudioArchive?) {
            try {
                if (discarded) {
                    // release()/cancel：丢弃缓存语音，引擎若已启动则 reset，不产出结果
                    synchronized(preloadLock) { loadingCancelled = true }
                    val b = engine
                    if (b != null && engineStarted && isCurrentSession()) {
                        try { b.cancel() } catch (_: Exception) { }
                    }
                    mainHandler.post {
                        stateCallback?.invoke(RecognitionState.IDLE)
                    }
                    scheduleAudioFallback()
                    return
                }

                // 等待模型期间用户已开启新会话：后端生命周期归新会话，本线程直接退出
                if (!isCurrentSession()) {
                    FileLogger.i(TAG, "Stale session finalize skipped")
                    scheduleAudioFallback()
                    return
                }

                val b = awaitBackend(FINISH_WAIT_BACKEND_MS)
                if (b == null || !isCurrentSession()) {
                    // 加载失败（ensureBackendLoading 已发错误回调）、被取消、超时或会话已切换
                    FileLogger.w(TAG, "Backend unavailable at finish, buffered audio dropped")
                    mainHandler.post {
                        stateCallback?.invoke(RecognitionState.IDLE)
                    }
                    scheduleAudioFallback()
                    return
                }

                if (!startEngine(b)) return
                // 抬起瞬间可能还有最后几块停在缓存里，先排空
                drainPending()
                // 补尾部静音，保证整句最后一个字在 finalize 时被解出（实时与回放路径都需要）
                feedTrailingSilence(b)

                // stop() 产出最终结果（离线后端在内部回调 result），与旧实现一致
                try {
                    b.stop()
                } catch (e: Exception) {
                    FileLogger.e(TAG, "backend.stop failed", e)
                }
                Log.d(TAG, "Recognition thread ended")
            } finally {
                try { audioArchive?.close() } catch (_: Exception) { }
                if (audioArchive == null) {
                    synchronized(this@SpeechRecognitionManager) {
                        pendingAudioArchive = null
                    }
                }
            }
        }

        private fun isCurrentSession(): Boolean =
            synchronized(preloadLock) { sessionId == mySession }

        /** 有界等待后台模型加载完成，返回后端；取消/超时返回 null。 */
        private fun awaitBackend(timeoutMs: Long): AsrBackend? {
            val deadline = System.currentTimeMillis() + timeoutMs
            synchronized(preloadLock) {
                while (backend == null && loadingInProgress && !loadingCancelled) {
                    val remain = deadline - System.currentTimeMillis()
                    if (remain <= 0) return null
                    try {
                        preloadLock.wait(remain)
                    } catch (_: InterruptedException) {
                        // requestFinish()/requestDiscard() 靠 interrupt() 唤醒阻塞在
                        // audioRecord.read() 中的本线程；中断状态会残留到收尾阶段，
                        // 这里清掉并继续等待模型，不能把它当作取消信号（否则缓存会被误丢）
                        Thread.interrupted()
                    }
                }
                return backend
            }
        }

        private fun isSpeech(chunk: ByteArray): Boolean {
            var peak = 0
            for (i in 0 until chunk.size / 2) {
                val low = chunk[i * 2].toInt() and 0xFF
                val high = chunk[i * 2 + 1].toInt()
                val sample = ((high shl 8) or low).toShort().toInt()
                val abs = kotlin.math.abs(sample)
                if (abs > peak) peak = abs
            }
            return peak > SPEECH_THRESHOLD
        }
    }

    private fun handleResult(text: String) {
        finalizeAudio(text)
        mainHandler.post {
            resultCallback?.invoke(text)
        }
    }

    private fun handlePartialResult(text: String) {
        mainHandler.post {
            if (text.isNotEmpty()) {
                partialResultCallback?.invoke(text)
            }
        }
    }

    private fun handleError(error: String) {
        Log.e(TAG, "Recognition error: $error")
        finalizeAudio(null)
        mainHandler.post {
            errorCallback?.invoke(error, true)
        }
    }
}
