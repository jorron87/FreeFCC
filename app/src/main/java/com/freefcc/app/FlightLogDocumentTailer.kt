package com.freefcc.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.FileInputStream

internal object FlightLogAccess {
    const val PREF_TREE_URI = "telemetry_flight_log_tree_uri"
}

internal interface FlightLogPoller {
    fun poll(): FlightLogTailer.PollResult
}

internal class FlightLogDocumentTailer(
    context: Context,
    treeUri: Uri,
    private val maxChunkBytes: Int = DEFAULT_CHUNK_BYTES
) : FlightLogPoller {
    private val resolver = context.contentResolver
    private val root = DocumentFile.fromTreeUri(context, treeUri)
    private val sourceLabel = "saf:$treeUri"
    private var activeUri: String? = null
    private var offset = 0L

    override fun poll(): FlightLogTailer.PollResult {
        val directory = root
            ?: return FlightLogTailer.PollResult.Unavailable(
                "Persisted FlightRecord folder URI is invalid"
            )
        val candidates = try {
            directory.listFiles()
        } catch (error: SecurityException) {
            return FlightLogTailer.PollResult.Unavailable(
                "SAF FlightRecord access denied; select the folder again"
            )
        } catch (error: Exception) {
            return FlightLogTailer.PollResult.Unavailable(
                "SAF FlightRecord listing failed: ${error.message.orEmpty()}"
            )
        }
        val newest = candidates
            .asSequence()
            .filter { it.isFile && FLIGHT_LOG_NAME.matches(it.name.orEmpty()) }
            .maxWithOrNull(compareBy<DocumentFile>({ it.lastModified() }, { it.name.orEmpty() }))
            ?: return FlightLogTailer.PollResult.Waiting(
                "SAF FlightRecord folder is readable; waiting for DJI Fly log"
            )

        val documentUri = newest.uri.toString()
        val switched = activeUri != documentUri
        if (switched) {
            activeUri = documentUri
            offset = 0L
        }
        val fileSize = newest.length()
        if (fileSize < offset) offset = 0L
        if (fileSize == offset) {
            return FlightLogTailer.PollResult.Waiting(
                "Following ${newest.name} from SAF live folder; waiting at offset $offset"
            )
        }

        val startOffset = offset
        val bytesToRead = minOf(maxChunkBytes.toLong(), fileSize - offset).toInt()
        val bytes = try {
            resolver.openFileDescriptor(newest.uri, "r")?.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    input.channel.position(startOffset)
                    ByteArray(bytesToRead).also { buffer ->
                        var read = 0
                        while (read < buffer.size) {
                            val count = input.read(buffer, read, buffer.size - read)
                            if (count < 0) error("Unexpected EOF at ${startOffset + read}")
                            read += count
                        }
                    }
                }
            } ?: return FlightLogTailer.PollResult.Unavailable(
                "SAF FlightRecord could not be opened"
            )
        } catch (error: SecurityException) {
            return FlightLogTailer.PollResult.Unavailable(
                "SAF FlightRecord read denied; select the folder again"
            )
        } catch (error: Exception) {
            return FlightLogTailer.PollResult.Unavailable(
                "SAF FlightRecord read failed: ${error.message.orEmpty()}"
            )
        }
        offset += bytes.size
        return FlightLogTailer.PollResult.Chunk(
            logName = newest.name.orEmpty(),
            sourceDirectory = sourceLabel,
            offset = startOffset,
            fileSize = fileSize,
            newFile = switched || startOffset == 0L,
            bytes = bytes
        )
    }

    private companion object {
        const val DEFAULT_CHUNK_BYTES = 32 * 1024
        val FLIGHT_LOG_NAME = Regex("""^(DJI)?FlightRecord_.*\.txt$""")
    }
}
