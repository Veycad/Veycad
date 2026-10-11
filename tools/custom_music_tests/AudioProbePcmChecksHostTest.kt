package com.veycad.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Test

/** Meaningful host-only guard/byte-conversion checks; Android codecs are never instantiated. */
class AudioProbePcmChecksHostTest {
    @Test fun pcm_guards_hashes_and_complete_float_coverage() { runAudioProbePcmChecks() }
}

fun main() {
    val checked = runAudioProbePcmChecks()
    println("PASS $checked PCM alignment/bounds/raw-f32/converter/streaming/witness/duration checks")
}

private fun runAudioProbePcmChecks(): Int {
    var checked = 0
    fun verify(value: Boolean) { check(value); checked++ }
    fun rejected(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { checked++; return }
        catch (_: IllegalStateException) { checked++; return }
        error("Invalid PCM input accepted")
    }
    verify(AudioProbePcmChecks.frameCount(8, 4, 2) == 1)
    verify(AudioProbePcmChecks.frameCount(4, 2, 1) == 2)
    verify(AudioProbePcmChecks.frameCount(0, 3, 2) == 0)
    rejected { AudioProbePcmChecks.frameCount(3, 2, 1) }
    rejected { AudioProbePcmChecks.frameCount(3, 3, 2) }
    rejected { AudioProbePcmChecks.frameCount(-1, 4, 1) }
    rejected { AudioProbePcmChecks.frameCount(4, 4, 0) }
    rejected { AudioProbePcmChecks.frameCount(4, 4, 9) }
    rejected { AudioProbePcmChecks.frameCount(4, 5, 1) }
    val limit = 64L * 1024 * 1024
    verify(AudioProbePcmChecks.checkedTotal(limit - 1, 1) == limit)
    verify(AudioProbePcmChecks.checkedTotal(limit, 0) == limit)
    rejected { AudioProbePcmChecks.checkedTotal(limit, 1) }
    rejected { AudioProbePcmChecks.checkedTotal(Long.MAX_VALUE, 1) }
    rejected { AudioProbePcmChecks.checkedTotal(0, -1) }
    rejected { AudioProbePcmChecks.checkedTotal(-1, 0) }
    // Exact little-endian bytes: 1.2f, -1.3f, -0.0f, a noncanonical quiet NaN payload.
    val raw = byteArrayOf(0x9a.toByte(), 0x99.toByte(), 0x99.toByte(), 0x3f,
        0x66, 0x66, 0xa6.toByte(), 0xbf.toByte(), 0, 0, 0, 0x80.toByte(), 0x45, 0x23, 0xc1.toByte(), 0x7f)
    val floats = PcmSampleConverter.decode(raw, 4, clampFloatSamples = false)
    verify(floats[0] > 1f && floats[1] < -1f)
    verify(java.lang.Float.floatToRawIntBits(floats[2]) == Int.MIN_VALUE)
    verify(java.lang.Float.floatToRawIntBits(floats[3]) == 0x7fc12345)
    fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    verify(AudioProbePcmChecks.sampleHash(floats) == hex(MessageDigest.getInstance("SHA-256").digest(raw)))
    val integers = PcmSampleConverter.decode(byteArrayOf(0, 0x80.toByte(), 0xff.toByte(), 0x7f), 2, false)
    verify(integers.contentEquals(floatArrayOf(-1f, 32767f / 32768f)))
    // Both digest buffering (>8192 bytes) and decoder-chunk boundaries must preserve raw bits.
    val many = FloatArray(3001) { java.lang.Float.intBitsToFloat(0x3f000000 + it) }
    val expectedBytes = ByteBuffer.allocate(many.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        .also { b -> many.forEach(b::putFloat) }.array()
    val expected = hex(MessageDigest.getInstance("SHA-256").digest(expectedBytes))
    verify(AudioProbePcmChecks.sampleHash(many) == expected)
    val streamed = MessageDigest.getInstance("SHA-256")
    AudioProbePcmChecks.updateSampleDigest(streamed, many.copyOfRange(0, 17))
    AudioProbePcmChecks.updateSampleDigest(streamed, many.copyOfRange(17, 2080))
    AudioProbePcmChecks.updateSampleDigest(streamed, many.copyOfRange(2080, many.size))
    verify(hex(streamed.digest()) == expected)
    val duration = 16_000_000L
    val positive = AudioProbePcmChecks.WitnessState().apply { repeat(250) { observe(4, 16000, 1, 4, 1024) } }
    verify(positive.floatComplete(true, duration))
    verify(!positive.floatComplete(false, duration))
    val empty = AudioProbePcmChecks.WitnessState().apply { observe(4, 16000, 1, 4, 0) }
    verify(!empty.floatComplete(true, duration))
    val fallback = AudioProbePcmChecks.WitnessState().apply { observe(2, 16000, 1, null, 1024) }
    verify(!fallback.floatComplete(true, duration) && fallback.encodingConstant && fallback.rateConstant && fallback.channelsConstant)
    val mixed = AudioProbePcmChecks.WitnessState().apply {
        observe(4, 16000, 1, 4, 1024); observe(2, 16000, 1, null, 0)
    }
    verify(!mixed.encodingConstant && !mixed.floatComplete(true, duration))
    val rateChange = AudioProbePcmChecks.WitnessState().apply {
        observe(4, 16000, 1, 4, 1024); observe(4, 48000, 1, 4, 1024)
    }
    verify(!rateChange.rateConstant && !rateChange.floatComplete(true, duration))
    val channelChange = AudioProbePcmChecks.WitnessState().apply {
        observe(4, 16000, 1, 4, 1024); observe(4, 16000, 2, 4, 1024)
    }
    verify(!channelChange.channelsConstant && !channelChange.floatComplete(true, duration))
    rejected { AudioProbePcmChecks.WitnessState().observe(2, 16000, 1, 4, 1024) }
    rejected { AudioProbePcmChecks.WitnessState().observe(4, 0, 1, 4, 1024) }
    rejected { AudioProbePcmChecks.WitnessState().observe(4, 16000, 1, 4, -1) }
    val tolerance = 33_334L + 1_024_000_000L / 16000
    verify(AudioProbePcmChecks.durationWithinTolerance(duration - tolerance, 16000))
    verify(AudioProbePcmChecks.durationWithinTolerance(duration + tolerance, 16000))
    verify(!AudioProbePcmChecks.durationWithinTolerance(duration - tolerance - 1, 16000))
    verify(!AudioProbePcmChecks.durationWithinTolerance(duration + tolerance + 1, 16000))
    verify(positive.floatComplete(true, duration + tolerance))
    verify(!positive.floatComplete(true, duration + tolerance + 1))
    rejected { AudioProbePcmChecks.durationWithinTolerance(-1, 16000) }
    rejected { AudioProbePcmChecks.durationWithinTolerance(duration, 0) }
    // Equal short production/trace counts plus EOS and explicit float must not prove full coverage.
    val productionShortFrames = 1024L
    val traceShortFrames = 1024L
    val short = AudioProbePcmChecks.WitnessState().apply { observe(4, 16000, 1, 4, traceShortFrames.toInt()) }
    val shortDuration = traceShortFrames * 1_000_000L / 16000
    verify(productionShortFrames == traceShortFrames && short.hasFloatFormatEvidence(true))
    verify(!AudioProbePcmChecks.durationWithinTolerance(shortDuration, 16000) && !short.floatComplete(true, shortDuration))
    return checked
}
