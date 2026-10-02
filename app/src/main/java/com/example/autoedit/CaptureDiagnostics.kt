package com.example.autoedit

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.IdentityHashMap

/** Explicit local export of capture events. Never exports media, paths or exception messages. */
internal object CaptureDiagnostics {
    enum class Stage { CAMERA_BIND, STORAGE_CHECK, MUSIC_ASSET, MUSIC_PLAYER, SESSION_CREATE,
        RECORDER_START, RECORD_FINALIZE, CONTAINER_FINALIZE, RECOVERY, TAKE_SELECTION, RENDER, REPORT_EXPORT }

    private val eventFields = setOf("time", "event", "stage", "style", "mode", "front", "quality",
        "duration_ms", "video_ms", "recorder_error", "player_what", "player_extra", "focus_change",
        "phase", "requested_stop", "has_stop_reason", "exception", "root_exception", "cause_depth",
        "material_code", "selected_take", "requested_take", "take", "eligible", "width", "height",
        "package", "version_name", "version_code", "android", "device", "cause_truncated")

    internal fun failureFields(failure: Throwable): Map<String, String> {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val causes = ArrayList<Throwable>()
        var next: Throwable? = failure
        while (next != null && causes.size < 8 && visited.add(next)) {
            causes += next
            next = next.cause
        }
        return buildMap {
            put("exception", failure.javaClass.name)
            put("root_exception", causes.last().javaClass.name)
            put("cause_depth", causes.size.toString())
            put("cause_truncated", (next != null).toString())
            causes.filterIsInstance<MaterialRejectedException>().firstOrNull()?.code
                ?.takeIf { it.matches(Regex("[a-z0-9_]{1,80}")) }
                ?.let { put("material_code", it) }
        }
    }

    fun failure(context: Context, stage: Stage, failure: Throwable, details: Map<String, String> = emptyMap()) {
        LocalDiagnostics.record(context, "capture_failed", details + failureFields(failure) +
            ("stage" to stage.name))
    }

    /** Read off the UI thread; whitelist capture fields rather than exporting general render logs. */
    fun report(context: Context): String {
        val events = LocalDiagnostics.recent(context, 10_000).mapNotNull { line ->
            runCatching {
                val row = JSONObject(line)
                if (!row.optString("event").startsWith("capture_")) return@runCatching null
                JSONObject().apply {
                    eventFields.filter(row::has).forEach { key -> put(key, row.get(key)) }
                }
            }.getOrNull()
        }
        return JSONObject().apply {
            put("format", "veykad-capture-diagnostics-v1")
            put("package", context.packageName)
            put("version_name", BuildConfig.VERSION_NAME)
            put("version_code", BuildConfig.VERSION_CODE)
            put("android_api", Build.VERSION.SDK_INT)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("events", JSONArray(events))
            put("privacy", "No video, audio, frames, faces, file names, paths or free-form error messages.")
        }.toString(2)
    }

    fun export(context: Context, destination: Uri): Boolean = runCatching {
        val payload = report(context)
        context.contentResolver.openOutputStream(destination)?.bufferedWriter()?.use { it.write(payload) }
            ?: error("Diagnostics destination is unavailable")
        true
    }.getOrElse {
        failure(context, Stage.REPORT_EXPORT, it)
        false
    }
}
