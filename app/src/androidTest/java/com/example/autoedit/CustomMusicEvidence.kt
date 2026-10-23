package com.veycad.app

import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.security.MessageDigest

/** Test-only snapshot of existing native evidence; never re-samples video or changes acceptance. */
internal object CustomMusicEvidence {
    data class Summary(
        val qualityGate: Boolean,
        val visualSamples: Int,
        val audioStartUs: Long?,
        val clockEntries: Int,
        val clockComplete: Boolean
    )

    fun save(result: VeycadAutomaticEditor.Result, source: File, music: File,
             output: File, destination: File): Summary {
        val winner = result.winner
        val execution = winner.artifacts?.report
        val clock = execution?.let(::readClock).orEmpty()
        val summary = Summary(result.passedQualityGate, winner.samples.size,
            winner.alternative.graph.audioTrack?.sourceStartUs, clock.size,
            winner.frames > 0 && clock.size == winner.frames &&
                clock.all { (outputUs, sourceUs) -> outputUs >= 0L && sourceUs >= 0L })
        // This small map holds references to existing models, not a second JSON object tree.
        val fields = linkedMapOf<String, Any?>(
            "format" to "custom-music-native-evidence-v1",
            "local_only" to true,
            "quality_gate" to result.passedQualityGate,
            "frames" to winner.frames,
            "files" to linkedMapOf("source" to fileIdentity(source),
                "music" to fileIdentity(music), "output" to fileIdentity(output)),
            "graph" to winner.alternative.graph,
            "acceptance" to winner.acceptance,
            "visual_samples" to winner.samples,
            "visual_map" to result.pipeline.visualMap,
            "audio_map" to result.pipeline.audioMap,
            "decoded_source_clock_available" to clock.isNotEmpty(),
            "decoded_source_clock_entries" to summary.clockEntries,
            "decoded_source_clock_complete" to summary.clockComplete,
            "decoded_source_clock" to clock,
            "execution" to execution?.let { CustomMusicJsonWriter.InspectorDocument(it) }
        )
        CustomMusicJsonWriter.write(destination, fields)
        return summary // Do not read/parse the pixel planes again for a few assertions.
    }

    private fun fileIdentity(file: File): Map<String, Any> {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return mapOf("name" to file.name, "bytes" to file.length(), "sha256" to
            digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
    }

    /** Only the compact output/source PTS map is retained; other inspector tokens are skipped. */
    internal fun readClock(file: File): Map<Long, Long> = linkedMapOf<Long, Long>().apply {
        JsonReader(file.bufferedReader(Charsets.UTF_8, CustomMusicJsonWriter.BUFFER_CHARS)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "frames") {
                    reader.skipValue()
                    continue
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    var outputUs: Long? = null
                    var sourceUs: Long? = null
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "output_us" -> outputUs = reader.nextLong()
                            "decoded_source_us" -> if (reader.peek() == JsonToken.NULL) {
                                reader.nextNull()
                            } else sourceUs = reader.nextLong()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    sourceUs?.let { put(requireNotNull(outputUs), it) }
                }
                reader.endArray()
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing inspector JSON data" }
        }
    }
}
