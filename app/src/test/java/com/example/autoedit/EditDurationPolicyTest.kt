package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class EditDurationPolicyTest {
    private val rate = 1_000

    @Test fun rejects_source_shorter_than_fifteen_seconds() {
        try {
            EditDurationPolicy.choose(14_999L, map(beatsMs = emptyList()))
            fail("Expected minimum-duration rejection")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test fun keeps_shortest_valid_source_instead_of_inventing_video() {
        val choice = EditDurationPolicy.choose(16_200L, map(beatsMs = listOf(16_000L)))

        assertEquals(16_200L, choice.durationMs)
        assertEquals(EditDurationPolicy.Choice.Reason.SOURCE_LIMIT, choice.reason)
    }

    @Test fun ends_on_strong_downbeat_near_eighteen_seconds() {
        val audio = map(
            beatsMs = listOf(17_500L, 18_250L, 19_000L),
            downbeatMs = 18_250L,
            strongestMs = 18_250L
        )

        val choice = EditDurationPolicy.choose(25_000L, audio)

        assertEquals(18_250L, choice.durationMs)
        assertEquals(EditDurationPolicy.Choice.Reason.MUSICAL_PHRASE, choice.reason)
        assertEquals(250L, choice.distanceToMusicalBoundaryMs)
    }

    @Test fun musical_phrase_can_extend_edit_but_never_beyond_twenty_two_seconds() {
        val audio = map(
            beatsMs = listOf(18_500L, 20_250L, 22_500L),
            downbeatMs = 20_250L,
            strongestMs = 20_250L
        )

        val choice = EditDurationPolicy.choose(40_000L, audio)

        assertEquals(20_250L, choice.durationMs)
        assertEquals(EditDurationPolicy.Choice.Reason.MUSICAL_PHRASE, choice.reason)
    }

    @Test fun falls_back_to_eighteen_seconds_when_music_has_no_reliable_boundary() {
        val choice = EditDurationPolicy.choose(30_000L, map(beatsMs = emptyList()))

        assertEquals(18_000L, choice.durationMs)
        assertEquals(EditDurationPolicy.Choice.Reason.PREFERRED_LENGTH, choice.reason)
    }

    private fun map(
        beatsMs: List<Long>,
        downbeatMs: Long? = null,
        strongestMs: Long? = null
    ): AudioBeatMap {
        val beats = beatsMs.map { timeMs ->
            AudioBeatMap.Beat(
                sampleIndex = timeMs,
                strength = if (timeMs == strongestMs) 1f else .35f,
                dominantBand = AudioBeatMap.FrequencyBand.LOW,
                isDownbeat = timeMs == downbeatMs
            )
        }
        val onsets = strongestMs?.let {
            listOf(AudioBeatMap.Onset(it, 1f, AudioBeatMap.FrequencyBand.LOW))
        }.orEmpty()
        return AudioBeatMap(rate, 60_000L, 120f, beats, onsets)
    }
}
