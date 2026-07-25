package com.freefcc.app

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightLogTailerTest {

    @Test
    fun `tailer reads full new file then only appended bytes`() {
        val root = Files.createTempDirectory("flight-log-tailer").toFile()
        try {
            val log = File(root, "FlightRecord_2026-07-26_[00-33-36].txt")
            log.writeBytes(byteArrayOf(1, 2, 3))
            val tailer = FlightLogTailer(listOf(root), maxChunkBytes = 1024)

            val first = tailer.poll() as FlightLogTailer.PollResult.Chunk
            assertEquals(0L, first.offset)
            assertTrue(first.newFile)
            assertArrayEquals(byteArrayOf(1, 2, 3), first.bytes)

            log.appendBytes(byteArrayOf(4, 5))
            val second = tailer.poll() as FlightLogTailer.PollResult.Chunk
            assertEquals(3L, second.offset)
            assertTrue(!second.newFile)
            assertArrayEquals(byteArrayOf(4, 5), second.bytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `tailer ignores sidecar cache records`() {
        val root = Files.createTempDirectory("flight-log-tailer").toFile()
        try {
            File(root, "FlightRecord_2026-07-26_[00-33-36].txt_25925").writeBytes(byteArrayOf(1))

            val result = FlightLogTailer(listOf(root)).poll()

            assertTrue(result is FlightLogTailer.PollResult.Waiting)
        } finally {
            root.deleteRecursively()
        }
    }
}
