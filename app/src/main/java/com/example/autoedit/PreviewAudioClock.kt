package com.veycad.app

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import java.io.File

/** All MediaPlayer operations and callbacks belong to this HandlerThread, never GL or main. */
internal class PreviewAudioClock(
    private val isCurrent: (PreviewGeneration) -> Boolean,
    private val onError: (PreviewGeneration, Throwable) -> Unit
) {
    private val thread = HandlerThread("Veycad-preview-audio").apply { start() }
    private val handler = Handler(thread.looper)
    private var player: MediaPlayer? = null
    private var generation: PreviewGeneration? = null
    private var durationUs = 0L
    private var anchorOutputUs = 0L
    private var anchorNs = 0L
    private var outputEndUs = 0L
    private var running = false
    private var ready = false
    private var preparedAudio: PreparedProjectAudio? = null
    private var composedAudio: ComposedProjectAudio? = null
    private var composedTimeline = false

    fun prepare(file: File?, token: PreviewGeneration, outputUs: Long, outputDurationUs: Long,
        snapshot: PreparedProjectAudio? = null, directory: File? = null, onReady: () -> Unit) {
        handler.post {
            if (!isCurrent(token)) return@post
            attempt(token) {
                releasePlayer()
                generation = token; anchorOutputUs = outputUs; ready = false
                outputEndUs = outputDurationUs
                if (preparedAudio !== snapshot) {
                    releaseComposition()
                    if (snapshot != null) {
                        val composed = snapshot.compose(requireNotNull(directory)) {
                            if (!isCurrent(token)) throw java.util.concurrent.CancellationException("Obsolete audio preparation")
                        }
                        if (!isCurrent(token)) { composed.close(); return@attempt }
                        composedAudio = composed; preparedAudio = snapshot
                    }
                }
                val playback = composedAudio?.playbackFile ?: file
                composedTimeline = snapshot != null
                if (playback == null) {
                    ready = true; onReady()
                    return@attempt
                }
                // Recreate for a user seek: an old native seek callback cannot be mistaken for a
                // new seek on the same MediaPlayer. At most one player borrows the composed WAV.
                val current = MediaPlayer().also { player = it }
                current.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                current.setOnErrorListener { _, what, extra ->
                    if (isCurrent(token)) onError(token, IllegalStateException("Music cannot play: $what/$extra"))
                    true
                }
                current.setOnPreparedListener {
                    if (player !== current || !isCurrent(token)) return@setOnPreparedListener
                    attempt(token) {
                        require(current.trackInfo.any { it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO } &&
                            current.trackInfo.none { it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO }) {
                            "Preview music must contain audio and no video track"
                        }
                        durationUs = current.duration.toLong() * 1_000
                        require(durationUs > 0) { "Preview music requires a finite positive duration" }
                        current.isLooping = !composedTimeline
                        current.setOnSeekCompleteListener {
                            if (player === current && isCurrent(token)) {
                                ready = true; onReady()
                            }
                        }
                        val position = if (composedTimeline) outputUs.coerceAtMost(outputEndUs)
                            else PreviewMusicPosition.seekPosition(outputUs, durationUs)
                        current.seekTo(position / 1_000,
                            MediaPlayer.SEEK_CLOSEST)
                    }
                }
                current.setDataSource(playback.absolutePath)
                current.prepareAsync()
                handler.postDelayed({
                    if (player === current && isCurrent(token) && !ready)
                        onError(token, IllegalStateException("Music preparation/seek timed out"))
                }, 10_000)
            }
        }
    }

    fun start(token: PreviewGeneration, authorized: () -> Boolean, onStarted: () -> Unit, onEnded: () -> Unit) {
        handler.post {
            if (generation != token || !ready || !isCurrent(token) || !authorized()) return@post
            attempt(token) {
                player?.start()
                // If invalidation raced an already-entered native start, immediately silence it.
                if (!isCurrent(token) || !authorized()) { player?.pause(); return@attempt }
                anchorNs = System.nanoTime(); running = true
                onStarted()
                // Enforce the global montage endpoint on the AUDIO owner even if GL is decoding.
                // MediaPlayer/Handler timing is millisecond granularity, not sample-accurate muxing.
                handler.postDelayed({
                    if (generation == token && isCurrent(token) && running) attempt(token) {
                        player?.pause(); running = false; anchorOutputUs = outputEndUs
                        onEnded()
                    }
                }, ((outputEndUs - anchorOutputUs).coerceAtLeast(0) + 999) / 1_000)
            }
        }
    }

    fun position(token: PreviewGeneration, onPosition: (Long) -> Unit) {
        handler.post {
            if (generation != token || !running || !isCurrent(token)) return@post
            attempt(token) {
                val prediction = anchorOutputUs + (System.nanoTime() - anchorNs).coerceAtLeast(0) / 1_000
                val current = player
                // Android documents getTimestamp's media anchor as the current playback position.
                // A null timestamp falls back to currentPosition (ms), then the monotonic anchor.
                val local = current?.let {
                    runCatching { it.timestamp?.anchorMediaTimeUs }.getOrNull()
                        ?: runCatching { it.currentPosition.toLong() * 1_000 }.getOrNull()
                }
                onPosition(if (current == null) prediction else if (composedTimeline)
                    (local ?: prediction).coerceIn(0, outputEndUs)
                    else PreviewMusicPosition.globalPosition(local, durationUs, prediction))
            }
        }
    }

    fun pause() { handler.post { if (running) { runCatching { player?.pause() }; running = false } } }

    /** Completion only follows release on the audio owner; callers must also await GL cleanup. */
    fun release(onReleased: (Throwable?) -> Unit) {
        handler.post { onReleased(runCatching { releasePlayer(); releaseComposition() }.exceptionOrNull()) }
    }
    fun close() { handler.post { releasePlayer(); releaseComposition(); thread.quitSafely() } }

    private fun releaseComposition() {
        composedAudio?.close()
        composedAudio = null; preparedAudio = null; composedTimeline = false
    }

    private fun releasePlayer() {
        player?.release()
        player = null; generation = null; ready = false; running = false
    }
    private inline fun attempt(token: PreviewGeneration, action: () -> Unit) {
        try { action() } catch (error: Throwable) { if (isCurrent(token)) onError(token, error) }
    }
}
