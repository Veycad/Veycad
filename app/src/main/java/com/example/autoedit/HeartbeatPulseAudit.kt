package com.example.autoedit

/** Narrow decoded evidence; passing this audit does not accept an edit or its musical rhythm. */
internal object HeartbeatPulseAudit {
    const val TAIL_METHOD = "decoded-luma-contiguous-60fps-v1"
    const val TAIL_BOUNDARY_TOLERANCE_US = 16_667L
    private const val DECODED_PTS_ROUNDING_US = 1L
    private const val MAXIMUM_BLACK_LUMA = .05f
    private const val MINIMUM_WHITE_LUMA = .95f
    private val firstTailFrame = (HeartbeatMontageProfile.LAST_VISUAL_END_US *
        HeartbeatMontageProfile.REFERENCE_FPS / 1_000_000L).toInt()
    private val outputFrameCount = (HeartbeatMontageProfile.OUTPUT_DURATION_US *
        HeartbeatMontageProfile.REFERENCE_FPS / 1_000_000L).toInt()
    val expectedTailFrames: Int get() = outputFrameCount - firstTailFrame

    /** Rounded 60fps presentation clock, matching HighQualityFramePlan, not rounded millisecond
     * overlays. The 21.166s graph endpoint is exclusive: last index is1269, never1270. */
    internal fun tailSamplingTargetsUs(): List<Long> =
        (firstTailFrame - 2 until outputFrameCount).map { frame ->
            (frame * 1_000_000L + HeartbeatMontageProfile.REFERENCE_FPS / 2L) /
                HeartbeatMontageProfile.REFERENCE_FPS
        }

    data class TailReport(
        val expectedFrames: Int,
        val measuredFrames: Int,
        val blackFrames: Int,
        val boundaryMeasuredFrames: Int,
        val firstBlackUs: Long?,
        val startOffsetUs: Long?,
        val missingTimesUs: List<Long>,
        val brightTimesUs: List<Long>,
        val unexpectedTimesUs: List<Long>,
        val preBoundaryLuma: Float?
    ) {
        val matched: Boolean get() = missingTimesUs.isEmpty() && brightTimesUs.isEmpty() &&
            unexpectedTimesUs.isEmpty() &&
            measuredFrames == expectedFrames && boundaryMeasuredFrames == 2 &&
            blackFrames in expectedFrames - 1..expectedFrames &&
            preBoundaryLuma != null && preBoundaryLuma >= MINIMUM_WHITE_LUMA &&
            startOffsetUs != null && kotlin.math.abs(startOffsetUs) <= TAIL_BOUNDARY_TOLERANCE_US +
                DECODED_PTS_ROUNDING_US
    }

    data class Report(val measured: Int, val matched: Int, val missing: List<Int>,
        val wrongLuma: List<Int>, val tail: TailReport)
    fun evaluate(samples: List<RenderedMp4Acceptance.VisualSample>): Report {
        val missing = mutableListOf<Int>()
        val wrong = mutableListOf<Int>()
        var matched = 0
        HeartbeatMontageProfile.pulses.forEachIndexed { index,pulse ->
            // MediaCodec may floor fractional microsecond timestamps by one microsecond.
            val inside = samples.filter { it.outputTimeUs >= pulse.startUs-1 && it.outputTimeUs < pulse.endUs-1 }
            if (inside.isEmpty()) missing += index
            else if (inside.all { sample -> when(pulse.kind) {
                HeartbeatMontageProfile.PulseKind.WHITE -> sample.luma >= .95f
                HeartbeatMontageProfile.PulseKind.DARK -> sample.luma <= .05f
            } }) matched++ else wrong += index
        }
        return Report(HeartbeatMontageProfile.pulses.size-missing.size,matched,missing,wrong,
            evaluateTail(samples))
    }

    /** Every tail frame must have its own decoded PTS. One late image cannot satisfy multiple
     * clock slots; the ±1us match admits codec timestamp flooring, not one-frame missing coverage.
     * Tail onset permits ±one60fps frame; the independently required terminal white pulse can
     * impose a stricter early-start bound. Every decoded image from the first boundary slot's
     * rounding allowance through EOS participates; extra/off-clock or duplicate slot witnesses,
     * later bright holes and missing final images are illegal. */
    internal fun evaluateTail(samples: List<RenderedMp4Acceptance.VisualSample>): TailReport {
        val targets = tailSamplingTargetsUs()
        val evidence = samples.filter { it.outputTimeUs >= targets.first() - DECODED_PTS_ROUNDING_US }
            .sortedBy { it.outputTimeUs }
        val slotWitnesses = targets.map { target -> evidence.filter {
            kotlin.math.abs(it.outputTimeUs - target) <= DECODED_PTS_ROUNDING_US
        } }
        val aligned = slotWitnesses.map { it.singleOrNull() }
        val unexpected = evidence.filter { sample ->
            val slot = targets.indexOfFirst {
                kotlin.math.abs(sample.outputTimeUs - it) <= DECODED_PTS_ROUNDING_US
            }
            slot < 0 || slotWitnesses[slot].size != 1
        }
        val tail = aligned.drop(2)
        val firstBlack = evidence.firstOrNull { it.luma <= MAXIMUM_BLACK_LUMA }
        val mandatoryBlackStartUs = minOf(firstBlack?.outputTimeUs ?: Long.MAX_VALUE,
            HeartbeatMontageProfile.LAST_VISUAL_END_US + TAIL_BOUNDARY_TOLERANCE_US)
        return TailReport(expectedTailFrames, tail.count { it != null },
            tail.count { it != null && it.luma <= MAXIMUM_BLACK_LUMA },
            aligned.take(2).count { it != null }, firstBlack?.outputTimeUs,
            firstBlack?.outputTimeUs?.minus(HeartbeatMontageProfile.LAST_VISUAL_END_US),
            targets.filterIndexed { index, _ -> aligned[index] == null },
            evidence.mapNotNull { sample -> sample.outputTimeUs.takeIf {
                it >= mandatoryBlackStartUs - DECODED_PTS_ROUNDING_US && sample.luma > MAXIMUM_BLACK_LUMA
            } }, unexpected.map { it.outputTimeUs }, aligned.firstOrNull()?.luma)
    }
}
