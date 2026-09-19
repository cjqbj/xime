package com.kingzcheung.xime.settings

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StyleColorSchemeSerializerTest {

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    @Test
    fun `标量形式 color_scheme 可以解析`() {
        val config = yaml.decodeFromString(
            StyleConfig.serializer(),
            "color_scheme: mu_shan_zi\n"
        )
        assertNotNull(config.colorScheme)
        assertEquals("mu_shan_zi", config.colorScheme?.light)
        assertNull(config.colorScheme?.dark)
    }

    @Test
    fun `对象形式 color_scheme 仍然可以解析`() {
        val config = yaml.decodeFromString(
            StyleConfig.serializer(),
            "color_scheme:\n  light: zine_light\n  dark: zine_dark\n"
        )
        assertNotNull(config.colorScheme)
        assertEquals("zine_light", config.colorScheme?.light)
        assertEquals("zine_dark", config.colorScheme?.dark)
    }

    @Test
    fun `对象形式缺 dark 时 light 保留`() {
        val config = yaml.decodeFromString(
            StyleConfig.serializer(),
            "color_scheme:\n  light: lavender_purple\n"
        )
        assertEquals("lavender_purple", config.colorScheme?.light)
        assertNull(config.colorScheme?.dark)
    }
}
