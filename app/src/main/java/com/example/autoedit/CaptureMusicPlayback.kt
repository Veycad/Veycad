package com.veycad.app

import android.media.MediaPlayer

/** Main-thread playback. Rewind during the break; a seek callback never starts music. */
internal class CaptureMusicPlayback(
    private val output: Output,
    private val onEvent: (String) -> Unit,
    private val onFailure: (String) -> Unit
) {
    internal interface Output {
        fun start()
        fun pause()
        fun rewind(onComplete: () -> Unit)
    }

    private var readyTake = 1 // A newly prepared local player is already at the beginning.
    private var playingTake = 0
    private var pendingTake: Int? = null
    private var closed = false

    fun update(position: CaptureTakeTimeline.Position) {
        if (closed || position.finished) return
        if (!position.recovering) {
            if (playingTake == position.ordinal) return
            if (readyTake != position.ordinal || pendingTake != null) {
                fail("Музыка не успела подготовиться к следующему дублю. Запись остановлена")
                return
            }
            // Observe the paused/prepared position before start: the playback clock can
            // rebase immediately after start and is not an acoustic zero-time measurement.
            onEvent("music-start-request:${position.ordinal}")
            if (perform { output.start() }) {
                playingTake = position.ordinal
                onEvent("music-start:${position.ordinal}")
            }
            return
        }

        if (playingTake != 0) {
            if (!perform { output.pause() }) return
            playingTake = 0
            onEvent("music-pause:${position.ordinal}")
        }
        val next = position.ordinal + 1
        if (readyTake == next || pendingTake == next) return
        if (pendingTake != null) {
            fail("Музыка не успела подготовиться к следующему дублю. Запись остановлена")
            return
        }
        readyTake = 0
        pendingTake = next
        onEvent("music-seek-request:$next")
        perform {
            output.rewind {
                if (!closed && pendingTake == next) {
                    pendingTake = null
                    readyTake = next
                    onEvent("music-seek-complete:$next")
                }
            }
        }
    }

    fun close() {
        closed = true
        pendingTake = null
    }

    private fun perform(action: () -> Unit): Boolean = try {
        action()
        true
    } catch (_: RuntimeException) {
        fail("Музыка прервана. Запись остановлена")
        false
    }

    private fun fail(message: String) {
        close()
        onFailure(message)
    }

    companion object {
        fun forPlayer(player: MediaPlayer, onEvent: (String) -> Unit, onFailure: (String) -> Unit) =
            CaptureMusicPlayback(object : Output {
                override fun start() = player.start()
                override fun pause() = player.pause()
                override fun rewind(onComplete: () -> Unit) {
                    player.setOnSeekCompleteListener { onComplete() }
                    player.seekTo(0, MediaPlayer.SEEK_CLOSEST)
                }
            }, onEvent, onFailure)
    }
}
