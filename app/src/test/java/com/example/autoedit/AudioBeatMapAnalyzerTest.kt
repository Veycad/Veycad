package com.veycad.app

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBeatMapAnalyzerTest {
    private val sampleRate = 8_000
    private val config = AudioBeatMapAnalyzer.Config(
        frameSize = 256,
        hopSize = 64,
        thresholdWindowFrames = 12,
        thresholdMultiplier = 1.15f
    )

    @Test fun detects_120_bpm_low_band_beats_and_accented_downbeats() {
        val pcm = pulseTrack(
            durationSeconds = 8,
            periodSamples = sampleRate / 2L,
            frequencyHz = 110f,
            accentEvery = 4
        )

        val map = AudioBeatMapAnalyzer.analyze(pcm, sampleRate, config)

        assertNotNull(map.estimatedTempoBpm)
        assertEquals(120f, map.estimatedTempoBpm!!, 3f)
        assertTrue("onsets=${map.onsets.size}", map.onsets.size >= 13)
        assertTrue("beats=${map.beats.size}", map.beats.size >= 14)
        assertTrue(map.onsets.count { it.dominantBand == AudioBeatMap.FrequencyBand.LOW } >= 10)
        val downbeats = map.beats.filter { it.isDownbeat }
        assertTrue(downbeats.size >= 3)
        assertTrue(downbeats.zipWithNext().all { (left, right) ->
            abs((right.sampleIndex - left.sampleIndex) - sampleRate * 2L) <= config.hopSize * 3L
        })
    }

    @Test fun follows_a_different_90_bpm_structure_without_millisecond_clock_drift() {
        val period = (sampleRate * 60.0 / 90.0).toLong()
        val pcm = pulseTrack(12, period, 880f, accentEvery = 3)

        val map = AudioBeatMapAnalyzer.analyze(pcm, sampleRate, config)

        assertEquals(90f, map.estimatedTempoBpm!!, 2.5f)
        assertTrue(map.beats.size >= 16)
        map.beats.forEach { beat ->
            assertEquals(beat.sampleIndex * 1_000_000L / sampleRate, map.timestampUs(beat.sampleIndex))
        }
    }

    @Test fun classifies_high_frequency_onsets_separately_from_low_frequency_hits() {
        val pcm = FloatArray(sampleRate * 4)
        addBurst(pcm, sampleRate / 2, 100f, .9f)
        addBurst(pcm, sampleRate, 3_200f, .9f)
        addBurst(pcm, sampleRate * 3 / 2, 100f, .9f)
        addBurst(pcm, sampleRate * 2, 3_200f, .9f)
        addBurst(pcm, sampleRate * 5 / 2, 100f, .9f)

        val map = AudioBeatMapAnalyzer.analyze(pcm, sampleRate, config)

        assertTrue(map.onsets.any { it.dominantBand == AudioBeatMap.FrequencyBand.LOW })
        assertTrue(map.onsets.any { it.dominantBand == AudioBeatMap.FrequencyBand.HIGH })
        assertTrue(map.onsets.all { it.sampleIndex in pcm.indices })
    }

    @Test fun silence_has_no_invented_beats_or_onsets() {
        val map = AudioBeatMapAnalyzer.analyze(FloatArray(sampleRate * 2), sampleRate, config)

        assertTrue(map.onsets.isEmpty())
        assertTrue(map.beats.isEmpty())
        assertEquals(null, map.estimatedTempoBpm)
    }

    @Test fun beat_map_loop_uses_exact_sample_offsets() {
        val original = AudioBeatMap(
            48_000, 48_000L, 120f,
            listOf(AudioBeatMap.Beat(12_000L, 1f, AudioBeatMap.FrequencyBand.LOW, true)),
            listOf(AudioBeatMap.Onset(12_000L, 1f, AudioBeatMap.FrequencyBand.LOW))
        )

        val looped = original.loopedTo(120_000L)

        assertEquals(listOf(12_000L, 60_000L, 108_000L), looped.beats.map { it.sampleIndex })
        assertEquals(listOf(12_000L, 60_000L, 108_000L), looped.onsets.map { it.sampleIndex })
        assertEquals(listOf(12_000L, 60_000L), original.loopedTo(108_000L).beats.map { it.sampleIndex })
        assertEquals(48_000L, original.durationSamples)
        assertEquals(120_000L, looped.durationSamples)
    }

    @Test fun pcm16_conversion_is_little_endian_and_clamped() {
        val source = floatArrayOf(-1.2f, -.5f, 0f, .5f, 1.2f)
        val encoded = PcmSampleConverter.encode16(source)
        val decoded = PcmSampleConverter.decode(encoded, AudioFormat.ENCODING_PCM_16BIT)

        assertEquals(source.size, decoded.size)
        assertEquals(-1f, decoded[0], .001f)
        assertEquals(-.5f, decoded[1], .001f)
        assertEquals(0f, decoded[2], 0f)
        assertEquals(.5f, decoded[3], .001f)
        assertEquals(1f, decoded[4], .001f)
        val encodedSamples = ShortArray(5)
        ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(encodedSamples)
        assertArrayEquals(shortArrayOf(-32767, -16383, 0, 16383, 32767), encodedSamples)
        // A round trip alone cannot expose matching endian/scale bugs in both converters.
        assertArrayEquals(floatArrayOf(-1f, 0f, .5f),
            PcmSampleConverter.decode(byteArrayOf(0, -128, 0, 0, 0, 64),
                AudioFormat.ENCODING_PCM_16BIT), 0f)
    }

    private fun pulseTrack(
        durationSeconds: Int,
        periodSamples: Long,
        frequencyHz: Float,
        accentEvery: Int
    ): FloatArray {
        val pcm = FloatArray(sampleRate * durationSeconds)
        var sample = sampleRate / 4L
        var beat = 0
        while (sample < pcm.size) {
            addBurst(pcm, sample.toInt(), frequencyHz, if (beat % accentEvery == 0) 1f else .55f)
            sample += periodSamples
            beat++
        }
        return pcm
    }

    private fun addBurst(pcm: FloatArray, startSample: Int, frequencyHz: Float, amplitude: Float) {
        val length = sampleRate / 25
        repeat(length) { offset ->
            val index = startSample + offset
            if (index >= pcm.size) return
            val envelope = 1f - offset.toFloat() / length
            pcm[index] += (amplitude * envelope * sin(2.0 * PI * frequencyHz * offset / sampleRate)).toFloat()
        }
    }
}
