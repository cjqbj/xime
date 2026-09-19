package com.kingzcheung.xime.plugin.core.api

import android.content.Context
import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable

enum class AsrPluginState {
    IDLE,
    LISTENING,
    PROCESSING,
    ERROR
}

enum class AsrInputMode {
    STREAMING,
    BATCH
}

data class AsrAudioFormat(
    val sampleRate: Int = 16000,
    val channels: Int = 1,
    val encoding: String = "pcm16le"
)

data class AsrPluginCapabilities(
    val inputMode: AsrInputMode = AsrInputMode.STREAMING,
    val supportsPartialResults: Boolean = true,
    val maxRecordDurationMillis: Int = 10 * 60 * 1000,
    val requiresNetwork: Boolean = true
)

interface AsrPluginListener {
    fun onFinal(text: String)
    fun onPartial(text: String) {}
    fun onError(message: String) {}
    fun onStateChanged(state: AsrPluginState) {}
    fun onStopped() {}
    fun onAmplitude(amplitude: Float) {}
}

interface AsrPluginBackend {
    val isRunning: Boolean

    fun setListener(listener: AsrPluginListener)

    fun initialize(): Boolean

    fun start(): Boolean

    fun processAudioChunk(pcm: ByteArray)

    fun stop()

    fun cancel()

    fun release()

    fun getState(): AsrPluginState
}

interface AsrPlugin : IPluginEntryClass, IPluginConfigurable {
    val providerId: String

    fun getDisplayName(): String

    fun getCapabilities(): AsrPluginCapabilities

    fun getAudioFormat(): AsrAudioFormat = AsrAudioFormat()

    fun isConfigured(): Boolean

    fun createBackend(context: Context): AsrPluginBackend
}
