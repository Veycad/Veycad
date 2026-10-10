package com.veycad.app

internal object GalleryMontageDirector {
    fun direct(sources: MediaSourceSet, moments: List<GalleryMoment>, audioMap: AudioBeatMap,
        requestedDurationMs: Long): MontageGraph {
        require(requestedDurationMs in listOf(15_000L, 30_000L, 60_000L))
        GalleryImportPolicy.validate(sources)
        // A beat-trimmed window consumes less output than its source window. Keep looking
        // through fresh footage until the actual graph is full, rather than padding it.
        val selected = GalleryMomentSelector.selections(moments, sources, sources.items.sumOf { it.durationUs })
        val sourceIndices = sources.items.mapIndexed { index, source -> source.id to index }.toMap()
        // Convert samples without overflowing an intermediate sampleIndex * 1_000_000.
        val beatsMs = audioMap.beats.mapNotNull { beat ->
            val seconds = beat.sampleIndex / audioMap.sampleRate
            if (seconds > requestedDurationMs / 1_000L) null else
                seconds * 1_000L + (beat.sampleIndex % audioMap.sampleRate) * 1_000L / audioMap.sampleRate
        }.distinct().sorted()
        val clips = mutableListOf<MontageGraph.Clip>()
        var cursorMs = 0L
        for (moment in selected) {
            if (requestedDurationMs - cursorMs < 500L) break
            // Existing graph adapter only: inward rounding preserves raw source origin and EOF.
            val startMs = moment.startUs / 1_000L + if (moment.startUs % 1_000L == 0L) 0L else 1L
            val endMs = moment.endUs / 1_000L
            val availableMs = minOf(endMs - startMs, requestedDurationMs - cursorMs)
            if (availableMs < 500L) continue
            // Never sacrifice most of a short file just to find an earlier musical accent.
            val reachesTarget = availableMs == requestedDurationMs - cursorMs
            val beatEndMs = if (reachesTarget) null else beatsMs.lastOrNull {
                it > cursorMs && it <= cursorMs + availableMs &&
                    it - cursorMs >= maxOf(500L, availableMs * 3L / 4L) }
            val durationMs = beatEndMs?.minus(cursorMs) ?: availableMs
            clips += MontageGraph.Clip("gallery-${clips.size}", startMs, startMs + durationMs,
                durationMs, if (clips.isEmpty()) MontageGraph.ShotRole.OPENING else MontageGraph.ShotRole.ACTION,
                MontageGraph.Transition.HARD_CUT, MontageGraph.Motion.HOLD,
                GalleryMomentSelector.score(moment), cursorMs, sourceIndex = sourceIndices.getValue(moment.sourceId))
            cursorMs += durationMs
        }
        if (cursorMs < 1_000L) throw MaterialRejectedException("gallery_insufficient_material",
            "Недостаточно пригодного материала: ролик должен длиться хотя бы секунду")
        return MontageGraph(sourceDurationMs = sources.items.maxOf { it.videoEndPtsUs / 1_000L },
            outputDurationMs = cursorMs, clips = clips.toList(),
            audioTrack = MontageGraph.AudioTrack("neon_drift"))
    }
}
