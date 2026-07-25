package com.freefcc.app

import java.io.File
import java.io.RandomAccessFile

internal class FlightLogTailer(
    private val directories: List<File> = DEFAULT_DIRECTORIES,
    private val maxChunkBytes: Int = DEFAULT_CHUNK_BYTES
) {
    sealed interface PollResult {
        data class Chunk(
            val logName: String,
            val offset: Long,
            val fileSize: Long,
            val newFile: Boolean,
            val bytes: ByteArray
        ) : PollResult

        data class Waiting(val detail: String) : PollResult
        data class Unavailable(val detail: String) : PollResult
    }

    private var activePath: String? = null
    private var offset = 0L

    fun poll(): PollResult {
        val directory = directories.firstOrNull { it.isDirectory }
            ?: return PollResult.Unavailable(
                "DJI Fly FlightRecord directory is not visible at ${directories.joinToString { it.path }}"
            )
        val candidates = try {
            directory.listFiles()
        } catch (error: SecurityException) {
            return PollResult.Unavailable("FlightRecord access denied: ${error.message.orEmpty()}")
        } ?: return PollResult.Unavailable(
            "FlightRecord directory exists but cannot be listed; storage access is required"
        )

        val newest = candidates
            .asSequence()
            .filter { it.isFile && FLIGHT_LOG_NAME.matches(it.name) }
            .maxWithOrNull(compareBy<File>({ it.lastModified() }, { it.name }))
            ?: return PollResult.Waiting("FlightRecord directory is readable; waiting for DJI Fly log")

        val switched = activePath != newest.absolutePath
        if (switched) {
            activePath = newest.absolutePath
            offset = 0L
        }
        val fileSize = try {
            newest.length()
        } catch (error: SecurityException) {
            return PollResult.Unavailable("FlightRecord size access denied: ${error.message.orEmpty()}")
        }
        if (fileSize < offset) {
            offset = 0L
        }
        if (fileSize == offset) {
            return PollResult.Waiting(
                "Following ${newest.name}; waiting for appended bytes at offset $offset"
            )
        }

        val bytesToRead = minOf(maxChunkBytes.toLong(), fileSize - offset).toInt()
        val startOffset = offset
        val bytes = try {
            RandomAccessFile(newest, "r").use { input ->
                input.seek(startOffset)
                ByteArray(bytesToRead).also { input.readFully(it) }
            }
        } catch (error: SecurityException) {
            return PollResult.Unavailable("FlightRecord read denied: ${error.message.orEmpty()}")
        } catch (error: Exception) {
            return PollResult.Unavailable("FlightRecord read failed: ${error.message.orEmpty()}")
        }
        offset += bytes.size
        return PollResult.Chunk(
            logName = newest.name,
            offset = startOffset,
            fileSize = fileSize,
            newFile = switched || startOffset == 0L,
            bytes = bytes
        )
    }

    companion object {
        private const val DEFAULT_CHUNK_BYTES = 32 * 1024
        private val FLIGHT_LOG_NAME = Regex("""^(DJI)?FlightRecord_.*\.txt$""")
        val DEFAULT_DIRECTORIES = listOf(
            File("/storage/emulated/0/Android/data/dji.go.v5/files/FlightRecord"),
            File("/sdcard/Android/data/dji.go.v5/files/FlightRecord")
        )
    }
}
