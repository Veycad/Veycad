package com.veycad.app

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class CustomAudioTest {
    @Test fun loops_only_the_selected_tail_without_returning_to_the_intro() {
        assertEquals(listOf(
            AudioExportPlan.Segment(1_000_000, 3_000_000, 0),
            AudioExportPlan.Segment(1_000_000, 3_000_000, 2_000_000),
            AudioExportPlan.Segment(1_000_000, 2_000_000, 4_000_000)
        ), AudioExportPlan.loop(3_000_000, 5_000_000, sourceStartUs = 1_000_000))
    }

    @Test fun decoder_window_removes_seek_preroll_and_clips_the_last_buffer_on_sample_boundaries() {
        assertEquals(480 until 1_000,
            AudioPcmWindow.frames(1_940_000, 1_000, 48_000, 1_950_000, 20_000))
        assertEquals(0 until 480,
            AudioPcmWindow.frames(1_960_000, 1_000, 48_000, 1_950_000, 20_000))
        assertTrue(AudioPcmWindow.frames(1_900_000, 1_000, 48_000, 1_950_000, 20_000).isEmpty())
        // A request between sample boundaries starts at the next complete PCM frame.
        assertEquals(1 until 4, AudioPcmWindow.frames(0, 4, 44_100, 1, 100))
    }

    @Test fun detects_a_sustained_bass_drop_instead_of_labeling_every_kick_a_drop() {
        val rate = 8_000
        val pcm = FloatArray(rate * 12)
        for (beat in 0 until 24) {
            val start = rate / 4 + beat * rate / 2
            val amplitude = if (beat < 12) .08f else .9f
            for (i in 0 until rate / 5) {
                val envelope = 1.0 - i.toDouble() / (rate / 5)
                pcm[start + i] = (sin(2 * PI * 100 * i / rate) * amplitude * envelope).toFloat()
            }
        }
        val map = AudioBeatMapAnalyzer.analyze(pcm, rate)
        assertEquals(120f, requireNotNull(map.estimatedTempoBpm), 3f)
        assertTrue("drops=${map.drops}", map.drops.any {
            map.timestampUs(it.sampleIndex) in 6_000_000..6_600_000
        })
        assertTrue("Each ordinary kick is not a drop", map.drops.size <= 2)
        val repeated = map.loopedTo(rate * 24L)
        assertTrue(repeated.drops.any { it.sampleIndex >= rate * 18L })
    }

    @Test fun silence_has_no_drop_candidates() {
        assertTrue(AudioBeatMapAnalyzer.analyze(FloatArray(24_000), 8_000).drops.isEmpty())
    }

    @Test fun a_short_tail_gets_rhythm_from_the_same_repetition_used_by_export() {
        val rate = 8_000
        val pcm = FloatArray(rate)
        for (start in listOf(2_000, 6_000)) {
            repeat(800) { offset ->
                pcm[start + offset] = (sin(2 * PI * 100 * offset / rate) * (1 - offset / 800.0) * .8).toFloat()
            }
        }
        val repeated = AudioBeatMapAnalyzer.analyzeLoopingFragment(pcm, rate)
        assertEquals(120f, requireNotNull(repeated.estimatedTempoBpm), 3f)
        assertEquals(0L, repeated.durationSamples % rate)
        assertTrue(repeated.beats.size >= 14)
        assertTrue("beats=${repeated.beats}", repeated.beats.all { beat ->
            val phase = beat.sampleIndex % rate
            // 50 ms allows the 32 ms spectral hop without accepting decay-tail peaks.
            kotlin.math.abs(phase - 2_000) <= 400 || kotlin.math.abs(phase - 6_000) <= 400
        })
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannot_start_after_the_end_of_the_track() {
        AudioExportPlan.loop(3_000_000, 5_000_000, sourceStartUs = 3_000_000)
    }
}
