package com.example.autoedit

/** Decoded-frame audit for the two shutter blocks and the terminal white/black phrase. */
internal object FearStrobeAudit {
    data class Report(
        val shutterFramesMeasured: Int,
        val shutterFramesMatched: Int,
        val finaleMatched: Boolean,
        val missingPulseIndices: List<Int>,
        val wrongLumaIndices: List<Int>,
        val unexpectedBlackFramesUs: List<Long>
    )

    fun evaluate(samples: List<RenderedMp4Acceptance.VisualSample>): Report {
        val missing = mutableListOf<Int>()
        val wrong = mutableListOf<Int>()
        var shutterMeasured = 0
        var shutterMatched = 0
        FearStrobeProfile.pulses.forEachIndexed { index, pulse ->
            val inside = samples.filter { it.outputTimeUs >= pulse.startUs - 1L &&
                it.outputTimeUs < pulse.endUs - 1L }
            if (inside.isEmpty()) {
                missing += index
            } else {
                if (pulse.shutterFrame) shutterMeasured++
                val lumaMatches = inside.all { sample ->
                    when (pulse.kind) {
                        FearStrobeProfile.PulseKind.BLACK -> sample.luma <= .05f
                        FearStrobeProfile.PulseKind.WHITE -> sample.luma >= .95f
                    }
                }
                if (lumaMatches) {
                    if (pulse.shutterFrame) shutterMatched++
                } else wrong += index
            }
        }
        // The three finale pulse intervals encode WW/B/WW and are sampled at both edges.
        val finaleStart = FearStrobeProfile.pulses.size - 4
        val finaleIndices = finaleStart until finaleStart + 3
        val finaleMatched = finaleIndices.none { it in missing || it in wrong }
        val unexpected = samples.filter { sample ->
            sample.luma <= .03f && FearStrobeProfile.pulses.none { pulse ->
                pulse.kind == FearStrobeProfile.PulseKind.BLACK &&
                    sample.outputTimeUs >= pulse.startUs - 1L && sample.outputTimeUs < pulse.endUs - 1L
            }
        }.map { it.outputTimeUs }
        return Report(shutterMeasured, shutterMatched, finaleMatched, missing, wrong, unexpected)
    }
}
