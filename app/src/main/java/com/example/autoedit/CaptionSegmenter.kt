package com.veycad.app

import java.util.Locale

object CaptionSegmenter {
    fun merge(existing: List<CaptionCue>, incoming: List<CaptionCue>, durationUs: Long): List<CaptionCue> {
        fun normalized(text: String) = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        val result = existing.toMutableList()
        for (cue in incoming) {
            if (cue.startUs >= durationUs || cue.text.isBlank()) continue
            val clipped = cue.copy(text = cue.text.trim(), endUs = minOf(cue.endUs, durationUs))
            if (result.none { normalized(it.text) == normalized(clipped.text) &&
                    it.startUs < clipped.endUs && clipped.startUs < it.endUs }) result.add(clipped)
        }
        return result.sortedBy { it.startUs }
    }
}
