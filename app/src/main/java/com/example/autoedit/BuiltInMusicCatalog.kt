package com.veycad.app

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/** Local phonk catalog. The authored reference track is bundled with the user's permission. */
object BuiltInMusicCatalog {
    data class Track(
        val id: String,
        val title: String,
        val bpm: Int,
        val rootHz: Double,
        val pattern: IntArray,
        val swing: Double,
        val rawResourceId: Int? = null
    )

    data class Selection(val styleId: String, val track: Track, val file: File)

    private val heartbeatTrack = Track(
        "heartbeat_author",
        "Heartbeat",
        120,
        49.00,
        intArrayOf(0, 3, 8, 7),
        .10,
        R.raw.heartbeat_author
    )

    private val fearTrack = Track(
        "fear_strobe_author",
        "Fear",
        122,
        49.00,
        intArrayOf(0, 3, 8, 7),
        .10,
        R.raw.fear_strobe_author
    )

    private val dualityTrack = Track(
        "duality_loop_author",
        "Duality",
        108,
        49.00,
        intArrayOf(0, 3, 8, 7),
        .10,
        R.raw.duality_loop_author
    )

    val tracks: List<Track> = listOf(
        Track("neon_drift", "Neon Drift", 156, 55.00, intArrayOf(0, 3, 5, 7), .03),
        Track("midnight_torque", "Midnight Torque", 148, 49.00, intArrayOf(0, 0, 7, 5), .07),
        Track("chrome_ghost", "Chrome Ghost", 164, 58.27, intArrayOf(0, 5, 3, 10), .02),
        Track("asphalt_ritual", "Asphalt Ritual", 142, 46.25, intArrayOf(0, 7, 5, 3), .09),
        Track("grave_circuit", "Grave Circuit", 154, 51.91, intArrayOf(0, 7, 3, 10), .065),
        Track("night_lane", "Night Lane", 152, 51.91, intArrayOf(0, 5, 7, 3), .06),
        Track("shadow_clutch", "Shadow Clutch", 160, 43.65, intArrayOf(0, 0, 3, 7), .04),
        Track("violet_rev", "Violet Rev", 146, 65.41, intArrayOf(0, 8, 7, 3), .08),
        Track("concrete_pulse", "Concrete Pulse", 158, 55.00, intArrayOf(0, 10, 7, 5), .05),
        Track(
            "leonid_reentry",
            "Leonid Re-entry",
            121,
            49.00,
            intArrayOf(0, 3, 8, 7),
            .10,
            R.raw.leonid_reentry_phonk
        )
    )

    fun select(context: Context, styleId: String): Selection {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val track = trackForStyle(styleId)
        preferences.edit().putString(LAST_TRACK, track.id).apply()
        val directory = File(context.filesDir, "built-in-music").apply { mkdirs() }
        val resourceId = track.rawResourceId
        val file = File(
            directory,
            if (resourceId == null) "${track.id}-$GENERATOR_VERSION.wav" else "${track.id}-$ASSET_VERSION.m4a"
        )
        if (!file.isFile || file.length() < minimumFileBytes(track)) {
            if (resourceId == null) {
                writeWave(file, synthesize(track))
            } else {
                copyResource(context, resourceId, file)
            }
        }
        return Selection(styleId, track, file)
    }

    /** Each authored montage recipe owns its score; sharing the Sigma score with Heartbeat
     * activates the wrong beat map and source-selection profile before Heartbeat is built. */
    internal fun trackForStyle(styleId: String): Track = when (styleId) {
        MontageStyleCatalog.sigma.id ->
            tracks.single { it.id == ReferenceMontageProfile.AUTHOR_TRACK_ID }
        MontageStyleCatalog.heartbeat.id -> heartbeatTrack
        MontageStyleCatalog.fearStrobe.id -> fearTrack
        MontageStyleCatalog.dualityLoop.id -> dualityTrack
        MontageStyleCatalog.galleryMontage.id -> tracks.single { it.id == "neon_drift" }
        else -> error("No production music route for montage style: $styleId")
    }

    internal fun synthesize(
        track: Track,
        sampleRate: Int = SAMPLE_RATE,
        durationSeconds: Int = DURATION_SECONDS
    ): ShortArray {
        require(sampleRate >= 8_000 && durationSeconds > 0)
        val output = ShortArray(sampleRate * durationSeconds)
        val beatSeconds = 60.0 / track.bpm
        var noiseState = track.id.hashCode().toUInt().toLong().coerceAtLeast(1L)
        for (index in output.indices) {
            val time = index.toDouble() / sampleRate
            val beatPosition = time / beatSeconds
            val beatIndex = beatPosition.toInt()
            val beatTime = (beatPosition - beatIndex) * beatSeconds
            val halfBeat = beatSeconds * .5
            val halfTime = time % halfBeat

            val kickEnvelope = exp(-beatTime * 15.0)
            val kick = sin(2.0 * PI * (48.0 * beatTime + 7.0 * (1.0 - exp(-beatTime * 22.0)))) *
                kickEnvelope * if (beatIndex % 4 == 0) 1.0 else .78

            noiseState = (noiseState * 1_664_525L + 1_013_904_223L) and 0xffffffffL
            val noise = noiseState.toDouble() / 0xffffffffL * 2.0 - 1.0
            val snareHit = beatIndex % 4 == 1 || beatIndex % 4 == 3
            val snare = if (snareHit) noise * exp(-beatTime * 24.0) * .42 else 0.0
            val hat = noise * exp(-halfTime * 70.0) * .12

            val step = ((beatPosition * 2.0).toInt() + if (beatIndex % 2 == 1) 1 else 0)
            val note = track.pattern[(step / 2) % track.pattern.size]
            val frequency = track.rootHz * Math.pow(2.0, note / 12.0)
            val bassTime = time % (beatSeconds * 2.0)
            val bass = sin(2.0 * PI * frequency * time) * exp(-bassTime * 1.7) * .46

            val swungStep = beatPosition * 2.0 + if (beatIndex % 2 == 1) track.swing else 0.0
            val cowbellTime = (swungStep - swungStep.toInt()) * halfBeat
            val cowbellFrequency = frequency * 10.0
            val cowbell = (sin(2.0 * PI * cowbellFrequency * time) +
                .45 * sin(2.0 * PI * cowbellFrequency * 1.48 * time)) *
                exp(-cowbellTime * 14.0) * .16

            val bar = (beatIndex / 4) % 8
            val lift = if (bar >= 6) 1.12 else 1.0
            val mixed = tanh((kick * .78 + snare + hat + bass + cowbell) * lift * 1.35)
            output[index] = (mixed.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
        return output
    }

    private fun writeWave(target: File, samples: ShortArray) {
        val temporary = File(target.parentFile, "${target.name}.partial")
        BufferedOutputStream(FileOutputStream(temporary)).use { output ->
            val dataBytes = samples.size * 2
            output.write("RIFF".toByteArray())
            output.writeLe32(36 + dataBytes)
            output.write("WAVEfmt ".toByteArray())
            output.writeLe32(16)
            output.writeLe16(1)
            output.writeLe16(1)
            output.writeLe32(SAMPLE_RATE)
            output.writeLe32(SAMPLE_RATE * 2)
            output.writeLe16(2)
            output.writeLe16(16)
            output.write("data".toByteArray())
            output.writeLe32(dataBytes)
            samples.forEach { output.writeLe16(it.toInt()) }
        }
        check(temporary.renameTo(target) || runCatching {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }.isSuccess) { "Unable to create built-in music" }
    }

    private fun copyResource(context: Context, resourceId: Int, target: File) {
        val temporary = File(target.parentFile, "${target.name}.partial")
        context.resources.openRawResource(resourceId).use { input ->
            FileOutputStream(temporary).use { output -> input.copyTo(output) }
        }
        check(temporary.renameTo(target) || runCatching {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }.isSuccess) { "Unable to install built-in music" }
    }

    private fun minimumFileBytes(track: Track): Long =
        if (track.rawResourceId == null) MINIMUM_WAVE_BYTES else MINIMUM_ASSET_BYTES

    private fun BufferedOutputStream.writeLe16(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private fun BufferedOutputStream.writeLe32(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 24 and 0xff)
    }

    private const val PREFERENCES = "built_in_music"
    private const val LAST_TRACK = "last_track"
    private const val GENERATOR_VERSION = 1
    private const val ASSET_VERSION = 1
    private const val SAMPLE_RATE = 44_100
    private const val DURATION_SECONDS = 24
    private const val MINIMUM_WAVE_BYTES = 1_000_000L
    private const val MINIMUM_ASSET_BYTES = 50_000L
}
