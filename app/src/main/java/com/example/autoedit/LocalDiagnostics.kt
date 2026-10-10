package com.veycad.app

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Small, local-only event trail for internal testing. It never contains media or face data. */
object LocalDiagnostics {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val KEEP_LINES = 10_000
    private const val PREFS = "local_diagnostics"
    private const val SESSION_ID = "session_id"

    @Synchronized
    fun record(context: Context, event: String, details: Map<String, String> = emptyMap()) {
        runCatching {
            val directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
            val file = File(directory, "events.jsonl")
            if (file.exists() && file.length() > MAX_BYTES) {
                file.writeText(file.readLines().takeLast(KEEP_LINES).joinToString("\n", postfix = "\n"))
            }
            val row = JSONObject().apply {
                put("time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
                put("event", event)
                put("session_id", sessionId(context))
                put("android", Build.VERSION.SDK_INT)
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("package", context.packageName)
                put("version_name", BuildConfig.VERSION_NAME)
                put("version_code", BuildConfig.VERSION_CODE)
                details.forEach { (key, value) -> put(key, value) }
            }
            file.appendText(row.toString() + "\n")
        }
    }

    @Synchronized fun recent(context: Context, limit: Int = 120): List<String> = runCatching {
        File(context.filesDir, "diagnostics/events.jsonl").takeIf(File::isFile)
            ?.readLines()?.takeLast(limit.coerceIn(1, KEEP_LINES)) ?: emptyList()
    }.getOrDefault(emptyList())

    fun export(context: Context, destination: android.net.Uri): Boolean = runCatching {
        val payload = JSONObject().apply {
            put("format", "veypad-local-diagnostics-v1")
            put("session_id", sessionId(context))
            put("generated_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            put("events", org.json.JSONArray(recent(context, KEEP_LINES)))
            put("privacy", "No video, audio, frame, face or user-entered text is included.")
        }
        context.contentResolver.openOutputStream(destination)?.bufferedWriter()?.use { it.write(payload.toString(2)) }
            ?: error("Diagnostics destination is unavailable")
        true
    }.getOrDefault(false)

    private fun sessionId(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(SESSION_ID, null) ?: UUID.randomUUID().toString().also {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SESSION_ID, it).apply()
        }
}
