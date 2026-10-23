package com.veycad.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/** Every authored montage recipe owns one bundled score. */
object BuiltInMusicCatalog {
    data class Track(val id: String, val title: String, val bpm: Int, val rawResourceId: Int)
    data class Selection(val styleId: String, val track: Track, val file: File)

    private val sigmaTrack = Track("leonid_reentry", "Leonid Re-entry", 121, R.raw.leonid_reentry_phonk)
    private val heartbeatTrack = Track("heartbeat_author", "Heartbeat", 120, R.raw.heartbeat_author)
    private val fearTrack = Track("fear_strobe_author", "Fear", 122, R.raw.fear_strobe_author)
    private val dualityTrack = Track("duality_loop_author", "Duality", 108, R.raw.duality_loop_author)
    val tracks: List<Track> = listOf(sigmaTrack, heartbeatTrack, fearTrack, dualityTrack)

    fun select(context: Context, styleId: String): Selection {
        val track = trackForStyle(styleId)
        val directory = File(context.filesDir, "built-in-music").apply { mkdirs() }
        val file = File(directory, "${track.id}-$ASSET_VERSION.m4a")
        if (!file.isFile || file.length() < MINIMUM_ASSET_BYTES) copyResource(context, track.rawResourceId, file)
        return Selection(styleId, track, file)
    }

    internal fun trackForStyle(styleId: String): Track = when (styleId) {
        MontageStyleCatalog.sigma.id -> sigmaTrack
        MontageStyleCatalog.heartbeat.id -> heartbeatTrack
        MontageStyleCatalog.fearStrobe.id -> fearTrack
        MontageStyleCatalog.dualityLoop.id -> dualityTrack
        else -> error("No production music route for montage style: $styleId")
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

    private const val ASSET_VERSION = 1
    private const val MINIMUM_ASSET_BYTES = 50_000L
}
