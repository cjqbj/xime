package com.kingzcheung.xime.speech

import android.Manifest
import android.content.Context
import android.media.AudioFormat
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

    // 预启动的 AudioRecord：手指按下 150ms 后启动，语音激活时直接交给录音线程
    private var preStartedRecord: AudioRecord? = null
    private val preStartTimeoutRunnable = Runnable { cancelPreStart() }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecognition() {
        synchronized(preloadLock) {
            while (isPreloading) {
                try {
                    preloadLock.wait()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
        
        if (recordingThread != null) {
            FileLogger.w(TAG, "Recognition already running, ignoring start request")
            return
        }

        FileLogger.i(TAG, "Starting speech recognition")
        stateCallback?.invoke(RecognitionState.PROCESSING)

        if (backend == null) {
            // 按需加载：后台线程加载 ASR 模型，完成后在主线程启动录音，避免阻塞键盘 UI
            FileLogger.i(TAG, "ASR backend not loaded, loading on demand")
            synchronized(preloadLock) {
                if (loadingInProgress) {
                    // 已有加载进行中（如设置开启触发的预加载），等待其完成
                    loadingCancelled = false
                    return
                }
                loadingInProgress = true
                loadingCancelled = false
            }
            Thread {
                try {
                    val ok = preload()
                    if (!ok || synchronized(preloadLock) { backend } == null) {
                        mainHandler.post {
                            errorCallback?.invoke("无法初始化语音引擎，请检查本地模型或在线语音插件配置", true)
                            stateCallback?.invoke(RecognitionState.ERROR)
                        }
                        return@Thread
                    }
                    if (loadingCancelled) {
                        mainHandler.post {
                            stateCallback?.invoke(RecognitionState.IDLE)
                        }
                        return@Thread
                    }
                    mainHandler.post {
                        if (recordingThread == null && !loadingCancelled) {
                            startRecording()
                        }
                    }
                } finally {
                    synchronized(preloadLock) {
                        loadingInProgress = false
                        preloadLock.notifyAll()
                    }
                }
            }.start()
            return
        }

        startRecording()
    }

    private fun startRecording() {
        val currentBackend = synchronized(preloadLock) { backend } ?: return
        synchronized(preloadLock) { sessionId++ }
        audioFallbackRunnable?.let(mainHandler::removeCallbacks)
        audioFallbackRunnable = null

        // 预启动的 AudioRecord 已运行 ~250ms，直接交给录音线程
        var preStarted: AudioRecord? = null
        synchronized(this) {
            preStarted = preStartedRecord
            preStartedRecord = null
        }
        mainHandler.removeCallbacks(preStartTimeoutRunnable)

        recordingThread = RecordingThread(currentBackend, preStarted)
        recordingThread!!.start()
    }

    fun stopRecognition() {
        Log.d(TAG, "Stopping recognition")
        val thread = recordingThread
        if (thread == null) {
            // 模型仍在后台加载中：标记取消，加载完成后不再启动录音
            if (loadingInProgress) {
                loadingCancelled = true
                mainHandler.post {
                    stateCallback?.invoke(RecognitionState.IDLE)
                }
            }
            return
        }
        recordingThread = null
        val session = synchronized(preloadLock) { sessionId }
        thread.interrupt()
        Thread {
            try {
                thread.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val releaseBackend = true
            // release() 含跨进程 IPC，放到后台线程执行，避免阻塞主线程；
            // 仅当会话序号未变化时释放并置空，避免误释放新会话正在使用的后端
            if (releaseBackend) {
                val b = synchronized(preloadLock) {
                    if (sessionId == session) {
                        val tmp = backend
                        backend = null
                        tmp
                    } else null
                }
                if (b != null) {
                    b.release()
                }
            }
            mainHandler.post {
                stateCallback?.invoke(RecognitionState.IDLE)
            }
            scheduleAudioFallback()
        }.start()
    }

    fun cancelRecognition() {
        Log.d(TAG, "Canceling recognition")
        val thread = recordingThread
        if (thread == null) {
            // 模型仍在后台加载中：标记取消，加载完成后不再启动录音
            if (loadingInProgress) {
                loadingCancelled = true
                mainHandler.post {
                    stateCallback?.invoke(RecognitionState.IDLE)
                }
            }
            return
        }
        recordingThread = null
        val session = synchronized(preloadLock) { sessionId }
        thread.interrupt()
        Thread {
            try {
                thread.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val releaseBackend = true
            // release() 含跨进程 IPC，放到后台线程执行，避免阻塞主线程；
            // 仅当会话序号未变化时释放并置空，避免误释放新会话正在使用的后端
            if (releaseBackend) {
                val b = synchronized(preloadLock) {
                    if (sessionId == session) {
                        val tmp = backend
                        backend = null
                        tmp
                    } else null
                }
                if (b != null) {
                    b.release()
                }
            }
            mainHandler.post {
                stateCallback?.invoke(RecognitionState.IDLE)
            }
            scheduleAudioFallback()
        }.start()
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

    private inner class RecordingThread(
        private val currentBackend: AsrBackend,
        private val preStarted: AudioRecord? = null
    ) : Thread("AsrRecording") {

        private val spectrumAnalyzer = SpectrumAnalyzer()

        override fun run() {
            val audioRecord = preStarted ?: (createAudioRecord() ?: run {
                mainHandler.post {
                    errorCallback?.invoke("无法启动录音", false)
                    stateCallback?.invoke(RecognitionState.ERROR)
                }
                return
            })

            if (!currentBackend.start()) {
                audioRecord.stop()
                audioRecord.release()
                mainHandler.post {
                    errorCallback?.invoke("启动引擎失败", false)
                    stateCallback?.invoke(RecognitionState.ERROR)
                }
                return
            }

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
            var speechDetected = false
            // 语音前缓冲：保存检测到语音前的若干块，检测到后一起送入 ASR，
            // 避免"你/觉"等弱开头的语音块因音量低于阈值被当作静音丢弃
            val preSpeechBuffer = ArrayDeque<ByteArray>()
            val maxPreSpeechChunks = 4  // 0.4s 语音前缓冲

            try {
                while (!interrupted()) {
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
                        if (!speechDetected) {
                            preSpeechBuffer.addLast(chunk)
                            // 缓冲满仍未检测到语音：放弃 VAD，直接开始识别，
                            // 保证整段弱音内容也能送入 ASR（开头静音已被缓冲丢弃）
                            if (preSpeechBuffer.size >= maxPreSpeechChunks) {
                                speechDetected = true
                                while (preSpeechBuffer.isNotEmpty()) {
                                    currentBackend.processAudioChunk(preSpeechBuffer.removeFirst())
                                }
                            } else if (isSpeech(chunk)) {
                                speechDetected = true
                                // 把语音前缓冲的块按顺序送入 ASR，保证开头不丢失
                                while (preSpeechBuffer.isNotEmpty()) {
                                    currentBackend.processAudioChunk(preSpeechBuffer.removeFirst())
                                }
                            }
                        } else {
                            currentBackend.processAudioChunk(chunk)
                        }
                    } else if (nread < 0) {
                        break
                    }
                }
            } catch (_: Exception) {
            } finally {
                audioRecord.stop()
                audioRecord.release()
            }

            currentBackend.stop()
            audioArchive?.close()
            Log.d(TAG, "Recognition thread ended")
            if (audioArchive == null) {
                synchronized(this@SpeechRecognitionManager) {
                    pendingAudioArchive = null
                }
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
