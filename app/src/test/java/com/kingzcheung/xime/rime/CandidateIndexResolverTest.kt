package com.kingzcheung.xime.rime

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateIndexResolverTest {

    @Test
    fun `candidate reorder resolves by text`() {
        assertEquals(
            1,
            resolveRimeCandidateIndex(0, "target", listOf("other", "target"))
        )
    }

    @Test
    fun `missing candidate does not fall back to another page position`() {
        assertEquals(
            -1,
            resolveRimeCandidateIndex(2, "previous-page-word", listOf("current-page-word"))
        )
    }

    @Test
    fun `missing identity preserves index based selection`() {
        assertEquals(2, resolveRimeCandidateIndex(2, null, listOf("candidate")))
    }
}
