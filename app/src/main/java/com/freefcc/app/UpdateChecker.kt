package com.freefcc.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Checks for app updates by querying either the GitHub Releases API or a small
 * release manifest for fork/research builds.
 *
 * Default research release endpoint:
 *   GET https://api.github.com/repos/jorron87/FreeFCC/releases/latest
 *
 * Returns JSON with tag_name, name, body (changelog), and assets[] (download URLs).
 *
 * The download flow:
 *   1. Download the APK to app cache dir
 *   2. Verify the SHA-256 digest against the GitHub asset digest
 *   3. Return the file path — the ViewModel opens an install Intent
 */
data class UpdateInfo(
    val version: String,       // e.g. "1.4"
    val title: String,         // e.g. "v1.4 — Altitude Unlock"
    val changelog: String,    // release body (markdown)
    val downloadUrl: String,  // direct APK URL
    val apkSize: Long,        // bytes
    val publishedAt: String,  // ISO date
    val sha256: String?,      // expected hex digest from release metadata, or null if absent
    val source: String        // release channel URL or repository
) {
    fun isNewerThan(currentVersion: String): Boolean {
        val cur = parseVersion(currentVersion)
        val new = parseVersion(version)
        val maxLen = maxOf(cur.size, new.size)
        for (i in 0 until maxLen) {
            val c = cur.getOrElse(i) { 0 }
            val n = new.getOrElse(i) { 0 }
            if (n != c) return n > c
        }
        return false
    }

    private fun parseVersion(v: String): List<Int> {
        return v.removePrefix("v")
            .split(".", "-", "_")
            .mapNotNull { part -> Regex("""\d+""").find(part)?.value?.toIntOrNull() }
    }
}

object UpdateChecker {

    const val DEFAULT_REPO = "jorron87/FreeFCC"
    const val DEFAULT_API_URL = "https://api.github.com/repos/$DEFAULT_REPO/releases/latest"
    const val LEGACY_API_URL = "https://api.github.com/repos/doesthings/FreeFCC/releases/latest"

    /**
     * Fetches the latest release info from GitHub.
     * Returns null on any error (network, parse, etc).
     */
    fun fetchLatest(endpoint: String = DEFAULT_API_URL): UpdateInfo? {
        val trimmedEndpoint = endpoint.trim().ifBlank { DEFAULT_API_URL }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(trimmedEndpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "FreeFCC-App")
            }

            if (conn.responseCode != 200) return null

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseReleaseJson(body, trimmedEndpoint)
        } catch (e: Exception) {
            Log.w("FreeFCC-Update", "fetchLatest failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun parseReleaseJson(body: String, source: String): UpdateInfo? {
        val json = JSONObject(body)
        return if (json.has("assets")) {
            parseGithubRelease(json, source)
        } else {
            parseManifest(json, source)
        }
    }

    private fun parseGithubRelease(json: JSONObject, source: String): UpdateInfo? {
        val tagName = json.optString("tag_name", "").removePrefix("v")
        val name = json.optString("name", "v$tagName")
        val changelog = json.optString("body", "").trim()
        val publishedAt = json.optString("published_at", "")

        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        var apkSize = 0L
        var sha256: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val nameField = asset.optString("name", "")
            if (nameField.endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url", "")
                apkSize = asset.optLong("size", 0)
                sha256 = asset.optString("digest", "").removePrefix("sha256:").ifEmpty { null }
                break
            }
        }

        if (apkUrl == null) return null

        return UpdateInfo(
            version = tagName,
            title = name,
            changelog = changelog,
            downloadUrl = apkUrl,
            apkSize = apkSize,
            publishedAt = publishedAt,
            sha256 = sha256,
            source = source
        )
    }

    private fun parseManifest(json: JSONObject, source: String): UpdateInfo? {
        val version = json.optString("version", json.optString("tag_name", "")).removePrefix("v")
        val apkUrl = json.optString("apk_url", json.optString("download_url", ""))
        if (version.isBlank() || apkUrl.isBlank()) return null

        return UpdateInfo(
            version = version,
            title = json.optString("title", "v$version"),
            changelog = json.optString("changelog", json.optString("body", "")).trim(),
            downloadUrl = apkUrl,
            apkSize = json.optLong("apk_size", json.optLong("size", 0L)),
            publishedAt = json.optString("published_at", json.optString("date", "")),
            sha256 = json.optString("sha256", "").removePrefix("sha256:").ifEmpty { null },
            source = source
        )
    }

    /**
     * Downloads the APK file to the app cache directory.
     * Calls onProgress with bytes downloaded / total bytes.
     * Verifies the SHA-256 digest if the GitHub release provided one.
     * Returns the downloaded file, or null on failure (including hash mismatch).
     */
    fun downloadApk(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 30000
                setRequestProperty("User-Agent", "FreeFCC-App")
            }

            if (conn.responseCode != 200) return null

            val totalBytes = conn.contentLengthLong.coerceAtLeast(1L)
            val outputDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outputFile = File(outputDir, "freefcc_update.apk")
            val md = info.sha256?.let { MessageDigest.getInstance("SHA-256") }

            FileOutputStream(outputFile).use { fos ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        fos.write(buffer, 0, read)
                        md?.update(buffer, 0, read)
                        downloaded += read
                        onProgress((downloaded.toFloat() / totalBytes).coerceIn(0f, 1f))
                    }
                }
            }

            // Verify the digest if GitHub provided one. A mismatch means the
            // file was tampered with, the connection was MITM'd, or the
            // release changed underneath us — refuse to install in all cases.
            if (md != null) {
                val actual = md.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(info.sha256, ignoreCase = true)) {
                    outputFile.delete()
                    return null
                }
            }

            outputFile
        } catch (e: Exception) {
            Log.w("FreeFCC-Update", "downloadApk failed: ${e.javaClass.simpleName}: ${e.message}")
            try { File(context.cacheDir, "updates/freefcc_update.apk").delete() } catch (_: Exception) {}
            null
        } finally {
            conn?.disconnect()
        }
    }
}
