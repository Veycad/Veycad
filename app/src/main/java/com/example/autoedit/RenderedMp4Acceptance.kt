package com.veycad.app

import kotlin.math.abs
import kotlin.math.sqrt

/** Pure quality gate fed by decoded samples from the locally rendered MP4. */
object RenderedMp4Acceptance {
    enum class DecodedEffect { DOUBLE_EXPOSURE, MIRROR_SLICE, GLITCH }
    enum class MaskEvidence { NOT_REQUESTED, MEASURED, SOURCE_MASK_EMPTY, OUTPUT_MASK_EMPTY, INFERENCE_FAILED }

    data class ReferenceMontageCard(
        val minimumBeatHitRate: Float = .72f,
        val maximumRepeatedSourceRatio: Float = .02f,
        val minimumTransitionPeak: Float = .16f,
        val maximumAvDriftUs: Long = 33_334L,
        val maximumDurationErrorUs: Long = 33_334L,
        val maximumArtifactScore: Float = .18f,
        val maximumColourJump: Float = .25f,
        val maximumFaceLossRate: Float = .12f,
        val maximumEdgeLeakRatio: Float = .02f,
        val minimumMaskTemporalIou: Float = .88f,
        val maximumBlackBlockScore: Float = .08f,
        val minimumReferenceGrammarFit: Float = .55f,
        val minimumReferenceTimelineRecall: Float = .95f,
        val minimumAuthorAccentHitRate: Float = 1f,
        val maximumAuthorAccentOffsetUs: Long = 33_334L,
        val minimumDecodedGlitchPeak: Float = .007f,
        val maximumSubjectStageBackgroundLuma: Float = .09f,
        val maximumSubjectStageBackgroundLoss: Float = .35f,
        val maximumVideoStartPtsUs: Long = 0L
    )

    data class ContainerSample(
        val videoLastPtsUs: Long,
        val audioLastPtsUs: Long,
        val videoMime: String,
        val audioMime: String,
        val videoFirstPtsUs: Long = 0L,
        val audioFirstPtsUs: Long = 0L,
        val integrityIssues: List<String> = emptyList()
    ) {
        init {
            require(videoLastPtsUs >= videoFirstPtsUs && audioLastPtsUs >= audioFirstPtsUs)
            require(videoFirstPtsUs >= 0L && audioFirstPtsUs >= 0L)
        }
    }

    data class VisualSample(
        val outputTimeUs: Long,
        val transitionStrength: Float,
        val luma: Float,
        val chromaU: Float,
        val chromaV: Float,
        val artifactScore: Float,
        val faceExpected: Boolean,
        val faceConfidence: Float,
        val maskExpected: Boolean = false,
        val edgeLeakRatio: Float = 0f,
        val blackBlockScore: Float = 0f,
        val maskTemporalIou: Float? = null,
        val expectedEffect: DecodedEffect? = null,
        val effectSignatureStrength: Float = 0f,
        /** Mean decoded luma outside the source-aligned subject matte during the final stage. */
        val subjectStageBackgroundLuma: Float? = null,
        val subjectStageBackgroundLoss: Float? = null,
        /** null is legacy/test evidence; production sampler always records an explicit status. */
        val maskEvidence: MaskEvidence? = null,
        val decodedSourceIndex: Int? = null,
        val decodedClipIndex: Int? = null,
        /** null is legacy/test evidence, never production proof of a fresh face measurement. */
        val decodedFaceEvidence: DecodedFaceEvidence? = null,
        /** Actual render witness PTS; absent when the caller supplies no decoded source evidence. */
        val decodedSourceTimeUs: Long? = null
    ) {
        init {
            require(outputTimeUs >= 0L)
            require(listOf(transitionStrength, luma, chromaU, chromaV, artifactScore, faceConfidence, edgeLeakRatio, blackBlockScore, effectSignatureStrength).all { it in 0f..1f })
            require(maskTemporalIou == null || maskTemporalIou in 0f..1f)
            require(subjectStageBackgroundLuma == null || subjectStageBackgroundLuma in 0f..1f)
            require(subjectStageBackgroundLoss == null || subjectStageBackgroundLoss in 0f..1f)
        }
    }

    data class Metrics(
        val beatHitRate: Float,
        val repeatedSourceRatio: Float,
        val transitionPeak: Float,
        val avDriftUs: Long,
        val maximumArtifactScore: Float,
        val maximumColourJump: Float,
        val faceLossRate: Float,
        val maximumEdgeLeakRatio: Float = 0f,
        val minimumMaskTemporalIou: Float = 0f,
        val maximumBlackBlockScore: Float = 0f,
        val referenceGrammarFit: Float = 0f,
        val maximumColourJumpTimeUs: Long = 0L,
        val durationErrorUs: Long = 0L,
        val authorAccentHitRate: Float = 1f,
        val maximumAuthorAccentOffsetUs: Long = 0L,
        val referenceTimelineRecall: Float = 1f,
        val decodedDoubleExposurePeak: Float = 0f,
        val decodedMirrorSlicePeak: Float = 0f,
        val decodedGlitchPeak: Float = 0f,
        val maximumSubjectStageBackgroundLuma: Float = 0f,
        val maximumSubjectStageBackgroundLoss: Float = 0f,
        val videoStartPtsUs: Long = 0L,
        val audioStartPtsUs: Long = 0L
    )

    data class Report(
        val accepted: Boolean,
        val metrics: Metrics,
        val reference: ReferenceMontageCard,
        val issues: List<String>,
        val decodedAudio: DecodedAudioQuality.Report? = null
    )

    fun evaluate(
        graph: MontageGraph,
        audio: AudioBeatMap,
        container: ContainerSample,
        samples: List<VisualSample>,
        reference: ReferenceMontageCard = ReferenceMontageCard(),
        beatToleranceUs: Long = 85_000L,
        requireFaceEvidence: Boolean = false,
        decodedAudio: DecodedAudioQuality.Report? = null,
        requireDecodedAudioEvidence: Boolean = false
    ): Report {
        require(samples.zipWithNext().all { (left, right) -> right.outputTimeUs > left.outputTimeUs })
        val colourJump = maximumColourJump(graph, samples)
        val accentEvidence = authorAccentEvidence(graph, samples, reference.maximumAuthorAccentOffsetUs)
        val timeline = ReferenceMontageGrammar.timelineComparison(graph)
        fun decodedPeak(effect: DecodedEffect): Float = samples
            .filter { it.expectedEffect == effect }
            .maxOfOrNull { it.effectSignatureStrength } ?: 0f
        val metrics = Metrics(
            beatHitRate = beatHitRate(graph, audio, samples, beatToleranceUs),
            repeatedSourceRatio = repeatedSourceRatio(graph),
            transitionPeak = transitionPeak(graph, samples),
            avDriftUs = abs(container.videoLastPtsUs - container.audioLastPtsUs),
            maximumArtifactScore = samples.maxOfOrNull { it.artifactScore } ?: 1f,
            maximumColourJump = colourJump.second,
            faceLossRate = faceLossRate(samples),
            maximumEdgeLeakRatio = samples.filter { it.maskExpected }
                .maxOfOrNull { it.edgeLeakRatio } ?: 0f,
            minimumMaskTemporalIou = samples.mapNotNull { it.maskTemporalIou }.minOrNull() ?: 0f,
            maximumBlackBlockScore = samples.maxOfOrNull { it.blackBlockScore } ?: 0f,
            referenceGrammarFit = ReferenceMontageGrammar.score(graph),
            maximumColourJumpTimeUs = colourJump.first,
            durationErrorUs = abs(container.videoLastPtsUs - graph.outputDurationMs * 1_000L),
            authorAccentHitRate = accentEvidence.hitRate,
            maximumAuthorAccentOffsetUs = accentEvidence.maximumOffsetUs,
            referenceTimelineRecall = timeline.recall,
            decodedDoubleExposurePeak = decodedPeak(DecodedEffect.DOUBLE_EXPOSURE),
            decodedMirrorSlicePeak = decodedPeak(DecodedEffect.MIRROR_SLICE),
            maximumSubjectStageBackgroundLoss = samples.mapNotNull { it.subjectStageBackgroundLoss }.maxOrNull() ?: 0f,
            decodedGlitchPeak = decodedPeak(DecodedEffect.GLITCH),
            maximumSubjectStageBackgroundLuma = samples.mapNotNull {
                it.subjectStageBackgroundLuma
            }.maxOrNull() ?: 0f,
            videoStartPtsUs = container.videoFirstPtsUs,
            audioStartPtsUs = container.audioFirstPtsUs
        )
        val issues = buildList {
            if (requireDecodedAudioEvidence && decodedAudio == null) add("audio-decode-evidence-missing")
            decodedAudio?.let { addAll(it.issues) }
            addAll(container.integrityIssues.map { "container:$it" })
            if (container.videoMime != "video/avc") add("unexpected-video-codec:${container.videoMime}")
            if (container.audioMime != "audio/mp4a-latm") add("unexpected-audio-codec:${container.audioMime}")
            if (metrics.beatHitRate < reference.minimumBeatHitRate) add("beat-hit-rate")
            if (metrics.repeatedSourceRatio > reference.maximumRepeatedSourceRatio) add("repeated-source-moments")
            if (graph.clips.drop(1).any { it.transitionIn in visibleTransitions } &&
                metrics.transitionPeak < reference.minimumTransitionPeak) add("weak-rendered-transitions")
            if (metrics.avDriftUs > reference.maximumAvDriftUs) add("av-drift")
            if (metrics.durationErrorUs > reference.maximumDurationErrorUs) add("duration-mismatch")
            if (metrics.videoStartPtsUs > reference.maximumVideoStartPtsUs) add("video-not-pts-zero")
            if (metrics.audioStartPtsUs != 0L) add("audio-not-pts-zero")
            if (metrics.maximumArtifactScore > reference.maximumArtifactScore) add("transition-artifacts")
            if (metrics.maximumColourJump > reference.maximumColourJump) add("colour-jump")
            if (requireFaceEvidence && samples.none { it.faceExpected }) add("face-evidence-missing")
            if (samples.any { it.faceExpected &&
                    (requireFaceEvidence || it.decodedSourceIndex != null ||
                        it.decodedClipIndex != null || it.decodedFaceEvidence != null) &&
                    !hasFreshFaceEvidence(it) }) {
                add("face-evidence-unmeasured")
            }
            if (metrics.faceLossRate > reference.maximumFaceLossRate) add("face-loss")
            if (graph.clips.any { it.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY } &&
                samples.none { it.maskExpected }) add("foreground-mask-evidence-missing")
            if (graph.clips.any { it.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY } &&
                samples.any { it.maskEvidence != null } && samples.none {
                    it.maskEvidence == MaskEvidence.MEASURED || (it.maskEvidence == null && it.maskExpected)
                }) add("foreground-mask-measurement-missing")
            if (samples.any { it.maskEvidence == MaskEvidence.SOURCE_MASK_EMPTY })
                add("foreground-mask-source-empty")
            if (samples.any { it.maskEvidence == MaskEvidence.OUTPUT_MASK_EMPTY })
                add("foreground-mask-output-empty")
            if (samples.any { it.maskEvidence == MaskEvidence.INFERENCE_FAILED })
                add("foreground-mask-inference-failed")
            if (samples.any { it.maskExpected &&
                    (it.maskEvidence == null || it.maskEvidence == MaskEvidence.MEASURED) &&
                    it.edgeLeakRatio > reference.maximumEdgeLeakRatio }) {
                add("foreground-mask-edge-leak")
            }
            if (graph.clips.any { it.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY } &&
                samples.none { it.maskTemporalIou != null }) add("foreground-mask-temporal-evidence-missing")
            else if (samples.any { it.maskTemporalIou != null } &&
                metrics.minimumMaskTemporalIou < reference.minimumMaskTemporalIou) {
                add("foreground-mask-temporal-instability")
            }
            if (!FearStrobeProfile.appliesTo(graph) &&
                metrics.maximumBlackBlockScore > reference.maximumBlackBlockScore) add("black-block-artifact")
            if (FearStrobeProfile.appliesTo(graph)) {
                val fear = FearStrobeAudit.evaluate(samples)
                if (fear.shutterFramesMatched != 30) add("fear-shutter-frame-mismatch")
                if (!fear.finaleMatched) add("fear-finale-mismatch")
                if (fear.missingPulseIndices.isNotEmpty() || fear.wrongLumaIndices.isNotEmpty()) {
                    add("fear-pulse-mismatch:missing=${fear.missingPulseIndices.joinToString("+")}:" +
                        "wrong=${fear.wrongLumaIndices.joinToString("+")}")
                }
                if (fear.unexpectedBlackFramesUs.isNotEmpty()) {
                    add("fear-unexpected-black:${fear.unexpectedBlackFramesUs.joinToString("+")}")
                }
            }
            if (HeartbeatMontageProfile.appliesTo(graph)) {
                val heartbeat = HeartbeatPulseAudit.evaluate(samples)
                if (heartbeat.matched != HeartbeatMontageProfile.pulses.size) {
                    add("heartbeat-pulse-mismatch:missing=${heartbeat.missing.joinToString("+")}:" +
                        "wrong=${heartbeat.wrongLuma.joinToString("+")}")
                }
                if (!heartbeat.tail.matched) {
                    add("heartbeat-tail-mismatch:missing=${heartbeat.tail.missingTimesUs.joinToString("+")}:" +
                        "bright=${heartbeat.tail.brightTimesUs.joinToString("+")}:" +
                        "offset=${heartbeat.tail.startOffsetUs ?: "unknown"}")
                }
            }
            if (DualityLoopProfile.appliesTo(graph)) {
                val clips = graph.clips
                if (clips.size != DualityLoopProfile.boundariesMs.size - 1) add("duality-clip-count")
                if (setOf(0, 1) != clips.map { it.sourceIndex }.toSet()) add("duality-source-coverage")
                if ((0..1).any { source -> clips.count { it.sourceIndex == source } < 8 }) {
                    add("duality-source-balance")
                }
                if (clips.windowed(4).any { run -> run.map { it.sourceIndex }.distinct().size == 1 }) {
                    add("duality-source-run")
                }
                if (clips.map { Triple(it.sourceIndex, it.sourceStartMs, it.sourceEndMs) }
                        .distinct().size != clips.size) add("duality-duplicate-window")
            }
            // This grammar describes Sigma's source/transition language. Heartbeat intentionally
            // reprises roles; FEAR and DUALITY have different authored cut structures.
            if (ReferenceMontageProfile.appliesTo(graph) &&
                metrics.referenceGrammarFit < reference.minimumReferenceGrammarFit) {
                add("reference-grammar-fit")
            }
            if (ReferenceMontageProfile.appliesTo(graph) &&
                metrics.authorAccentHitRate < reference.minimumAuthorAccentHitRate) {
                add("author-accent-miss:${accentEvidence.missedUs.joinToString("+")}")
            }
            if (ReferenceMontageProfile.appliesTo(graph) &&
                metrics.referenceTimelineRecall < reference.minimumReferenceTimelineRecall) {
                add("reference-timeline-recall")
            }
            // Gradient repetition is a texture diagnostic, not evidence of a second image.
            // Keep reporting it as a diagnostic; Sigma uses a single live contour source.
            // Sigma's legacy MIRROR_SLICE slot is now a contour echo, not a mirrored seam.
            // Its old seam signature is diagnostic only, like the texture signature above.
            if (ReferenceMontageProfile.appliesTo(graph) &&
                metrics.decodedGlitchPeak < reference.minimumDecodedGlitchPeak) {
                add("decoded-glitch-missing")
            }
            if (graph.overlays.any { it.overlayKind == MontageGraph.OverlayKind.SUBJECT_STAGE }) {
                if (ReferenceMontageProfile.appliesTo(graph)) {
                    if (samples.none { it.subjectStageBackgroundLoss != null })
                        add("subject-stage-background-evidence-missing")
                    else if (metrics.maximumSubjectStageBackgroundLoss > reference.maximumSubjectStageBackgroundLoss)
                        add("subject-stage-background-erased")
                } else {
                    val stageEvidence = samples.mapNotNull { it.subjectStageBackgroundLuma }
                    if (stageEvidence.isEmpty()) add("subject-stage-background-evidence-missing")
                    else if (metrics.maximumSubjectStageBackgroundLuma > reference.maximumSubjectStageBackgroundLuma)
                        add("subject-stage-background-not-isolated")
                }
            }
            if (ReferenceMontageProfile.appliesTo(graph)) {
                val missingRequiredEffects = timeline.missing.filter {
                    it.startsWith("opening:") || it.startsWith("layer:") || it.startsWith("effect:")
                }
                if (missingRequiredEffects.isNotEmpty()) {
                    add("reference-required-event-missing:${missingRequiredEffects.joinToString("+")}")
                }
            }
        }
        return Report(issues.isEmpty(), metrics, reference, issues, decodedAudio)
    }

    internal fun beatHitRate(
        graph: MontageGraph,
        audio: AudioBeatMap,
        samples: List<VisualSample>,
        toleranceUs: Long
    ): Float {
        val boundaries = graph.clips.dropLast(1).runningFold(0L) { timeUs, clip ->
            timeUs + clip.outputDurationMs * 1_000L
        }.drop(1)
        if (boundaries.isEmpty()) return 1f
        // Heartbeat's measured scene changes land primarily on transients rather than only the
        // tempo grid (24/26 boundaries on onsets versus 8/26 on beats in the author track).
        // Other recipes keep their existing beat-only contract.
        val beatTimes = if (HeartbeatMontageProfile.appliesTo(graph) || FearStrobeProfile.appliesTo(graph)) {
            (audio.beats.asSequence().map { it.sampleIndex } +
                audio.onsets.asSequence().map { it.sampleIndex })
                .distinct()
                .map(audio::timestampUs)
                .toList()
        } else {
            audio.beats.map { audio.timestampUs(it.sampleIndex) }
        }
        if (beatTimes.isEmpty()) return 0f
        return boundaries.count { boundary ->
            val alignedToBeat = beatTimes.any { abs(it - boundary) <= toleranceUs }
            // transitionStrength is measured from the encoded pixels, not from the authored graph.
            // The asymmetric window includes the first sample after a hard cut at 10 Hz QA cadence.
            val renderedPeak = samples.asSequence()
                .filter { it.outputTimeUs in boundary - 35_000L..boundary + 155_000L }
                .maxOfOrNull { it.transitionStrength } ?: 0f
            alignedToBeat && renderedPeak >= MINIMUM_VISIBLE_CUT_STRENGTH
        }.toFloat() / boundaries.size
    }

    internal fun repeatedSourceRatio(graph: MontageGraph): Float {
        var overlap = 0L
        // Heartbeat deliberately reprises its first phrase. Audit uniqueness inside the original
        // phrase; counting the authored reprise (and its black-tail source handle) as accidental
        // duplication made every correct Heartbeat graph fail by construction.
        val auditedClips = when {
            HeartbeatMontageProfile.appliesTo(graph) ->
                graph.clips.take(HeartbeatMontageProfile.scenes.count { !it.echo })
            FearStrobeProfile.appliesTo(graph) -> graph.clips.dropLast(1)
            else -> graph.clips
        }
        auditedClips.forEachIndexed { index, clip ->
            auditedClips.drop(index + 1).filter { it.sourceIndex == clip.sourceIndex }.forEach { other ->
                overlap += (minOf(clip.sourceEndMs, other.sourceEndMs) -
                    maxOf(clip.sourceStartMs, other.sourceStartMs)).coerceAtLeast(0L)
            }
        }
        val total = auditedClips.sumOf { it.sourceEndMs - it.sourceStartMs }.coerceAtLeast(1L)
        return (overlap.toFloat() / total).coerceIn(0f, 1f)
    }

    private fun transitionPeak(graph: MontageGraph, samples: List<VisualSample>): Float {
        val boundaries = ArrayList<Pair<Long, Long>>()
        var cursorUs = 0L
        graph.clips.forEachIndexed { index, clip ->
            if (index > 0 && clip.transitionIn in visibleTransitions) {
                boundaries += cursorUs to TransitionTimeline.durationMs(clip.transitionIn) * 1_000L
            }
            cursorUs += clip.outputDurationMs * 1_000L
        }
        if (boundaries.isEmpty()) return 1f
        return boundaries.map { (start, duration) ->
            samples.filter { it.outputTimeUs in start..start + duration }
                .maxOfOrNull { it.transitionStrength } ?: 0f
        }.average().toFloat()
    }

    private fun maximumColourJump(graph: MontageGraph, samples: List<VisualSample>): Pair<Long, Float> {
        val intentionalWindows = ArrayList<LongRange>()
        var cursorUs = 0L
        graph.clips.forEachIndexed { index, clip ->
            if (index > 0) {
                val duration = TransitionTimeline.durationMs(clip.transitionIn) * 1_000L
                // A hard cut is supposed to change the plate instantly. Guard one QA interval
                // around every authored boundary; otherwise a stylistically correct dark/bright
                // cut is misreported as an intra-shot colour instability.
                intentionalWindows += cursorUs..cursorUs + maxOf(duration, COLOUR_BOUNDARY_GUARD_US)
            }
            cursorUs += clip.outputDurationMs * 1_000L
        }
        graph.overlays.filter {
            it.kind == "heartbeat-measured-step" ||
                it.kind == "authored-measured-step" ||
                it.overlayKind == MontageGraph.OverlayKind.FLASH ||
                it.overlayKind == MontageGraph.OverlayKind.BLACK_FADE
        }.forEach { overlay ->
            intentionalWindows += (overlay.startMs * 1_000L - COLOUR_BOUNDARY_GUARD_US)
                .coerceAtLeast(0L)..overlay.endMs * 1_000L + COLOUR_BOUNDARY_GUARD_US
        }
        return samples.zipWithNext().filterNot { (_, right) ->
            intentionalWindows.any { right.outputTimeUs in it }
        }.map { (left, right) ->
            val luma = abs(right.luma - left.luma)
            val chroma = sqrt(
                (right.chromaU - left.chromaU) * (right.chromaU - left.chromaU) +
                    (right.chromaV - left.chromaV) * (right.chromaV - left.chromaV)
            )
            right.outputTimeUs to (luma * .65f + chroma * .35f).coerceIn(0f, 1f)
        }.maxByOrNull { it.second } ?: (0L to 0f)
    }

    internal fun hasFreshFaceEvidence(sample: VisualSample): Boolean =
        sample.decodedFaceEvidence?.let {
            it.isFreshFor(sample.outputTimeUs, sample.decodedSourceIndex, sample.decodedClipIndex) &&
                it.confidence == sample.faceConfidence
        } == true

    internal fun faceLossTimestampsUs(samples: List<VisualSample>): List<Long> = samples
        .asSequence()
        .filter { it.faceExpected && it.faceConfidence < FACE_PRESENCE_THRESHOLD }
        .map { it.outputTimeUs }
        .toList()

    internal fun faceUnknownTimestampsUs(samples: List<VisualSample>): List<Long> = samples
        .asSequence()
        .filter { it.faceExpected && !hasFreshFaceEvidence(it) }
        .map { it.outputTimeUs }
        .toList()

    internal data class AuthorAccentEvidence(
        val hitRate: Float,
        val maximumOffsetUs: Long,
        val missedUs: List<Long>
    )

    internal fun authorAccentEvidence(
        graph: MontageGraph,
        samples: List<VisualSample>,
        toleranceUs: Long = 33_334L
    ): AuthorAccentEvidence {
        if (!ReferenceMontageProfile.appliesTo(graph)) return AuthorAccentEvidence(1f, 0L, emptyList())
        val matches = ReferenceMontageProfile.ACCENT_BEATS_US.map { accentUs ->
            val minimumStrength = if (accentUs == ReferenceMontageProfile.ACCENT_BEATS_US.first()) {
                MINIMUM_OPENING_ACCENT_STRENGTH
            } else {
                MINIMUM_VISIBLE_CUT_STRENGTH
            }
            val match = samples.asSequence()
                .filter { it.transitionStrength >= minimumStrength }
                .map { it to abs(it.outputTimeUs - accentUs) }
                .filter { it.second <= toleranceUs }
                .minByOrNull { it.second }
            accentUs to match?.second
        }
        val hitOffsets = matches.mapNotNull { it.second }
        return AuthorAccentEvidence(
            hitRate = hitOffsets.size.toFloat() / matches.size,
            maximumOffsetUs = hitOffsets.maxOrNull() ?: 0L,
            missedUs = matches.filter { it.second == null }.map { it.first }
        )
    }

    private fun faceLossRate(samples: List<VisualSample>): Float {
        val expected = samples.filter { it.faceExpected }
        if (expected.isEmpty()) return 0f
        return faceLossTimestampsUs(expected).size.toFloat() / expected.size
    }

    private val visibleTransitions = setOf(
        MontageGraph.Transition.WHIP,
        MontageGraph.Transition.OCCLUSION,
        MontageGraph.Transition.FOREGROUND_REENTRY,
        MontageGraph.Transition.BLACKOUT
    )

    private const val MINIMUM_VISIBLE_CUT_STRENGTH = .065f
    private const val MINIMUM_OPENING_ACCENT_STRENGTH = .012f
    private const val FACE_PRESENCE_THRESHOLD = .35f
    private const val COLOUR_BOUNDARY_GUARD_US = 100_000L
}
