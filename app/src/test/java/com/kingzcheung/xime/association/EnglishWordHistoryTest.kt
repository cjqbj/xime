package com.kingzcheung.xime.association

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class EnglishWordHistoryTest {

    @Test
    fun `history suggestions match prefixes case insensitively and rank repeated words first`() {
        val history = EnglishWordHistory()

        assertTrue(history.recordText("Google and GoPro"))
        history.recordText("Google")

        assertEquals(listOf("Google", "GoPro"), history.suggest("gO", 5))
        assertEquals(emptyList<String>(), history.suggest("", 5))
    }

    @Test
    fun `history persists committed words and ignores non-English text`() {
        val directory = Files.createTempDirectory("xime-english-history").toFile()
        try {
            val file = directory.resolve("history.tsv")
            val history = EnglishWordHistory()
            history.load(file)

            assertFalse(history.recordText("中文，123"))
            history.recordText("I use GitHub")
            history.save()

            val restored = EnglishWordHistory()
            restored.load(file)

            assertEquals(listOf("GitHub"), restored.suggest("GIT", 5))
        } finally {
            directory.deleteRecursively()
        }
    }
}
