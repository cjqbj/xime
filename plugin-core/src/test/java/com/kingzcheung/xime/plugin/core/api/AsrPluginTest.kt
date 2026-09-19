package com.kingzcheung.xime.plugin.core.api

import android.content.Context
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.config.PluginFieldType
import com.kingzcheung.xime.plugin.core.model.PluginContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrPluginCapabilitiesTest {

    @Test
    fun `capabilities have correct defaults`() {
        val caps = AsrPluginCapabilities()

        assertEquals(AsrInputMode.STREAMING, caps.inputMode)
        assertTrue(caps.supportsPartialResults)
        assertEquals(10 * 60 * 1000, caps.maxRecordDurationMillis)
        assertTrue(caps.requiresNetwork)
    }

    @Test
    fun `capabilities can declare BATCH mode`() {
        val caps = AsrPluginCapabilities(inputMode = AsrInputMode.BATCH, supportsPartialResults = false)

        assertEquals(AsrInputMode.BATCH, caps.inputMode)
        assertFalse(caps.supportsPartialResults)
    }

    @Test
    fun `audio format defaults to 16k mono pcm16le`() {
        val format = AsrAudioFormat()

        assertEquals(16000, format.sampleRate)
        assertEquals(1, format.channels)
        assertEquals("pcm16le", format.encoding)
    }
}

class AsrPluginDefaultImplTest {

    private open class FakeAsrPlugin : AsrPlugin {
        override val providerId: String = "fake"
        override fun getDisplayName(): String = "Fake"
        override fun getCapabilities(): AsrPluginCapabilities = AsrPluginCapabilities()
        override fun isConfigured(): Boolean = true
        override fun createBackend(context: Context): AsrPluginBackend = FakeBackend()

        override fun onLoad(context: PluginContext) {}
        override fun onUnload() {}
    }

    private class FakeBackend : AsrPluginBackend {
        override val isRunning: Boolean = false
        override fun setListener(listener: AsrPluginListener) {}
        override fun initialize(): Boolean = true
        override fun start(): Boolean = true
        override fun processAudioChunk(pcm: ByteArray) {}
        override fun stop() {}
        override fun cancel() {}
        override fun release() {}
        override fun getState(): AsrPluginState = AsrPluginState.IDLE
    }

    @Test
    fun `plugin default schema is empty`() {
        val plugin = FakeAsrPlugin()
        assertTrue(plugin.getSettingsSchema().isEmpty())
    }

    @Test
    fun `plugin default audio format is host contract`() {
        val plugin = FakeAsrPlugin()
        val format = plugin.getAudioFormat()

        assertEquals(16000, format.sampleRate)
        assertEquals(1, format.channels)
    }

    @Test
    fun `plugin extends IPluginEntryClass and IPluginConfigurable`() {
        val plugin: IPluginEntryClass = FakeAsrPlugin()
        val configurable: com.kingzcheung.xime.plugin.core.config.IPluginConfigurable = FakeAsrPlugin()
        assertTrue(plugin is com.kingzcheung.xime.plugin.core.config.IPluginConfigurable)
        assertTrue(configurable is IPluginEntryClass)
    }

    @Test
    fun `listener default callbacks are no-ops`() {
        val listener = object : AsrPluginListener {
            override fun onFinal(text: String) {}
        }
        listener.onPartial("partial")
        listener.onError("error")
        listener.onStateChanged(AsrPluginState.LISTENING)
        listener.onStopped()
        listener.onAmplitude(0.5f)
    }

    @Test
    fun `schema can be overridden with SECRET apiKey field`() {
        val plugin = object : FakeAsrPlugin() {
            override fun getSettingsSchema(): List<com.kingzcheung.xime.plugin.core.config.PluginSettingField> =
                listOf(
                    com.kingzcheung.xime.plugin.core.config.PluginSettingField(
                        key = "apiKey",
                        label = "API Key",
                        type = PluginFieldType.SECRET
                    )
                )
        }

        val field = plugin.getSettingsSchema().first()
        assertEquals("apiKey", field.key)
        assertEquals(PluginFieldType.SECRET, field.type)
        assertTrue(plugin.getSettingsSchema().isNotEmpty())
    }

    @Test
    fun `plugin state values are distinct`() {
        val states = AsrPluginState.entries.map { it.name }.toSet()
        assertEquals(4, states.size)
    }
}
