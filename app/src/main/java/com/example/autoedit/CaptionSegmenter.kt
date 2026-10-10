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
        val ordered=mutableListOf<CaptionCue>()
        for(cue in result.sortedBy { it.startUs }) {
            val previous=ordered.lastOrNull()
            if(previous==null || previous.endUs<=cue.startUs) { ordered.add(cue); continue }
            val left=previous.text.trim().split(Regex("\\s+"))
            val right=cue.text.trim().split(Regex("\\s+"))
            fun word(s:String)=normalized(s).replace(Regex("[\\p{P}]"),"")
            val overlap=(1..minOf(left.size,right.size)).lastOrNull { n ->
                left.takeLast(n).map(::word)==right.take(n).map(::word)
            } ?: 0
            val remaining=right.drop(overlap).joinToString(" ")
            if(overlap>0 && remaining.isBlank()) {
                ordered[ordered.lastIndex]=previous.copy(endUs=maxOf(previous.endUs,cue.endUs))
            } else if(overlap>0 && previous.endUs<cue.endUs) {
                ordered.add(cue.copy(text=remaining,startUs=previous.endUs))
            } else {
                // Differently split windows may disagree on boundaries. Keep both phrases,
                // sharing a boundary so two subtitle blocks never stack over one another.
                val boundary=((previous.endUs+cue.startUs)/2).coerceAtMost(cue.endUs-1)
                if(boundary>previous.startUs) {
                    ordered[ordered.lastIndex]=previous.copy(endUs=boundary)
                    ordered.add(cue.copy(text=remaining,startUs=boundary))
                }
            }
        }
        return ordered
    }
}
