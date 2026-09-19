package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure [SchemaManager.replaceSchemaListBlock] used by F1
 * (writing the enabled schema list directly into default.yaml so librime
 * actually compiles the enabled schemes).
 */
class SchemaListBlockTest {

    private val defaultYaml = """
        # Rime default settings
        # encoding: utf-8

        config_version: "0.36"

        schema_list:
          - schema: wubi86
          - schema: wubi86_pinyin
          - schema: pinyin_simp

        switcher:
          caption: "〔方案选单〕"
    """.trimIndent()

    /** Pull the schema ids out of the schema_list block of a yaml string. */
    private fun extractSchemaList(yaml: String): List<String> {
        val out = mutableListOf<String>()
        var inBlock = false
        for (line in yaml.lines()) {
            if (line.trim() == "schema_list:") { inBlock = true; continue }
            if (inBlock) {
                val t = line.trim()
                if (t.startsWith("- schema:")) {
                    out.add(t.removePrefix("- schema:").trim())
                } else if (t.isNotEmpty()) {
                    break
                }
            }
        }
        return out
    }

    @Test
    fun `replaces existing block with a single enabled schema`() {
        val result = SchemaManager.replaceSchemaListBlock(defaultYaml, listOf("quick5"))
        assertEquals(listOf("quick5"), extractSchemaList(result))
        assertFalse("old schemas must be gone", result.contains("wubi86"))
    }

    @Test
    fun `replaces existing block with multiple enabled schemas in order`() {
        val result = SchemaManager.replaceSchemaListBlock(
            defaultYaml, listOf("cangjie5", "quick5", "pinyin_simp")
        )
        assertEquals(listOf("cangjie5", "quick5", "pinyin_simp"), extractSchemaList(result))
    }

    @Test
    fun `preserves content before and after the block`() {
        val result = SchemaManager.replaceSchemaListBlock(defaultYaml, listOf("quick5"))
        assertTrue("config_version kept", result.contains("""config_version: "0.36""""))
        assertTrue("switcher block kept", result.contains("switcher:"))
        assertTrue("nested switcher content kept", result.contains("〔方案选单〕"))
        assertTrue("leading comment kept", result.contains("# Rime default settings"))
    }

    @Test
    fun `keeps the two-space indentation style of list items`() {
        val result = SchemaManager.replaceSchemaListBlock(defaultYaml, listOf("quick5"))
        assertTrue(result.contains("\n  - schema: quick5"))
    }

    @Test
    fun `handles a schema_list block at end of file`() {
        val atEof = "config_version: \"0.36\"\n\nschema_list:\n  - schema: wubi86\n"
        val result = SchemaManager.replaceSchemaListBlock(atEof, listOf("quick5"))
        assertEquals(listOf("quick5"), extractSchemaList(result))
        assertTrue(result.contains("config_version"))
    }

    @Test
    fun `empty enabled list leaves text unchanged`() {
        val result = SchemaManager.replaceSchemaListBlock(defaultYaml, emptyList())
        assertEquals(defaultYaml, result)
    }

    @Test
    fun `preserves CRLF line endings`() {
        val crlf = "config_version: \"0.36\"\r\n\r\nschema_list:\r\n  - schema: wubi86\r\n\r\nswitcher:\r\n"
        val result = SchemaManager.replaceSchemaListBlock(crlf, listOf("quick5"))
        assertTrue("CRLF must be preserved", result.contains("\r\n"))
        // 去掉所有 CRLF 后不应残留任何裸 LF（即没有被规范化成 LF 的行）
        assertFalse("no lone LF introduced", result.replace("\r\n", "").contains("\n"))
        assertEquals(listOf("quick5"), extractSchemaList(result))
    }

    @Test
    fun `inserts a block when none exists`() {
        val noBlock = "config_version: \"0.36\"\n\nswitcher:\n  caption: x\n"
        val result = SchemaManager.replaceSchemaListBlock(noBlock, listOf("quick5"))
        assertEquals(listOf("quick5"), extractSchemaList(result))
        assertTrue(result.contains("switcher:"))
    }
}
