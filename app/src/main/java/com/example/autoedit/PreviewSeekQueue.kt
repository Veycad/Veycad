package com.veycad.app

/** Operation epochs, independent of project revision IDs (including revisions restored by undo). */
data class PreviewGeneration(val project: Long, val surface: Long, val seek: Long) {
    init {
        require(project >= 0 && surface >= 0 && seek >= 0) { "Preview epochs must be nonnegative" }
    }
}

/**
 * One latest pending raw output time, shared by UI callers and the serial GL worker.
 * Every operation uses the same monitor so epoch changes, authorization and pending state
 * are atomic. Taking a request consumes only the pending slot, not its current generation.
 *
 * [isCurrent] is a snapshot check. The owning controller must still guard actual GL/audio
 * presentation against later invalidation; this queue performs no rendering or playback.
 * Counter exhaustion throws before changing any state; the owner must stop/recreate its lifecycle.
 */
class PreviewSeekQueue internal constructor(initialEpochs: PreviewGeneration) {
    // Internal seed constructor exists only to exercise otherwise unreachable counter exhaustion.
    constructor() : this(PreviewGeneration(0, 0, 0))

    private val lock = Any()
    private var epochs = initialEpochs
    private var current: PreviewGeneration? = null
    private var pending: Pair<PreviewGeneration, Long>? = null

    /** No canonical-frame rounding or duration clamping: those belong to the controller/compiler. */
    fun submit(outputTimeUs: Long): PreviewGeneration = synchronized(lock) {
        require(outputTimeUs >= 0) { "Preview output time must be nonnegative" }
        val generation = epochs.copy(seek = nextEpoch(epochs.seek))
        val request = generation to outputTimeUs
        epochs = generation
        current = generation
        pending = request
        generation
    }

    fun takeLatest(): Pair<PreviewGeneration, Long>? = synchronized(lock) {
        val request = pending
        pending = null
        request
    }

    fun isCurrent(generation: PreviewGeneration): Boolean = synchronized(lock) {
        generation == current
    }

    fun invalidateProject(): Unit = synchronized(lock) {
        val next = epochs.copy(project = nextEpoch(epochs.project))
        epochs = next
        current = null
        pending = null
    }

    fun invalidateSurface(): Unit = synchronized(lock) {
        val next = epochs.copy(surface = nextEpoch(epochs.surface))
        epochs = next
        current = null
        pending = null
    }

    private fun nextEpoch(epoch: Long): Long {
        check(epoch < Long.MAX_VALUE) { "Preview epoch exhausted; recreate the owning lifecycle" }
        return epoch + 1
    }
}
