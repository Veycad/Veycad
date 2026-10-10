package com.veycad.app

import kotlin.math.abs

/** Chooses a complete musical phrase instead of truncating every automatic edit at a fixed time. */
object EditDurationPolicy {
    const val MINIMUM_MS = 15_000L
    const val PREFERRED_MS = 18_000L
    const val MAXIMUM_MS = 22_000L

    private const val SEARCH_BEFORE_MS = 1_500L
    private const val SEARCH_AFTER_MS = 2_500L

    data class Choice(
        val durationMs: Long,
        val reason: Reason,
        val distanceToMusicalBoundaryMs: Long?
    ) {
        enum class Reason { MUSICAL_PHRASE, PREFERRED_LENGTH, SOURCE_LIMIT }
    }

    fun choose(sourceDurationMs: Long, audio: AudioBeatMap): Choice {
        require(sourceDurationMs >= MINIMUM_MS) {
            "Automatic edit needs at least $MINIMUM_MS ms of source video"
        }

        val upperBound = minOf(sourceDurationMs, MAXIMUM_MS)
        if (upperBound < PREFERRED_MS) {
            return Choice(upperBound, Choice.Reason.SOURCE_LIMIT, null)
        }

        val analysisSamples = upperBound * audio.sampleRate / 1_000L
        val usableAudio = when {
            audio.durationSamples == 0L -> audio
            audio.durationSamples < analysisSamples -> audio.loopedTo(analysisSamples)
            else -> audio
        }
        val searchStart = maxOf(MINIMUM_MS, PREFERRED_MS - SEARCH_BEFORE_MS)
        val searchEnd = minOf(upperBound, PREFERRED_MS + SEARCH_AFTER_MS)
        val candidates = usableAudio.beats.asSequence()
            .map { beat -> beat to usableAudio.timestampUs(beat.sampleIndex) / 1_000L }
            .filter { (_, timeMs) -> timeMs in searchStart..searchEnd }
            .map { (beat, timeMs) ->
                val onsetStrength = usableAudio.onsets
                    .asSequence()
                    .map { onset -> onset to usableAudio.timestampUs(onset.sampleIndex) / 1_000L }
                    .filter { (_, onsetMs) -> abs(onsetMs - timeMs) <= 120L }
                    .maxOfOrNull { (onset, _) -> onset.strength } ?: 0f
                val phraseScore =
                    (if (beat.isDownbeat) 1.15f else 0f) +
                    beat.strength * .65f +
                    onsetStrength * .35f +
                    (if (beat.dominantBand == AudioBeatMap.FrequencyBand.LOW) .2f else 0f) -
                    abs(timeMs - PREFERRED_MS).toFloat() / 4_000f
                Triple(timeMs, phraseScore, abs(timeMs - PREFERRED_MS))
            }
            .toList()

        val best = candidates.maxWithOrNull(
            compareBy<Triple<Long, Float, Long>> { it.second }
                .thenBy { -it.third }
        )
        return if (best != null && best.second >= .7f) {
            Choice(best.first, Choice.Reason.MUSICAL_PHRASE, best.third)
        } else {
            Choice(PREFERRED_MS, Choice.Reason.PREFERRED_LENGTH, null)
        }
    }
}
