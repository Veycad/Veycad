package com.veycad.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Conversion of one actual decoder output buffer; never guesses encoding from the request.
 * Numeric encodings are Android PCM16=2, PCM8=3, float=4. No Android runtime needed here. */
internal object DecodedPcmBuffer {
    fun convert(bytes: ByteBuffer, rate: Int, channels: Int, encoding: Int, ptsUs: Long): PcmChunk? {
        require(rate in 8_000..192_000 && channels in 1..8)
        val width = when (encoding) { 2 -> 2; 3 -> 1; 4 -> 4; else -> throw IllegalArgumentException("Unsupported decoder PCM encoding $encoding") }
        val input = bytes.slice().order(ByteOrder.LITTLE_ENDIAN)
        require(input.remaining() % (channels * width) == 0) { "Partial decoded PCM frame" }
        val count = input.remaining() / (channels * width)
        require(count <= rate) { "Decoder output buffer exceeds one second" }
        if (count == 0) return null
        // Discard only negative codec priming. Positive source PTS and gaps stay on their raw axis.
        if (ptsUs < -(count * 1_000_000L / rate)) return null
        val skip = if (ptsUs < 0) ((-ptsUs * rate + 999_999) / 1_000_000).toInt().coerceAtMost(count) else 0
        if (skip == count) return null
        input.position(skip * channels * width)
        val outputChannels = if (channels <= 2) channels else 1
        val samples = ShortArray((count - skip) * outputChannels)
        fun sample(): Double = when (encoding) {
            2 -> input.short / 32768.0
            3 -> ((input.get().toInt() and 255) - 128) / 128.0
            else -> input.float.toDouble().also { require(it.isFinite()) { "Nonfinite decoded PCM" } }
        }
        fun quantize(value: Double) = (value.coerceIn(-1.0, 1.0) * 32768).roundToInt().coerceIn(-32768, 32767).toShort()
        if (channels <= 2) samples.indices.forEach { samples[it] = quantize(sample()) }
        else samples.indices.forEach { index ->
            var sum = 0.0
            repeat(channels) { sum += sample() }
            samples[index] = quantize(sum / channels)
        }
        val start = Math.addExact(ptsUs, (skip * 1_000_000L + rate / 2) / rate)
        return PcmChunk(PcmFormat(rate, outputChannels), start, samples)
    }
}
