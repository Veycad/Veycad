package com.veycad.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Builds alternative edits by matching music accents to semantic visual events. */
object EventMatchingDirector {
    enum class Style { DYNAMIC, BALANCED, CINEMATIC }

    data class Alternative(
        val style: Style,
        val graph: MontageGraph,
        val matchedEventCount: Int,
        val virtualCameraCount: Int
    )

    data class Request(
        val sourceId: String,
        val audioSourceId: String,
        val outputDurationMs: Long,
        val audio: AudioBeatMap,
        val visual: VisualEventMap,
        val frameAttachments: FrameAttachmentTimeline = FrameAttachmentTimeline(),
        val referenceProfileId: String? = null
    ) {
        init {
            require(sourceId.isNotBlank() && audioSourceId.isNotBlank())
            val sourceDurationMs = visual.durationUs / 1_000L
            require(outputDurationMs >= 1_000L && (
                outputDurationMs <= sourceDurationMs ||
                    referenceProfileId == ReferenceMontageProfile.ID &&
                    ReferenceMontageProfile.canExtendSourceToTimeline(sourceDurationMs, outputDurationMs) ||
                    referenceProfileId == FearStrobeProfile.ID &&
                    FearStrobeProfile.canExtendSourceToTimeline(sourceDurationMs, outputDurationMs) ||
                    referenceProfileId == DualityLoopProfile.ID &&
                    DualityLoopProfile.canExtendSourceToTimeline(sourceDurationMs, outputDurationMs)
                )) {
                "Source duration $sourceDurationMs ms cannot fill output timeline $outputDurationMs ms"
            }
        }
    }

    fun direct(request: Request, requestedStyle: Style? = null): List<Alternative> =
        (requestedStyle?.let { listOf(it) } ?: Style.entries).map { style ->
            buildAlternative(request, style)
        }.also { alternatives ->
            require(alternatives.map { signature(it.graph) }.distinct().size == alternatives.size) {
                "Director alternatives must be structurally different"
            }
        }

    private data class Selection(
        val startMs: Long,
        val endMs: Long,
        val event: VisualEventMap.Event?,
        val score: Float
    )

    private fun buildAlternative(request: Request, style: Style): Alternative {
        val boundaries = editBoundaries(request, style)
        val durations = boundaries.zipWithNext { left, right -> right - left }
        val used = ArrayList<LongRange>()
        // A scarce actual wide take belongs to the finale before the entrance can consume it.
        // Without wide coverage, retain the entrance's existing priority for clean live mattes.
        val wideFinale = if (request.referenceProfileId == ReferenceMontageProfile.ID) {
            selectVisualWindow(request, style, durations.lastIndex, durations.size, durations.last(),
                nearestBeat(request.audio, boundaries[durations.lastIndex]), emptyList()).takeIf { take ->
                val samples = request.frameAttachments.frames.filter {
                    it.sourceTimeUs / 1_000L in take.startMs until take.endMs
                }
                val faces = samples.mapNotNull { it.faceRegion?.takeIf { face -> face.confidence >= .65f } }
                faces.isNotEmpty() && faces.map { kotlin.math.sqrt(it.width * it.height) }.average() <= .25 &&
                    samples.count(::isCleanRetainedSubject).toFloat() / samples.size >= .85f
            }
        } else null
        wideFinale?.let { used += it.startMs until it.endMs }
        // Reserve the clean live entrance among remaining takes before generic moments.
        val openingReservation = if (request.referenceProfileId == ReferenceMontageProfile.ID) {
            alignReferenceOpeningToMask(request, 0, selectVisualWindow(
                request, style, 0, durations.size, durations.first(),
                nearestBeat(request.audio, 0L), used
            ), used)
        } else null
        openingReservation?.let { used += it.startMs until it.endMs }
        val finaleReservation = wideFinale ?: durations.lastOrNull()?.takeIf {
            request.referenceProfileId == ReferenceMontageProfile.ID
        }?.let { finaleDuration ->
            selectVisualWindow(
                request = request,
                style = style,
                clipIndex = durations.lastIndex,
                clipCount = durations.size,
                outputDurationMs = finaleDuration,
                musicBeat = nearestBeat(request.audio, boundaries[durations.lastIndex]),
                used = used
            )
        }
        if (wideFinale == null) finaleReservation?.let { used += it.startMs until it.endMs }
        // The paired silhouettes need headroom before ordinary portraits consume clean takes.
        val mirrorReservation = if (request.referenceProfileId == ReferenceMontageProfile.ID &&
            style == Style.DYNAMIC && durations.size > 12) {
            selectVisualWindow(request, style, 12, durations.size, durations[12],
                nearestBeat(request.audio, boundaries[12]), used)
        } else null
        mirrorReservation?.let { used += it.startMs until it.endMs }
        // Reserve the reference's directional transition role before generic clips consume its
        // source window. The reservation still comes from a measured left/right CAMERA_MOVE;
        // if the material has no such event, the profile correctly falls back to a hard cut.
        val referenceWhipReservation = if (
            request.referenceProfileId == ReferenceMontageProfile.ID &&
            style == Style.DYNAMIC && durations.lastIndex >= REFERENCE_WHIP_CLIP_INDEX
        ) selectReferenceWhipWindow(
            request,
            outputDurationMs = durations[REFERENCE_WHIP_CLIP_INDEX],
            used = used
        ) else null
        referenceWhipReservation?.let { used += it.startMs until it.endMs }
        val clips = ArrayList<MontageGraph.Clip>()
        var matched = 0
        var virtual = 0
        var foregroundReentryAuthored = false

        durations.forEachIndexed { index, outputDuration ->
            val outputStart = boundaries[index]
            val musicBeat = nearestBeat(request.audio, outputStart)
            val selectedWindow = when {
                index == 0 && openingReservation != null -> openingReservation
                index == durations.lastIndex && finaleReservation != null -> finaleReservation
                index == 12 && mirrorReservation != null -> mirrorReservation
                index == REFERENCE_WHIP_CLIP_INDEX && referenceWhipReservation != null ->
                    referenceWhipReservation
                else -> selectVisualWindow(
                    request = request,
                    style = style,
                    clipIndex = index,
                    clipCount = durations.size,
                    outputDurationMs = outputDuration,
                    musicBeat = musicBeat,
                    used = used,
                    previousWindow = clips.lastOrNull()?.let { it.sourceStartMs until it.sourceEndMs }
                )
            }
            val selection = if (index == 0 && openingReservation != null) selectedWindow
                else alignReferenceOpeningToMask(request, index, selectedWindow, used)
            val selectedRange = selection.startMs until selection.endMs
            if (selectedRange !in used) used += selectedRange
            if (selection.event != null) matched++
            val role = roleFor(index, durations.size, selection.event)
            val motion = motionFor(selection.event, style, index)
            if (motion == MontageGraph.Motion.PUSH_IN || motion == MontageGraph.Motion.PUSH_OUT) virtual++
            val naturalTransition = if (request.referenceProfileId == ReferenceMontageProfile.ID &&
                style == Style.DYNAMIC && index != 0 && index != REFERENCE_WHIP_CLIP_INDEX) {
                // The measured Sigma slots are cuts with authored overlays, not extra whips
                // inferred from whichever source gesture happens to land at that boundary.
                MontageGraph.Transition.HARD_CUT
            } else transitionFor(selection.event, index, request.frameAttachments)
            val hasConfidentIncomingMask = request.frameAttachments
                .nearest(selection.startMs * 1_000L)?.mask?.confidence?.let { it >= .80f } == true
            // The authored reference establishes its visual hook before the first phrase resolves:
            // isolate the subject early, then reveal the original background. Keeping this in the
            // first two incoming moments also makes the effect readable instead of decorative.
            val isReadableReentryPosition = index in 1..minOf(2, durations.lastIndex - 1)
            val transition = if (index == 0 && hasConfidentIncomingMask) {
                foregroundReentryAuthored = true
                MontageGraph.Transition.FOREGROUND_REENTRY
            } else if (
                !foregroundReentryAuthored && naturalTransition == MontageGraph.Transition.HARD_CUT &&
                isReadableReentryPosition && hasConfidentIncomingMask
            ) {
                foregroundReentryAuthored = true
                MontageGraph.Transition.FOREGROUND_REENTRY
            } else {
                if (naturalTransition == MontageGraph.Transition.FOREGROUND_REENTRY) {
                    foregroundReentryAuthored = true
                }
                naturalTransition
            }
            clips += MontageGraph.Clip(
                id = "${style.name.lowercase()}-$index",
                sourceStartMs = selection.startMs,
                sourceEndMs = selection.endMs,
                outputDurationMs = outputDuration,
                role = role,
                transitionIn = transition,
                motion = motion,
                confidence = selection.score.coerceIn(.05f, 1f),
                beatAnchorMs = outputStart,
                transform = transformFor(motion, style, role),
                speedRamp = speedFor(style, selection, outputDuration),
                exposureBias = exposureFor(selection, request.visual),
                flowStrength = selection.event?.takeIf {
                    it.type == VisualEventMap.EventType.CAMERA_MOVE ||
                        it.type == VisualEventMap.EventType.SUBJECT_MOVE
                }?.strength ?: 0f
            )
        }

        val base = MontageGraph(
            sourceDurationMs = request.visual.durationUs / 1_000L,
            outputDurationMs = request.outputDurationMs,
            clips = clips,
            audioTrack = MontageGraph.AudioTrack(request.audioSourceId),
            frameAttachments = request.frameAttachments,
            metadata = NleProjectMetadata(generator = request.referenceProfileId?.let { profile ->
                "veycad-reference-$profile-${style.name.lowercase()}"
            } ?: "veycad-event-director-${style.name.lowercase()}")
        )
        val parameterized = base.copy(parameterTracks = ParameterTrackFactory.fromLegacy(base))
        val withEffects = EffectOrchestrator.orchestrate(parameterized, request.audio, request.visual).graph
        return Alternative(style, withEffects, matched, virtual)
    }

    private fun editBoundaries(request: Request, style: Style): List<Long> {
        if (style == Style.DYNAMIC && request.referenceProfileId == ReferenceMontageProfile.ID) {
            return requireNotNull(ReferenceMontageProfile.boundariesMs(request.outputDurationMs))
        }
        val minimumGap = when (style) {
            Style.DYNAMIC -> 500L
            Style.BALANCED -> 900L
            Style.CINEMATIC -> 1_300L
        }
        val averageHoldMs = when (style) {
            Style.DYNAMIC -> 1_100L
            Style.BALANCED -> 1_750L
            Style.CINEMATIC -> 2_500L
        }
        val targetSegments = when (style) {
            Style.DYNAMIC -> (request.outputDurationMs / averageHoldMs).toInt().coerceIn(7, 16)
            Style.BALANCED -> (request.outputDurationMs / averageHoldMs).toInt().coerceIn(4, 11)
            Style.CINEMATIC -> (request.outputDurationMs / averageHoldMs).toInt().coerceIn(3, 8)
        }
        val targetCuts = targetSegments - 1
        val candidates = request.audio.beats.map { beat ->
            val timeMs = request.audio.timestampUs(beat.sampleIndex) / 1_000L
            val structuralScore = beat.strength +
                (if (beat.isDownbeat) .42f else 0f) +
                (if (beat.dominantBand == AudioBeatMap.FrequencyBand.LOW) .16f else 0f)
            timeMs to structuralScore
        }.filter { (timeMs, _) ->
            timeMs in minimumGap..request.outputDurationMs - minimumGap
        }

        // Select a limited number of phrase-defining accents. Remaining onsets are available to
        // EffectOrchestrator, but no longer create a structural cut by themselves.
        val accepted = ArrayList<Long>(targetCuts)
        candidates.sortedByDescending { it.second }.forEach { (timeMs, _) ->
            if (accepted.size < targetCuts && accepted.all { abs(it - timeMs) >= minimumGap }) {
                accepted += timeMs
            }
        }
        if (accepted.size < targetCuts) {
            val fallbackGap = request.outputDurationMs / targetSegments
            for (index in 1 until targetSegments) {
                val ideal = fallbackGap * index
                val snapped = candidates.map { it.first }.minByOrNull { abs(it - ideal) } ?: ideal
                if (snapped in minimumGap..request.outputDurationMs - minimumGap &&
                    accepted.all { abs(it - snapped) >= minimumGap }) {
                    accepted += snapped
                }
            }
        }
        return (listOf(0L) + accepted.sorted() + request.outputDurationMs)
            .distinct()
            .zipWithNext()
            .fold(mutableListOf(0L)) { output, (left, right) ->
                if (right - left >= 280L) output.apply { add(right) } else output
            }.let { if (it.last() == request.outputDurationMs) it else it + request.outputDurationMs }
    }

    private fun selectVisualWindow(
        request: Request,
        style: Style,
        clipIndex: Int,
        clipCount: Int,
        outputDurationMs: Long,
        musicBeat: AudioBeatMap.Beat?,
        used: List<LongRange>,
        previousWindow: LongRange? = null
    ): Selection {
        val sourceDurationMs = request.visual.durationUs / 1_000L
        val referenceFinale = request.referenceProfileId == ReferenceMontageProfile.ID &&
            clipIndex == clipCount - 1
        val finaleGuardMs = if (referenceFinale) REFERENCE_FINALE_GUARD_MS else FINALE_GUARD_MS
        val minimumFinaleQuality = if (referenceFinale) REFERENCE_MINIMUM_FINALE_QUALITY
            else MINIMUM_FINALE_QUALITY
        val remainingClips = (clipCount - clipIndex).coerceAtLeast(1)
        val remainingSourceMs = (sourceDurationMs - used.sumOf { it.last - it.first + 1L }).coerceAtLeast(1L)
        val maximumWindowMs = max(1L, remainingSourceMs / remainingClips).let { available ->
            if (request.referenceProfileId == ReferenceMontageProfile.ID &&
                clipIndex == clipCount - 1) min(available, 1_000L) else available
        }
        val orderedEvents = request.visual.events
            .filterNot { it.type == VisualEventMap.EventType.OCCLUSION && clipIndex == 0 }
            .filterNot { it.type == VisualEventMap.EventType.OCCLUSION && clipIndex == clipCount - 1 }
            .filter { it.confidence >= .32f }
            .map { event ->
                val window = sourceWindow(event, outputDurationMs, sourceDurationMs, maximumWindowMs)
                val quality = selectionQuality(
                    request.visual,
                    window.first,
                    window.second,
                    finale = clipIndex == clipCount - 1,
                    guardMs = finaleGuardMs
                )
                Triple(event, window, matchScore(event, musicBeat, style, clipIndex, clipCount) * quality *
                    referenceWindowFit(request, window.first, window.second, clipIndex, clipCount, previousWindow))
            }
            .filter { (_, window, _) -> selectionQuality(
                request.visual,
                window.first,
                window.second,
                finale = clipIndex == clipCount - 1,
                guardMs = finaleGuardMs
            ) >= if (clipIndex == clipCount - 1) minimumFinaleQuality else .34f }
            .sortedByDescending { it.third }
        for ((event, sourceWindow, score) in if (referenceFinale) emptyList() else orderedEvents) {
            if (used.none { overlaps(it, sourceWindow.first until sourceWindow.second) }) {
                return Selection(sourceWindow.first, sourceWindow.second, event, score)
            }
        }

        // Static/low-information fallback is deliberate virtual cinematography on unique time ranges.
        val desired = min(max(1L, min(outputDurationMs, 1_500L)), maximumWindowMs)
        val phase = when (style) {
            Style.DYNAMIC -> .17
            Style.BALANCED -> .43
            Style.CINEMATIC -> .71
        }
        val seed = ((clipIndex + phase) / (clipCount + 1.0) * sourceDurationMs).toLong()
        val starts = sequence {
            yield(seed.coerceIn(0L, max(0L, sourceDurationMs - desired)))
            // Rank the entire final take against other valid windows. An event peak's
            // lead-in can contain a close shot even when the peak itself is wide.
            if (referenceFinale) orderedEvents.forEach { yield(it.second.first) }
            if (clipIndex == clipCount - 1) {
                for (observation in request.visual.observations) {
                    yield((observation.sourceTimeUs / 1_000L - desired * 2L / 5L)
                        .coerceIn(0L, max(0L, sourceDurationMs - desired)))
                }
            }
            var cursor = 0L
            while (cursor + desired <= sourceDurationMs) {
                yield(cursor)
                cursor += desired
            }
        }
        val candidate = starts.distinct().filter { candidate ->
            used.none { overlaps(it, candidate until candidate + desired) }
        }.filter { candidate ->
            clipIndex != clipCount - 1 || selectionQuality(
                request.visual,
                candidate,
                candidate + desired,
                finale = true,
                guardMs = finaleGuardMs
            ) >= minimumFinaleQuality
        }.maxByOrNull { candidate ->
            val quality = selectionQuality(
                request.visual,
                candidate,
                candidate + desired,
                finale = clipIndex == clipCount - 1,
                guardMs = finaleGuardMs
            )
            val phaseAffinity = 1f - abs(candidate - seed).toFloat() / sourceDurationMs.coerceAtLeast(1L)
            (quality * .82f + phaseAffinity.coerceIn(0f, 1f) * .18f) *
                referenceWindowFit(request, candidate, candidate + desired, clipIndex, clipCount, previousWindow)
        }
        if (candidate != null) return Selection(candidate, candidate + desired, null, .48f)

        val gap = largestGap(used, sourceDurationMs)
        val fallbackDuration = min(desired, gap.last - gap.first + 1L).coerceAtLeast(1L)
        return Selection(gap.first, gap.first + fallbackDuration, null, .48f)
    }

    private fun selectReferenceWhipWindow(
        request: Request,
        outputDurationMs: Long,
        used: List<LongRange>
    ): Selection? {
        val sourceDurationMs = request.visual.durationUs / 1_000L
        return request.visual.events.asSequence()
            .filter {
                it.type == VisualEventMap.EventType.CAMERA_MOVE &&
                    it.direction in setOf(VisualEventMap.Direction.LEFT, VisualEventMap.Direction.RIGHT) &&
                    it.confidence >= .32f
            }
            .map { event ->
                val window = sourceWindow(event, outputDurationMs, sourceDurationMs, outputDurationMs)
                val quality = selectionQuality(
                    request.visual, window.first, window.second, finale = false
                )
                Selection(window.first, window.second, event, event.strength * quality)
            }
            .filter { selection ->
                selection.score >= .12f && used.none {
                    overlaps(it, selection.startMs until selection.endMs)
                }
            }
            .maxByOrNull { it.score }
    }

    private fun referenceWindowFit(
        request: Request,
        startMs: Long,
        endMs: Long,
        clipIndex: Int,
        clipCount: Int,
        previousWindow: LongRange?
    ): Float {
        if (request.referenceProfileId != ReferenceMontageProfile.ID) return 1f
        val samples = request.visual.observations.filter {
            it.sourceTimeUs / 1_000L in startMs until endMs
        }
        if (samples.isEmpty()) return .6f
        val faces = request.frameAttachments.frames.filter {
            it.sourceTimeUs / 1_000L in startMs until endMs
        }.mapNotNull { it.faceRegion?.takeIf { face -> face.confidence >= .65f } }
        val faceScale = faces.map { kotlin.math.sqrt(it.width * it.height) }
            .average().toFloat().takeIf { it.isFinite() } ?: .3f
        // Relative face size, rather than a role label, distinguishes actual wide and close
        // takes. Rank available coverage; these targets cannot manufacture a missing full body.
        val targetScale = when {
            clipIndex == clipCount - 1 -> .18f
            clipIndex in setOf(2, 4, 9, 11, 12) -> .24f
            clipIndex in setOf(3, 6, 7, 8, 13) -> .42f
            else -> .32f
        }
        val scaleFit = (1f - abs(faceScale - targetScale) / .30f).coerceIn(0f, 1f)
        var fit = if (clipIndex == clipCount - 1) .25f + scaleFit * .75f
            else .55f + scaleFit * .45f
        // Oversized faces can win on motion while breaking the intended medium/wide role.
        val widestFace = faces.maxOfOrNull { it.width } ?: 0f
        fit *= SigmaComposition.faceScaleFit(widestFace, clipIndex in setOf(2, 4, 9, 11, 12) || clipIndex == clipCount - 1)
        // A confident foreground mask may be an animal. Missing face size must not make
        // it an ideal medium character shot; body evidence still supports a face-free wide take.
        fit *= samples.map { maxOf(it.humanPresenceConfidence, it.face?.confidence ?: 0f) }
            .average().toFloat()
        if (clipIndex == clipCount - 1 || clipIndex in setOf(11, 12)) {
            val masks = request.frameAttachments.frames.filter {
                it.sourceTimeUs / 1_000L in startMs until endMs
            }
            val cleanRatio = if (masks.isEmpty()) 0f else
                masks.count { frame ->
                    val face = frame.faceRegion
                    isCleanRetainedSubject(frame) && (face == null ||
                        face.centerY - face.height * .5f >= face.height * .35f)
                }.toFloat() / masks.size
            fit *= .15f + cleanRatio * .85f
        }
        if (previousWindow != null) {
            val previous = request.visual.observations.filter {
                it.sourceTimeUs / 1_000L in previousWindow
            }
            if (previous.isNotEmpty()) {
                fun yaw(window: List<VisualEventMap.Observation>): Float =
                    window.mapNotNull { it.face?.yawDegrees }.average().toFloat()
                        .takeIf { it.isFinite() } ?: 0f
                val yawChange = (abs(yaw(samples) - yaw(previous)) / 35f).coerceIn(0f, 1f)
                val previousScale = previous.mapNotNull { it.composition?.subjectScale }
                    .average().toFloat().takeIf { it.isFinite() } ?: .5f
                val currentScale = samples.mapNotNull { it.composition?.subjectScale }
                    .average().toFloat().takeIf { it.isFinite() } ?: previousScale
                val scaleChange = (abs(currentScale - previousScale) / .20f).coerceIn(0f, 1f)
                val lightChange = (abs(samples.map { it.meanLuma }.average() -
                    previous.map { it.meanLuma }.average()) / .20).toFloat().coerceIn(0f, 1f)
                fit *= .62f + maxOf(yawChange, scaleChange, lightChange) * .38f
            }
        }
        return fit
    }

    private fun alignReferenceOpeningToMask(
        request: Request,
        clipIndex: Int,
        selection: Selection,
        used: List<LongRange>
    ): Selection {
        if (clipIndex != 0 || request.referenceProfileId != ReferenceMontageProfile.ID) return selection
        val selectionStartUs = selection.startMs * 1_000L
        val durationMs = selection.endMs - selection.startMs
        val latestStartMs = request.visual.durationUs / 1_000L - durationMs
        fun stability(startMs: Long): Float {
            val samples = request.frameAttachments.frames.filter {
                it.sourceTimeUs / 1_000L in startMs until startMs + durationMs
            }
            if (samples.isEmpty()) return 0f
            val confidentMaskRatio = samples.count {
                (it.mask?.confidence ?: 0f) >= .80f
            }.toFloat() / samples.size
            val cleanRatio = samples.count(::isCleanRetainedSubject).toFloat() / samples.size
            val quality = samples.map { it.subjectQuality }.average().toFloat()
            val headroom = samples.count { frame ->
                frame.faceRegion?.let { face ->
                    face.centerY - face.height * .5f >= face.height * .55f
                } == true
            }.toFloat() / samples.size
            val composition = referenceWindowFit(request, startMs, startMs + durationMs,
                0, ReferenceMontageProfile.DYNAMIC_BOUNDARIES_MS.size - 1, null)
            return confidentMaskRatio * .25f + cleanRatio * .25f + quality * .15f +
                composition * .20f + headroom * .15f
        }
        val currentIsClean = request.frameAttachments.nearest(selectionStartUs)
            ?.let(::isCleanRetainedSubject) == true
        val currentScore = stability(selection.startMs)
        val attachment = request.frameAttachments.frames
            .asSequence()
            .filter(::isCleanRetainedSubject)
            .filter { frame -> frame.sourceTimeUs / 1_000L <= latestStartMs }
            .filter { frame ->
                val startMs = frame.sourceTimeUs / 1_000L
                used.none { overlaps(it, startMs until startMs + durationMs) }
            }
            .maxByOrNull { frame ->
                val startMs = frame.sourceTimeUs / 1_000L
                stability(startMs) + frame.subjectQuality * .05f -
                    (abs(frame.sourceTimeUs - selectionStartUs).toFloat() / request.visual.durationUs) * .015f
            }
            ?: return selection
        val startMs = attachment.sourceTimeUs / 1_000L
        // A clean first matte is insufficient when the following live frames lose the subject.
        // Keep the existing editorial choice only when its whole decoder window is at least as
        // stable as the best mask-aligned alternative.
        if (currentIsClean && currentScore >= stability(startMs)) return selection
        return selection.copy(startMs = startMs, endMs = startMs + durationMs)
    }

    private fun sourceWindow(
        event: VisualEventMap.Event,
        outputDurationMs: Long,
        sourceDurationMs: Long,
        maximumWindowMs: Long
    ): Pair<Long, Long> {
        val usableStart = event.usableWindow.startUs / 1_000L
        val usableEnd = event.usableWindow.endUs / 1_000L
        val desired = min(maximumWindowMs, max(1L, min(1_500L, max(outputDurationMs, usableEnd - usableStart))))
        val peak = event.peakTimeUs / 1_000L
        val start = (peak - desired * 2L / 5L).coerceIn(0L, max(0L, sourceDurationMs - desired))
        return start to min(sourceDurationMs, start + desired)
    }

    private fun windowQuality(visual: VisualEventMap, startMs: Long, endMs: Long): Float {
        val samples = visual.observations.filter { it.sourceTimeUs / 1_000L in startMs until endMs }
        if (samples.isEmpty()) return .45f
        val average = samples.map { it.visualQuality }.average().toFloat()
        val minimum = samples.minOf { it.visualQuality }
        return (average * .72f + minimum * .28f).coerceIn(0f, 1f)
    }

    /** A finale is a readable conclusion, never the residue of an occlusion gesture. */
    private fun selectionQuality(
        visual: VisualEventMap,
        startMs: Long,
        endMs: Long,
        finale: Boolean,
        guardMs: Long = FINALE_GUARD_MS
    ): Float {
        val base = windowQuality(visual, startMs, endMs)
        if (!finale) return base
        // Guard both ends because speed ramps can sample close to the selected boundary and the
        // semantic analyzer is intentionally sparser than the decoder. Without this margin a hand
        // entering just after the last in-window semantic sample was selected as a "clean" finale.
        val guardedStartMs = (startMs - guardMs).coerceAtLeast(0L)
        val guardedEndMs = (endMs + guardMs).coerceAtMost(visual.durationUs / 1_000L)
        val samples = visual.observations.filter {
            it.sourceTimeUs / 1_000L in guardedStartMs..guardedEndMs
        }
        if (samples.isEmpty()) return 0f
        val faceRatio = samples.count { (it.face?.confidence ?: 0f) >= .65f }.toFloat() / samples.size
        val maximumOcclusion = samples.maxOf { it.occlusionConfidence }
        val boundaryFaceIsReadable = listOf(samples.first(), samples.last()).all {
            (it.face?.confidence ?: 0f) >= .65f && it.composition != null
        }
        if (!boundaryFaceIsReadable || faceRatio < .85f || maximumOcclusion >= .25f) return 0f
        val composition = samples.mapNotNull { it.composition?.quality }
            .average().toFloat().takeIf { it.isFinite() } ?: .45f
        // A live subject stage still needs a readable continuous take. Prefer a calm moving
        // window over a nominally sharper peak whose last frames contain a fast head/camera move;
        // the latter becomes motion blur after the dark-stage grade. This ranks whole windows and
        // never turns the selected interval into a retained/frozen frame.
        val maximumMotion = samples.maxOf {
            it.cameraMotion.magnitude + it.subjectMotion.magnitude
        }
        val motionReadability = (1f - maximumMotion * .90f).coerceIn(0f, 1f)
        val minimumVisualQuality = samples.minOf { it.visualQuality }
        return (base * .30f + faceRatio * .28f + composition * .14f +
            motionReadability * .20f + minimumVisualQuality * .08f).coerceIn(0f, 1f)
    }

    private fun exposureFor(selection: Selection, visual: VisualEventMap): Float {
        val target = visual.observations.map { it.meanLuma }.sorted().let { values ->
            values[values.size / 2]
        }
        val window = visual.observations.filter {
            it.sourceTimeUs / 1_000L in selection.startMs until selection.endMs
        }
        val current = window.map { it.meanLuma }.average().toFloat().takeIf { it.isFinite() } ?: target
        return (target / current.coerceAtLeast(.08f) - 1f).coerceIn(-.18f, .18f)
    }

    private fun matchScore(
        event: VisualEventMap.Event,
        beat: AudioBeatMap.Beat?,
        style: Style,
        clipIndex: Int,
        clipCount: Int
    ): Float {
        var score = event.strength * .45f + event.confidence * .3f
        if (clipIndex == 0 && (event.type == VisualEventMap.EventType.COMPOSITION_PEAK ||
                event.type == VisualEventMap.EventType.CLEAN_HOLD)) score += .35f
        if (style == Style.DYNAMIC && clipIndex == 1 &&
            event.type == VisualEventMap.EventType.CAMERA_MOVE &&
            event.direction in setOf(VisualEventMap.Direction.LEFT, VisualEventMap.Direction.RIGHT)) {
            score += .35f
        }
        if (clipIndex > 0 && event.type == VisualEventMap.EventType.SUBJECT_REVEAL) {
            score += when (style) {
                Style.DYNAMIC -> .30f
                Style.BALANCED -> .20f
                Style.CINEMATIC -> .10f
            }
        }
        if (clipIndex == clipCount - 1 && event.type != VisualEventMap.EventType.OCCLUSION) score += .12f
        if (beat != null) {
            if (beat.dominantBand == AudioBeatMap.FrequencyBand.LOW &&
                event.type in setOf(VisualEventMap.EventType.CAMERA_MOVE, VisualEventMap.EventType.GESTURE)) score += .22f
            if (beat.strength > .7f && event.type == VisualEventMap.EventType.FACE_TURN) score += .12f
        }
        return score.coerceIn(0f, 1f)
    }

    private fun transitionFor(
        event: VisualEventMap.Event?,
        index: Int,
        frameAttachments: FrameAttachmentTimeline
    ): MontageGraph.Transition {
        if (index == 0) return MontageGraph.Transition.OPEN
        return when {
            event?.type == VisualEventMap.EventType.OCCLUSION && event.confidence >= .72f ->
                MontageGraph.Transition.OCCLUSION
            event?.type == VisualEventMap.EventType.SUBJECT_REVEAL && event.confidence >= .72f &&
                frameAttachments.nearest(event.peakTimeUs)?.mask?.confidence?.let { it >= .80f } == true ->
                MontageGraph.Transition.FOREGROUND_REENTRY
            event?.type == VisualEventMap.EventType.CAMERA_MOVE &&
                event.direction in setOf(VisualEventMap.Direction.LEFT, VisualEventMap.Direction.RIGHT) ->
                MontageGraph.Transition.WHIP
            else -> MontageGraph.Transition.HARD_CUT
        }
    }

    private fun motionFor(
        event: VisualEventMap.Event?,
        style: Style,
        index: Int
    ): MontageGraph.Motion = when {
        event?.type == VisualEventMap.EventType.CAMERA_MOVE && event.direction == VisualEventMap.Direction.LEFT ->
            MontageGraph.Motion.WHIP_LEFT
        event?.type == VisualEventMap.EventType.CAMERA_MOVE && event.direction == VisualEventMap.Direction.RIGHT ->
            MontageGraph.Motion.WHIP_RIGHT
        index % 2 == 1 -> MontageGraph.Motion.PUSH_OUT
        else -> MontageGraph.Motion.PUSH_IN
    }

    private fun transformFor(
        motion: MontageGraph.Motion,
        style: Style,
        role: MontageGraph.ShotRole
    ): MontageGraph.ClipTransform {
        val styleAmount = when (style) {
            Style.DYNAMIC -> .12f
            Style.BALANCED -> .08f
            Style.CINEMATIC -> .055f
        }
        val roleMultiplier = when (role) {
            MontageGraph.ShotRole.CLOSE -> 1.38f
            MontageGraph.ShotRole.DETAIL -> 1.52f
            MontageGraph.ShotRole.ACTION -> 1.18f
            MontageGraph.ShotRole.FINALE -> .92f
            MontageGraph.ShotRole.OPENING -> .72f
            MontageGraph.ShotRole.ESTABLISHING -> .82f
        }
        val amount = (styleAmount * roleMultiplier).coerceAtMost(.12f)
        // Without a measured subject centre, horizontal drift can silently crop one side of a
        // portrait frame. Keep push/pull centred; directional movement belongs to the short whip.
        val driftX = 0f
        val driftY = if (role == MontageGraph.ShotRole.CLOSE || role == MontageGraph.ShotRole.DETAIL) {
            -.008f
        } else .004f
        return when (motion) {
            MontageGraph.Motion.PUSH_IN -> if (
                style == Style.DYNAMIC && role in setOf(
                    MontageGraph.ShotRole.CLOSE,
                    MontageGraph.ShotRole.DETAIL,
                    MontageGraph.ShotRole.ACTION
                )
            ) MontageGraph.ClipTransform(listOf(
                MontageGraph.ClipTransform.Keyframe(0f, 1f + amount * .16f, -driftX * .25f, 0f),
                MontageGraph.ClipTransform.Keyframe(.18f, 1f + amount, driftX * .45f, driftY),
                MontageGraph.ClipTransform.Keyframe(1f, 1f + amount * .58f, driftX, driftY)
            )) else MontageGraph.ClipTransform(listOf(
                MontageGraph.ClipTransform.Keyframe(0f, 1f, -driftX * .3f, 0f),
                MontageGraph.ClipTransform.Keyframe(1f, 1f + amount, driftX, driftY)
            ))
            MontageGraph.Motion.PUSH_OUT -> MontageGraph.ClipTransform(listOf(
                MontageGraph.ClipTransform.Keyframe(0f, 1f + amount, -driftX * .35f, driftY),
                MontageGraph.ClipTransform.Keyframe(1f, 1.012f, driftX, 0f)
            ))
            else -> MontageGraph.ClipTransform.hold(1.035f)
        }
    }

    private fun speedFor(style: Style, selection: Selection, outputDurationMs: Long): MontageGraph.SpeedRamp {
        val ratio = (selection.endMs - selection.startMs).toFloat() / outputDurationMs
        val center = ratio.coerceIn(.55f, 1.8f)
        val spread = when (style) {
            Style.DYNAMIC -> .24f
            Style.BALANCED -> .12f
            Style.CINEMATIC -> .06f
        }
        return MontageGraph.SpeedRamp(listOf(
            MontageGraph.SpeedRamp.Keyframe(0f, (center - spread).coerceIn(.35f, 2.5f),
                MontageGraph.SpeedRamp.CubicBezier(.2f, .7f, .25f, 1f)),
            MontageGraph.SpeedRamp.Keyframe(.55f, (center + spread).coerceIn(.35f, 2.5f)),
            MontageGraph.SpeedRamp.Keyframe(1f, center.coerceIn(.35f, 2.5f))
        ))
    }

    private fun roleFor(
        index: Int,
        count: Int,
        event: VisualEventMap.Event?
    ): MontageGraph.ShotRole = when {
        index == 0 -> MontageGraph.ShotRole.OPENING
        index == count - 1 -> MontageGraph.ShotRole.FINALE
        event?.type == VisualEventMap.EventType.FACE_TURN -> MontageGraph.ShotRole.CLOSE
        event?.type == VisualEventMap.EventType.GESTURE -> MontageGraph.ShotRole.ACTION
        event?.type == VisualEventMap.EventType.COMPOSITION_PEAK -> MontageGraph.ShotRole.ESTABLISHING
        else -> MontageGraph.ShotRole.DETAIL
    }

    private fun nearestBeat(audio: AudioBeatMap, outputMs: Long): AudioBeatMap.Beat? =
        audio.beats.minByOrNull { abs(audio.timestampUs(it.sampleIndex) / 1_000L - outputMs) }

    private fun overlaps(left: LongRange, right: LongRange): Boolean =
        left.first < right.last + 1L && right.first < left.last + 1L

    private fun isCleanRetainedSubject(frame: FrameAttachments): Boolean =
        frame.mask?.confidence?.let { it >= .80f } == true &&
            frame.subjectQuality >= .56f && frame.subjectOcclusion <= .38f &&
            frame.maskTemporalIou >= .62f

    private fun largestGap(used: List<LongRange>, durationMs: Long): LongRange {
        var cursor = 0L
        var bestStart = 0L
        var bestEndExclusive = 0L
        used.sortedBy { it.first }.forEach { range ->
            if (range.first > cursor && range.first - cursor > bestEndExclusive - bestStart) {
                bestStart = cursor
                bestEndExclusive = range.first
            }
            cursor = max(cursor, range.last + 1L)
        }
        if (cursor < durationMs && durationMs - cursor > bestEndExclusive - bestStart) {
            bestStart = cursor
            bestEndExclusive = durationMs
        }
        val best = bestStart until bestEndExclusive
        require(!best.isEmpty()) { "No unique source time remains for montage clip" }
        return best
    }

    private fun signature(graph: MontageGraph): String = graph.clips.joinToString("|") {
        "${it.outputDurationMs}:${it.sourceStartMs}:${it.motion}:${it.transitionIn}"
    }

    private const val MINIMUM_FINALE_QUALITY = .72f
    private const val FINALE_GUARD_MS = 500L
    private const val REFERENCE_MINIMUM_FINALE_QUALITY = .66f
    private const val REFERENCE_FINALE_GUARD_MS = 125L
    private const val REFERENCE_WHIP_CLIP_INDEX = 11
}
