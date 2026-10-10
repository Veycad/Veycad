package com.veycad.app

/** Selected app-music tail loops at its actual decoded EOF. Retains one bounded chunk,
 * preserves measured gaps, and assigns output sample-clock PTS starting at zero. */
internal class LoopingMusicPcmStream(private val provider: PcmStreamProvider, private val startUs: Long) : PcmStream {
    init { require(startUs >= 0) }
    private var stream: PcmStream? = requireNotNull(provider.open("app-music", startUs)) { "Music has no audio track" }
    override val format = requireNotNull(stream).format
    private var pending: PcmChunk? = null
    private var skip = 0
    private var gap = 0L
    private var cycleFrames = 0L
    private var outputFrames = 0L
    private var closed = false

    override fun read(): PcmChunk {
        check(!closed)
        while (pending == null) {
            if (stream == null) {
                stream = requireNotNull(provider.open("app-music", startUs)) { "Music track disappeared" }
                check(stream!!.format == format) { "Music format changed after reopen" }
            }
            val chunk = stream!!.read()
            if (chunk == null) {
                check(cycleFrames > 0) { "Selected music tail is empty" }
                val ended = stream; stream = null; ended!!.close()
                cycleFrames = 0
                continue
            }
            check(chunk.format == format) { "Music PCM format changed" }
            val relative = Math.multiplyExact(chunk.startPtsUs - startUs, format.sampleRate.toLong())
            // First complete sample at/after the selected raw music offset.
            skip = if (relative < 0) ((-relative + 999_999) / 1_000_000).coerceAtMost(chunk.frames.toLong()).toInt() else 0
            if (skip == chunk.frames) continue
            val position = (relative + skip * 1_000_000L + 500_000) / 1_000_000
            check(position >= cycleFrames - 1) { "Unordered music chunks" }
            gap = (position - cycleFrames).coerceAtLeast(0)
            pending = chunk
        }
        val chunk = requireNotNull(pending)
        val count = if (gap > 0) minOf(gap, 1024L).toInt() else chunk.frames - skip
        val samples = ShortArray(count * format.channels)
        if (gap > 0) gap -= count else {
            repeat(count) { frame -> repeat(format.channels) { channel ->
                samples[frame * format.channels + channel] = (chunk.sample(frame + skip, channel) * 32768).toInt().toShort()
            } }
            pending = null
        }
        val pts = AudioExportPlan.presentationTimeUs(outputFrames, format.sampleRate)
        outputFrames = Math.addExact(outputFrames, count.toLong()); cycleFrames += count
        return PcmChunk(format, pts, samples)
    }

    override fun close() {
        if (closed) return
        closed = true; pending = null
        val owned = stream; stream = null; owned?.close()
    }
}
