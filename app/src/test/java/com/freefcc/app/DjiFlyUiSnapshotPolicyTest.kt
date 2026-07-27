package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjiFlyUiSnapshotPolicyTest {

    @Test
    fun `sanitize collapses whitespace and preserves first-seen order`() {
        val result = DjiFlyUiSnapshotPolicy.sanitize(
            listOf("  H  12.3 m ", "Pitch -90", "H 12.3 m", "", null)
        )

        assertEquals(listOf("H 12.3 m", "Pitch -90"), result.labels)
        assertFalse(result.truncated)
    }

    @Test
    fun `sanitize enforces label and total bounds`() {
        val values = (0..100).map { index ->
            "$index ${"x".repeat(DjiFlyUiSnapshotPolicy.MAX_LABEL_CHARS)}"
        }

        val result = DjiFlyUiSnapshotPolicy.sanitize(values)

        assertTrue(result.truncated)
        assertTrue(result.labels.size <= DjiFlyUiSnapshotPolicy.MAX_LABELS)
        assertTrue(result.labels.all { it.length <= DjiFlyUiSnapshotPolicy.MAX_LABEL_CHARS })
        assertTrue(result.labels.sumOf(String::length) <= DjiFlyUiSnapshotPolicy.MAX_TOTAL_CHARS)
    }

    @Test
    fun `traversal truncation is retained without label truncation`() {
        val result = DjiFlyUiSnapshotPolicy.sanitize(
            values = listOf("GPS 18"),
            traversalTruncated = true
        )

        assertEquals(listOf("GPS 18"), result.labels)
        assertTrue(result.truncated)
    }

    @Test
    fun `snapshot bus only publishes during explicit lab capture`() {
        DjiFlyUiSnapshotBus.endCapture()
        assertEquals(
            null,
            DjiFlyUiSnapshotBus.publish(
                wallTimeMs = 0,
                elapsedRealtimeNs = 1,
                eventType = "test",
                values = listOf("hidden outside lab"),
                visitedNodeCount = 1,
                traversalTruncated = false
            )
        )

        val startingSequence = DjiFlyUiSnapshotBus.beginCapture()
        try {
            val snapshot = DjiFlyUiSnapshotBus.publish(
                wallTimeMs = 0,
                elapsedRealtimeNs = 2,
                eventType = "test",
                values = listOf("visible in lab"),
                visitedNodeCount = 1,
                traversalTruncated = false
            )

            assertEquals(listOf("visible in lab"), snapshot?.labels)
            assertEquals(snapshot, DjiFlyUiSnapshotBus.latestAfter(startingSequence))
        } finally {
            DjiFlyUiSnapshotBus.endCapture()
        }
    }
}
