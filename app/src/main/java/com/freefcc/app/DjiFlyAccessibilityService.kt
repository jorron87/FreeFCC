package com.freefcc.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.Locale

internal object DjiFlyHomePointMatcher {
    private val whitespace = Regex("\\s+")

    fun normalize(value: CharSequence): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(whitespace, " ")
            .trim()
            .trimEnd('.', '!', '?', '\u3002', '\uff01', '\uff1f')

    fun matches(value: CharSequence, phrases: Set<String>): Boolean =
        normalize(value) in phrases
}

/**
 * Observes localized Home Point text from the original DJI Fly app.
 *
 * This service never opens a controller socket and never sends DUML itself.
 */
class DjiFlyAccessibilityService : AccessibilityService() {
    private var homePointPhrases: Set<String> = emptySet()
    private var lastMatch = ""
    private var lastMatchAtMs = 0L
    private var lastDjiFlyEventType = "periodic_window_poll"
    private val snapshotHandler = Handler(Looper.getMainLooper())
    private val snapshotRunnable = object : Runnable {
        override fun run() {
            captureDjiFlyWindow()
            snapshotHandler.postDelayed(this, UI_SNAPSHOT_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        homePointPhrases = loadPhrases()
        Log.i(TAG, "Connected with ${homePointPhrases.size} localized Home Point phrases")
        snapshotHandler.removeCallbacks(snapshotRunnable)
        snapshotHandler.post(snapshotRunnable)
        if (FccKeepaliveService.isRunningFlagSet(this)) {
            FccKeepaliveService.start(this)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != DJI_FLY_PACKAGE) return
        lastDjiFlyEventType = AccessibilityEvent.eventTypeToString(event.eventType)
        val values = buildSet {
            event.text.filterNotNull().forEach { if (it.isNotBlank()) add(it.toString()) }
            event.contentDescription?.takeIf { it.isNotBlank() }?.let { add(it.toString()) }
        }
        for (value in values) {
            if (!DjiFlyHomePointMatcher.matches(value, homePointPhrases)) continue
            val normalized = DjiFlyHomePointMatcher.normalize(value)
            val now = System.currentTimeMillis()
            if (normalized == lastMatch && now - lastMatchAtMs < LOCAL_DEBOUNCE_MS) continue
            lastMatch = normalized
            lastMatchAtMs = now
            val accepted = FccKeepaliveService.notifyHomePointDetected()
            Log.i(TAG, "Home Point matched; auto_fcc_trigger_accepted=$accepted")
        }
    }

    private fun captureDjiFlyWindow() {
        if (!DjiFlyUiSnapshotBus.isCaptureRequested()) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != DJI_FLY_PACKAGE) return

        val values = mutableListOf<CharSequence?>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var traversalTruncated = false

        while (queue.isNotEmpty() && visited < DjiFlyUiSnapshotPolicy.MAX_NODES) {
            val node = queue.removeFirst()
            visited += 1
            if (runCatching { node.isVisibleToUser }.getOrDefault(false)) {
                values += runCatching { node.text }.getOrNull()
                values += runCatching { node.contentDescription }.getOrNull()
            }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (index in 0 until childCount) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                if (visited + queue.size >= DjiFlyUiSnapshotPolicy.MAX_NODES) {
                    traversalTruncated = true
                    break
                }
                queue.addLast(child)
            }
        }
        if (queue.isNotEmpty()) traversalTruncated = true

        DjiFlyUiSnapshotBus.publish(
            wallTimeMs = System.currentTimeMillis(),
            elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
            eventType = lastDjiFlyEventType,
            values = values,
            visitedNodeCount = visited,
            traversalTruncated = traversalTruncated
        )
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        snapshotHandler.removeCallbacks(snapshotRunnable)
        super.onDestroy()
    }

    @SuppressLint("AppBundleLocaleChanges", "DiscouragedApi")
    private fun loadPhrases(): Set<String> {
        val packageContext = try {
            createPackageContext(DJI_FLY_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        } catch (e: Exception) {
            Log.w(TAG, "DJI Fly resources unavailable", e)
            return emptySet()
        }
        val baseResources = packageContext.resources
        val localeTags = buildSet {
            baseResources.assets.locales.filter(String::isNotBlank).forEach(::add)
            baseResources.configuration.locales.let { locales ->
                for (index in 0 until locales.size()) add(locales[index].toLanguageTag())
            }
            add(Locale.ENGLISH.toLanguageTag())
        }
        return buildSet {
            for (languageTag in localeTags) {
                val resources = packageContext.createConfigurationContext(
                    Configuration(baseResources.configuration).apply {
                        setLocale(Locale.forLanguageTag(languageTag))
                    }
                ).resources
                for (name in HOME_POINT_RESOURCE_NAMES) {
                    val id = resources.getIdentifier(name, "string", DJI_FLY_PACKAGE)
                    if (id == 0) continue
                    runCatching { resources.getText(id) }
                        .getOrNull()
                        ?.let(DjiFlyHomePointMatcher::normalize)
                        ?.takeIf(String::isNotEmpty)
                        ?.let(::add)
                }
            }
        }
    }

    companion object {
        private const val TAG = "FreeFCC-HomePoint"
        private const val DJI_FLY_PACKAGE = "dji.go.v5"
        private const val LOCAL_DEBOUNCE_MS = 10_000L
        private const val UI_SNAPSHOT_INTERVAL_MS = 1_000L
        private val HOME_POINT_RESOURCE_NAMES = listOf(
            "fpv_tips_smart_rth_homepoint_update",
            "fpv_setting_shortcut_update_return_point_succeed_toast",
            "fpv_setting_safe_return_point_update_window_current_beacon_location_note",
            "fpv_setting_safe_return_point_update_window_current_control_location_note",
            "fpv_setting_safe_return_point_update_window_current_drone_location_note"
        )
    }
}
