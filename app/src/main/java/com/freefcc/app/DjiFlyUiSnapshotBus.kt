package com.freefcc.app

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class DjiFlyUiSnapshot(
    val sequence: Long,
    val capturedAtUtc: String,
    val elapsedRealtimeNs: Long,
    val eventType: String,
    val labels: List<String>,
    val visitedNodeCount: Int,
    val truncated: Boolean
)

internal data class SanitizedDjiFlyLabels(
    val labels: List<String>,
    val truncated: Boolean
)

internal object DjiFlyUiSnapshotPolicy {
    const val MAX_NODES = 300
    const val MAX_LABELS = 80
    const val MAX_LABEL_CHARS = 240
    const val MAX_TOTAL_CHARS = 1_500

    private val whitespace = Regex("\\s+")

    fun sanitize(
        values: Iterable<CharSequence?>,
        traversalTruncated: Boolean = false
    ): SanitizedDjiFlyLabels {
        val labels = LinkedHashSet<String>()
        var totalChars = 0
        var truncated = traversalTruncated

        for (rawValue in values) {
            var value = rawValue?.toString()?.replace(whitespace, " ")?.trim().orEmpty()
            if (value.isEmpty() || value in labels) continue
            if (labels.size >= MAX_LABELS || totalChars >= MAX_TOTAL_CHARS) {
                truncated = true
                break
            }
            if (value.length > MAX_LABEL_CHARS) {
                value = value.take(MAX_LABEL_CHARS)
                truncated = true
            }
            val remaining = MAX_TOTAL_CHARS - totalChars
            if (value.length > remaining) {
                value = value.take(remaining)
                truncated = true
            }
            if (value.isNotEmpty() && labels.add(value)) {
                totalChars += value.length
            }
        }
        return SanitizedDjiFlyLabels(labels.toList(), truncated)
    }
}

internal object DjiFlyUiSnapshotBus {
    private val captureRequested = AtomicBoolean(false)
    private val nextSequence = AtomicLong(0)
    private val latest = AtomicReference<DjiFlyUiSnapshot?>(null)

    fun beginCapture(): Long {
        latest.set(null)
        captureRequested.set(true)
        return nextSequence.get()
    }

    fun endCapture() {
        captureRequested.set(false)
        latest.set(null)
    }

    fun isCaptureRequested(): Boolean = captureRequested.get()

    fun publish(
        wallTimeMs: Long,
        elapsedRealtimeNs: Long,
        eventType: String,
        values: Iterable<CharSequence?>,
        visitedNodeCount: Int,
        traversalTruncated: Boolean
    ): DjiFlyUiSnapshot? {
        if (!captureRequested.get()) return null
        val sanitized = DjiFlyUiSnapshotPolicy.sanitize(values, traversalTruncated)
        return DjiFlyUiSnapshot(
            sequence = nextSequence.incrementAndGet(),
            capturedAtUtc = Instant.ofEpochMilli(wallTimeMs).toString(),
            elapsedRealtimeNs = elapsedRealtimeNs,
            eventType = eventType,
            labels = sanitized.labels,
            visitedNodeCount = visitedNodeCount.coerceAtLeast(0),
            truncated = sanitized.truncated
        ).also(latest::set)
    }

    fun latestAfter(sequence: Long): DjiFlyUiSnapshot? =
        latest.get()?.takeIf { it.sequence > sequence }
}
