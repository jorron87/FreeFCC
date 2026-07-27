package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `research manifest parses apk metadata`() {
        val info = UpdateChecker.parseReleaseJson(
            """
            {
              "version": "1.5.3-research.1",
              "title": "Telemetry relay bugfix",
              "changelog": "Fix relay reconnect.",
              "apk_url": "https://example.test/FreeFCC-research.apk",
              "apk_size": 1234,
              "sha256": "sha256:abcdef",
              "published_at": "2026-07-22T20:00:00Z"
            }
            """.trimIndent(),
            "https://example.test/freefcc.json"
        )

        requireNotNull(info)
        assertEquals("1.5.3-research.1", info.version)
        assertEquals("Telemetry relay bugfix", info.title)
        assertEquals("https://example.test/FreeFCC-research.apk", info.downloadUrl)
        assertEquals(1234L, info.apkSize)
        assertEquals("abcdef", info.sha256)
        assertEquals("https://example.test/freefcc.json", info.source)
        assertTrue(info.isNewerThan("1.5.2"))
    }

    @Test
    fun `github release parser keeps first apk asset`() {
        val info = UpdateChecker.parseReleaseJson(
            """
            {
              "tag_name": "v1.5.3",
              "name": "v1.5.3",
              "body": "Bugfix",
              "published_at": "2026-07-22T20:00:00Z",
              "assets": [
                {"name": "notes.txt", "browser_download_url": "https://example.test/notes.txt"},
                {"name": "FreeFCC.apk", "browser_download_url": "https://example.test/FreeFCC.apk", "size": 4321, "digest": "sha256:123456"}
              ]
            }
            """.trimIndent(),
            UpdateChecker.DEFAULT_API_URL
        )

        requireNotNull(info)
        assertEquals("1.5.3", info.version)
        assertEquals("https://example.test/FreeFCC.apk", info.downloadUrl)
        assertEquals("123456", info.sha256)
    }
}
