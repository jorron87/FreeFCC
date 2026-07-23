package com.freefcc.app

import android.annotation.SuppressLint
import android.os.Build

data class ControllerIdentity(
    val serial: String?,
    val serialSource: String?
)

object ControllerIdentityReader {
    @SuppressLint("MissingPermission")
    fun read(): ControllerIdentity {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add("Build.getSerial" to runCatching { Build.getSerial() }.getOrNull())
            }
            @Suppress("DEPRECATION")
            add("Build.SERIAL" to runCatching { Build.SERIAL }.getOrNull())
            add("ro.serialno" to readSystemProperty("ro.serialno"))
            add("ro.boot.serialno" to readSystemProperty("ro.boot.serialno"))
        }

        val match = candidates.firstOrNull { (_, value) -> value.isUsableSerial() }
        return ControllerIdentity(
            serial = match?.second?.trim(),
            serialSource = match?.first
        )
    }

    private fun readSystemProperty(name: String): String? = runCatching {
        val systemProperties = Class.forName("android.os.SystemProperties")
        systemProperties.getMethod("get", String::class.java).invoke(null, name) as? String
    }.getOrNull()

    private fun String?.isUsableSerial(): Boolean {
        val normalized = this?.trim().orEmpty()
        return normalized.isNotEmpty() &&
            !normalized.equals(Build.UNKNOWN, ignoreCase = true) &&
            !normalized.equals("unknown", ignoreCase = true)
    }
}
