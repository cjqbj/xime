package com.kingzcheung.xime.plugin.core.runtime.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestParseTest {

    @Test
    fun `正常 manifest 解析成功且字段正确`() {
        val content = """
            id: kaomoji
            name: 颜文字
            version: 2.1.0
            description: 内置颜文字
            type: emoji
            entry: main.lua
            minHostVersion: 2.6.0
            maxHostVersion: 3.0.0
            network:
              hosts:
                - dashscope.aliyuncs.com
              allowCustomHosts: true
        """.trimIndent()

        val result = InstallerManager.parseManifestContent(content)
        val config = (result as PluginParseResult.Success).config
        assertEquals("kaomoji", config.id)
        assertEquals("颜文字", config.name)
        assertEquals("2.1.0", config.version)
        assertEquals("emoji", config.type)
        assertEquals("main.lua", config.entryScript)
        assertEquals("2.6.0", config.minHostVersion)
        assertEquals("3.0.0", config.maxHostVersion)
        assertEquals(listOf("dashscope.aliyuncs.com"), config.declaredHosts)
        assertTrue("allowCustomHosts 应解析为 true", config.allowCustomHosts)
    }

    @Test
    fun `可选字段缺省时使用默认值`() {
        val content = """
            id: mini
        """.trimIndent()

        val config = (InstallerManager.parseManifestContent(content) as PluginParseResult.Success).config
        assertEquals("mini", config.id)
        assertEquals("mini", config.name)
        assertEquals("0.0.0", config.version)
        assertEquals("unknown", config.type)
        assertEquals("main.lua", config.entryScript)
        assertEquals("", config.description)
        assertEquals(emptyList<String>(), config.declaredHosts)
    }

    @Test
    fun `缺少必填 id 字段时返回可读错误`() {
        val content = """
            name: 没有 id
        """.trimIndent()

        val result = InstallerManager.parseManifestContent(content)
        val reason = (result as PluginParseResult.Failure).reason
        assertTrue("错误应说明解析失败, 实际: $reason", reason.startsWith("manifest.yaml 解析失败"))
        assertTrue("错误应提到 id, 实际: $reason", reason.contains("id"))
    }

    @Test
    fun `字段类型错误时返回可读错误`() {
        val content = """
            id: demo
            type: [a, b]
        """.trimIndent()

        val result = InstallerManager.parseManifestContent(content)
        val reason = (result as PluginParseResult.Failure).reason
        assertTrue("错误应说明解析失败, 实际: $reason", reason.startsWith("manifest.yaml 解析失败"))
    }

    @Test
    fun `YAML 语法错误时返回可读错误`() {
        val content = "id: [未闭合"

        val result = InstallerManager.parseManifestContent(content)
        val reason = (result as PluginParseResult.Failure).reason
        assertTrue("错误应说明解析失败, 实际: $reason", reason.startsWith("manifest.yaml 解析失败"))
    }

    @Test
    fun `多余字段在非严格模式下被忽略`() {
        val content = """
            id: demo
            extraField: 123
            custom:
              - a
        """.trimIndent()

        val config = (InstallerManager.parseManifestContent(content) as PluginParseResult.Success).config
        assertEquals("demo", config.id)
    }

    @Test
    fun `插件 id 支持反域名命名空间`() {
        assertTrue("反域名 id 应合法", InstallerManager.isValidPluginId("com.kingzcheung.xime.plugin.funasr_asr"))
        assertTrue("下划线/连字符 id 应合法", InstallerManager.isValidPluginId("webdav-clipboard_sync"))
    }

    @Test
    fun `非法插件 id 被拒绝`() {
        assertTrue("空 id 非法", !InstallerManager.isValidPluginId(""))
        assertTrue("含点号连续出现非法", !InstallerManager.isValidPluginId("a..b"))
        assertTrue("点号开头非法", !InstallerManager.isValidPluginId(".abc"))
        assertTrue("点号结尾非法", !InstallerManager.isValidPluginId("abc."))
        assertTrue("含斜杠非法", !InstallerManager.isValidPluginId("a/b"))
        assertTrue("超过 64 非法", !InstallerManager.isValidPluginId("a".repeat(65)))
        assertTrue("含中文非法", !InstallerManager.isValidPluginId("插件a"))
    }
}