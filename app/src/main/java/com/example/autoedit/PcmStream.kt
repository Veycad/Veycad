package com.veycad.app

/** Decoder boundary: signed 16-bit interleaved PCM. Multichannel decoding must downmix upstream. */
data class PcmFormat(val sampleRate: Int, val channels: Int) {
    init { require(sampleRate in 8_000..192_000 && channels in 1..2) }
}

class PcmChunk(val format: PcmFormat, val startPtsUs: Long, samples: ShortArray) {
    init {
        require(startPtsUs >= 0)
        require(samples.isNotEmpty() && samples.size % format.channels == 0)
        require(samples.size <= format.sampleRate * format.channels) { "PCM chunk exceeds one second" }
        val durationCeilUs = (samples.size.toLong() / format.channels * 1_000_000 + format.sampleRate - 1) / format.sampleRate
        require(startPtsUs <= Long.MAX_VALUE - durationCeilUs) { "PCM PTS overflow" }
    }
    private val data = samples.copyOf()
    val frames: Int get() = data.size / format.channels
    internal val retainedSamples: Int get() = data.size
    internal fun sample(frame: Int, channel: Int): Double = data[frame * format.channels + channel] / 32_768.0
}

interface PcmStream : AutoCloseable {
    val format: PcmFormat
    /** Ordered raw-source PTS chunks, at most one second each. null means genuine EOF only.
     * IO/decode failures and cancellation throw. The implementation must not retain previous chunks.
     * It owns its decoder/extractor; close releases those resources exactly once. */
    fun read(): PcmChunk?
}

fun interface PcmStreamProvider {
    /** Transfers a fresh stream to the caller. Seek/reopen at or BEFORE requested PTS, with
     * a preceding sample for interpolation. Repeated windows may have the same stable ID.
     * null means an explicitly absent audio track, never an IO/decode failure. Music providers
     * receive ID "app-music" and output PTS zero; loop music upstream in bounded chunks. */
    fun open(sourceId: String, startPtsUs: Long): PcmStream?
}

fun interface PcmSink {
    /** Synchronous borrowed scratch: consume/copy frames*channels samples before returning.
     * startFrame is an absolute output sample-frame index (not source PTS). The caller owns
     * and closes the sink/file; composition owns only provider streams. */
    fun write(format: PcmFormat, startFrame: Long, samples: ShortArray, frames: Int)
}
