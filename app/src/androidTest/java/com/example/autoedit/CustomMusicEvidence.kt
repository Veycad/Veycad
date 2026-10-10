package com.veycad.app

import java.io.File
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Modifier
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Test-only snapshot of existing native evidence; never re-samples video or changes acceptance. */
internal object CustomMusicEvidence {
    fun save(result: VeycadAutomaticEditor.Result, source: File, music: File,
             output: File, destination: File): JSONObject {
        val winner = result.winner
        val execution = winner.artifacts?.report?.let { JSONObject(it.readText()) }
        val clock = VeykadRenderInspector.decodedSourceClock(winner.artifacts)
        val root = JSONObject().apply {
            put("format", "custom-music-native-evidence-v1")
            put("local_only", true)
            put("quality_gate", result.passedQualityGate)
            put("frames", winner.frames)
            put("files", JSONObject().apply {
                put("source", fileIdentity(source))
                put("music", fileIdentity(music))
                put("output", fileIdentity(output))
            })
            put("graph", json(winner.alternative.graph))
            put("acceptance", json(winner.acceptance))
            put("visual_samples", json(winner.samples))
            put("visual_map", json(result.pipeline.visualMap))
            put("audio_map", json(result.pipeline.audioMap))
            put("decoded_source_clock_available", clock.isNotEmpty())
            put("decoded_source_clock_entries", clock.size)
            put("decoded_source_clock_complete", winner.frames > 0 && clock.size == winner.frames &&
                clock.all { (outputUs, sourceUs) -> outputUs >= 0L && sourceUs >= 0L })
            put("decoded_source_clock", json(clock))
            // Preserve the complete inspector, including planned/decoded/secondary PTS and clips.
            put("execution", execution ?: JSONObject.NULL)
        }
        destination.writeText(root.toString(2))
        return JSONObject(destination.readText())
    }

    private fun fileIdentity(file: File) = JSONObject().apply {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        put("name", file.name)
        put("bytes", file.length())
        put("sha256", digest.digest().joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        })
    }

    /** Stored fields only, including nulls and primitive planes; no truncation or rounded metrics. */
    internal fun json(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is String, is Boolean, is Number -> value
        is Enum<*> -> value.name
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (key, item) -> put(key.toString(), json(item)) }
        }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(json(it)) } }
        else -> if (value.javaClass.isArray) {
            JSONArray().apply {
                repeat(ReflectArray.getLength(value)) { put(json(ReflectArray.get(value, it))) }
            }
        } else {
            require(value.javaClass.name.startsWith("com.veycad.app.")) {
                "Unsupported diagnostic value: ${value.javaClass.name}"
            }
            JSONObject().apply {
                value.javaClass.declaredFields.filterNot {
                    Modifier.isStatic(it.modifiers) || it.isSynthetic
                }.sortedBy { it.name }.forEach { field ->
                    field.isAccessible = true
                    put(field.name, json(field.get(value)))
                }
            }
        }
    }
}
