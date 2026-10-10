package com.veycad.app

/** Capture instructions are authored suggestions, never evidence that a movement occurred. */
internal data class CaptureScript(
    val id: String,
    val styleId: String,
    val version: Int,
    val durationMs: Long,
    val cues: List<Cue>
) {
    data class Cue(val atMs: Long, val textRes: Int)
    init {
        require(durationMs >= EditDurationPolicy.MINIMUM_MS)
        require(version > 0 && cues.isNotEmpty() && cues.first().atMs == 0L)
        require(cues.zipWithNext().all { (a, b) -> a.atMs < b.atMs })
        require(cues.all { it.atMs in 0 until durationMs && it.textRes != 0 })
    }
    fun cueAt(timeMs: Long): Cue = cues.lastOrNull { it.atMs <= timeMs } ?: cues.first()
    fun nextCueAt(timeMs: Long): Cue? = cues.firstOrNull { it.atMs > timeMs }

    companion object {
        const val COUNTDOWN_SECONDS = 5
        const val RECOVERY_MS = 5_000L
        const val DEFAULT_TAKES = 3
        const val MAX_TAKES = 5
        // Prototype scripts need physical capture and artistic acceptance before release.
        val fear = CaptureScript("fear-capture", "fear_strobe", 1, 27_000L, listOf(
            Cue(0, R.string.capture_cue_move),
            Cue(4_000, R.string.capture_cue_turn),
            Cue(8_000, R.string.capture_cue_close),
            Cue(12_000, R.string.capture_cue_gaze),
            Cue(15_000, R.string.capture_cue_wide),
            Cue(20_000, R.string.capture_cue_turn_again),
            Cue(24_000, R.string.capture_cue_hold)
        ))
        val heartbeat = CaptureScript("heartbeat-capture", "heartbeat", 1, 32_000L, listOf(
            Cue(0, R.string.capture_cue_turn),
            Cue(5_000, R.string.capture_cue_gaze),
            Cue(10_000, R.string.capture_cue_close),
            Cue(16_000, R.string.capture_cue_light),
            Cue(21_000, R.string.capture_cue_wide),
            Cue(26_000, R.string.capture_cue_smooth),
            Cue(29_000, R.string.capture_cue_hold)
        ))
        fun forStyle(id: String): CaptureScript? = when (id) {
            fear.styleId -> fear
            heartbeat.styleId -> heartbeat
            else -> null
        }
    }
}

internal enum class CaptureMode { MUSIC, BEST_TAKE }

/** Timing below is recording elapsed time; persisted cue observations retain clock uncertainty. */
internal object CaptureTakeTimeline {
    // CameraX can report the final encoded frame just before the scripted boundary.
    const val COMPLETION_TOLERANCE_MS = 250L
    data class Window(val ordinal: Int, val startMs: Long, val endMs: Long, val complete: Boolean) {
        init { require(ordinal >= 1 && startMs >= 0 && endMs > startMs) }
        val durationMs get() = endMs - startMs
    }
    data class Position(val ordinal: Int, val offsetMs: Long, val recovering: Boolean, val finished: Boolean)

    fun totalDurationMs(script: CaptureScript, count: Int): Long {
        require(count in 1..CaptureScript.MAX_TAKES)
        return script.durationMs * count + CaptureScript.RECOVERY_MS * (count - 1)
    }
    fun position(script: CaptureScript, count: Int, elapsedMs: Long): Position {
        require(count in 1..CaptureScript.MAX_TAKES && elapsedMs >= 0)
        if (elapsedMs >= totalDurationMs(script, count)) return Position(count, script.durationMs, false, true)
        val cycle = script.durationMs + CaptureScript.RECOVERY_MS
        val index = (elapsedMs / cycle).toInt()
        val offset = elapsedMs % cycle
        return Position(index + 1, offset, offset >= script.durationMs, false)
    }
    fun windows(script: CaptureScript, count: Int, recordedDurationMs: Long): List<Window> {
        require(count in 1..CaptureScript.MAX_TAKES && recordedDurationMs >= 0)
        return (0 until count).mapNotNull { index ->
            val start = index * (script.durationMs + CaptureScript.RECOVERY_MS)
            val end = minOf(start + script.durationMs, recordedDurationMs)
            if (end <= start) null else Window(index + 1, start, end,
                end - start >= script.durationMs - COMPLETION_TOLERANCE_MS)
        }
    }

    fun usableForMontage(window: Window): Boolean =
        window.complete && window.durationMs >= EditDurationPolicy.MINIMUM_MS
}
