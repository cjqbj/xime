package com.kingzcheung.xime.plugin.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryPluginConfigStoreTest {

    class InMemoryPluginConfigStore : PluginConfigStore {
        private val map = mutableMapOf<String, String>()

        override fun get(key: String): String? = map[key]

        override fun set(key: String, value: String) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }

        override fun keys(): Set<String> = map.keys.toSet()
    }

    @Test
    fun `store get returns null for missing key`() {
        val store = InMemoryPluginConfigStore()
        assertNull(store.get("apiKey"))
    }

    @Test
    fun `store set then get returns value`() {
        val store = InMemoryPluginConfigStore()
        store.set("apiKey", "sk-123")
        assertEquals("sk-123", store.get("apiKey"))
    }

    @Test
    fun `store set overwrites existing value`() {
        val store = InMemoryPluginConfigStore()
        store.set("apiKey", "old")
        store.set("apiKey", "new")
        assertEquals("new", store.get("apiKey"))
    }

    @Test
    fun `store remove deletes key`() {
        val store = InMemoryPluginConfigStore()
        store.set("apiKey", "sk-123")
        store.remove("apiKey")
        assertNull(store.get("apiKey"))
        assertTrue(store.keys().isEmpty())
    }

    @Test
    fun `store keys returns only stored keys`() {
        val store = InMemoryPluginConfigStore()
        store.set("a", "1")
        store.set("b", "2")
        assertEquals(setOf("a", "b"), store.keys())
    }
}

class NoopPluginConfigStoreTest {

    @Test
    fun `noop store is read-only and empty`() {
        val store = NoopPluginConfigStore
        assertNull(store.get("any"))
        store.set("any", "value")
        assertNull(store.get("any"))
        assertTrue(store.keys().isEmpty())
    }
}

class PluginSettingFieldTest {

    @Test
    fun `PluginSettingField has correct defaults`() {
        val field = PluginSettingField(key = "apiKey", label = "API Key", type = PluginFieldType.SECRET)

        assertEquals("apiKey", field.key)
        assertEquals("API Key", field.label)
        assertEquals(PluginFieldType.SECRET, field.type)
        assertNull(field.placeholder)
        assertNull(field.defaultValue)
        assertTrue(field.options.isEmpty())
        assertNull(field.helpText)
        assertNull(field.section)
        assertTrue("SECRET 字段默认必填", field.required)
    }

    @Test
    fun `PluginSettingField can be optional`() {
        val field = PluginSettingField(
            key = "appKey",
            label = "App Key",
            type = PluginFieldType.SECRET,
            required = false
        )

        assertFalse(field.required)
    }

    @Test
    fun `PluginSettingField can specify options for SELECT`() {
        val field = PluginSettingField(
            key = "region",
            label = "区域",
            type = PluginFieldType.SELECT,
            options = listOf("cn", "intl")
        )

        assertEquals(listOf("cn", "intl"), field.options)
    }

    @Test
    fun `PluginSettingField can have defaultValue`() {
        val field = PluginSettingField(
            key = "vad",
            label = "静音判停",
            type = PluginFieldType.SWITCH,
            defaultValue = "true"
        )

        assertEquals("true", field.defaultValue)
    }

    @Test
    fun `PluginSettingField can be MULTI_SELECT with options`() {
        val field = PluginSettingField(
            key = "languageHints",
            label = "语言提示",
            type = PluginFieldType.MULTI_SELECT,
            options = listOf("zh", "en", "ja")
        )

        assertEquals(PluginFieldType.MULTI_SELECT, field.type)
        assertEquals(listOf("zh", "en", "ja"), field.options)
    }

    @Test
    fun `PluginSettingField can have section`() {
        val field = PluginSettingField(
            key = "vadSensitivity",
            label = "静音灵敏度",
            type = PluginFieldType.NUMBER,
            section = "高级"
        )

        assertEquals("高级", field.section)
        assertNull(PluginSettingField("a", "A", PluginFieldType.TEXT).section)
    }

    @Test
    fun `PluginConfigurable default schema is empty`() {
        val configurable = object : IPluginConfigurable {}
        assertTrue(configurable.getSettingsSchema().isEmpty())
        assertNull(configurable.getOptions("model"))
    }

    @Test
    fun `PluginConfigurable custom schema is returned`() {
        val configurable = object : IPluginConfigurable {
            override fun getSettingsSchema(): List<PluginSettingField> =
                listOf(PluginSettingField("apiKey", "API Key", PluginFieldType.SECRET))
        }

        assertFalse(configurable.getSettingsSchema().isEmpty())
        assertEquals("apiKey", configurable.getSettingsSchema().first().key)
    }

    @Test
    fun `PluginConfigurable getOptions returns dynamic list`() {
        val configurable = object : IPluginConfigurable {
            override fun getOptions(key: String): List<String>? =
                if (key == "model") listOf("fun-asr-realtime", "fun-asr") else null
        }

        assertEquals(listOf("fun-asr-realtime", "fun-asr"), configurable.getOptions("model"))
        assertNull(configurable.getOptions("other"))
    }
}
