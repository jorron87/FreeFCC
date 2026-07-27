package com.freefcc.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjiFlyHomePointMatcherTest {
    @Test
    fun `localized text is normalized before exact matching`() {
        val phrases = setOf("home point has been updated")

        assertTrue(
            DjiFlyHomePointMatcher.matches(
                "  HOME   POINT has been updated! ",
                phrases
            )
        )
    }

    @Test
    fun `partial text does not trigger auto fcc`() {
        val phrases = setOf("home point has been updated")

        assertFalse(DjiFlyHomePointMatcher.matches("home point", phrases))
    }
}
