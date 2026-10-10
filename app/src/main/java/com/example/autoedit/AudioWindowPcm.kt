package com.veycad.app

/** Exact windows of an already prepared, full repeated track in interleaved PCM16. */
object AudioWindowPcm {
    /**
     * Copies whole frames without changing their amplitude or the source bytes.
     * The result is limited to 32 MiB; this does not account for other live runtime resources.
     */
    fun slice(
        bytes: ByteArray,
        sampleRate: Int,
        channels: Int,
        globalStartUs: Long,
        durationUs: Long
    ): ByteArray {
        require(sampleRate > 0 && channels > 0 && globalStartUs >= 0L && durationUs >= 0L)
        val frameBytesLong = channels.toLong() * 2L
        require(bytes.isNotEmpty() && frameBytesLong <= bytes.size && bytes.size % frameBytesLong == 0L) {
            "PCM must contain integral, nonempty channel frames"
        }
        require(globalStartUs <= Long.MAX_VALUE - durationUs) { "Global window end overflows" }
        val startFrame = frameIndex(globalStartUs, sampleRate)
        val endFrame = frameIndex(globalStartUs + durationUs, sampleRate)
        val outputFrames = endFrame - startFrame
        require(outputFrames <= MAX_OUTPUT_BYTES / frameBytesLong) { "Window exceeds PCM byte limit" }

        val frameBytes = frameBytesLong.toInt()
        val result = ByteArray((outputFrames * frameBytesLong).toInt())
        val sourceFrames = bytes.size / frameBytes
        var sourceFrame = (startFrame % sourceFrames).toInt()
        var remaining = outputFrames
        var outputByte = 0
        while (remaining > 0L) {
            val copyFrames = minOf(remaining, (sourceFrames - sourceFrame).toLong()).toInt()
            val copyBytes = copyFrames * frameBytes
            System.arraycopy(bytes, sourceFrame * frameBytes, result, outputByte, copyBytes)
            outputByte += copyBytes
            remaining -= copyFrames
            sourceFrame = 0
        }
        return result
    }

    /** floor(timeUs * sampleRate / 1,000,000), without overflowing the intermediate product. */
    private fun frameIndex(timeUs: Long, sampleRate: Int): Long {
        val wholeSeconds = timeUs / MICROS_PER_SECOND
        val fractionalFrames = (timeUs % MICROS_PER_SECOND) * sampleRate / MICROS_PER_SECOND
        require(wholeSeconds <= (Long.MAX_VALUE - fractionalFrames) / sampleRate) {
            "Absolute frame index overflows"
        }
        return wholeSeconds * sampleRate + fractionalFrames
    }

    private const val MICROS_PER_SECOND = 1_000_000L
    private const val MAX_OUTPUT_BYTES = 32L * 1024L * 1024L
}
