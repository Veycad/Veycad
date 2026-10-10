package com.veycad.app

import kotlin.math.abs

/** Builds the authored FEAR chronology from source-selected moments without semantic masks. */
internal object FearDirector {
    enum class CoverageMode { EXACT, ADAPTIVE }

    data class BuildResult(val graph: MontageGraph, val coverageMode: CoverageMode)

    private data class Moment(
        val clip: MontageGraph.Clip,
        val scale: Float,
        val yaw: Float,
        val pitch: Float,
        val gesture: Float?,
        val motion: Float,
        val radialMotion: Float,
        val faceConfidence: Float,
        val quality: Float
    )

    fun build(pool: MontageGraph, audioSourceId: String, visualMap: VisualEventMap): MontageGraph =
        buildWithMode(pool, audioSourceId, visualMap).graph

    internal fun buildWithMode(
        pool: MontageGraph,
        audioSourceId: String,
        visualMap: VisualEventMap
    ): BuildResult {
        require(pool.clips.size >= 2) { "FEAR needs at least an opener and a title moment" }
        val ownPool = pool.metadata.generator == "fear-source-pool"
        val preferredOpenerIndex = (if (ownPool) pool.clips.indexOfFirst {
            it.role == MontageGraph.ShotRole.OPENING
        }.takeIf { it >= 0 } else null)
            ?: (pool.clips.indices.maxByOrNull { openerScore(pool.clips[it], visualMap) } ?: 0)
        val titleIndex = (if (ownPool) pool.clips.indices.firstOrNull {
            it != preferredOpenerIndex && pool.clips[it].role == MontageGraph.ShotRole.CLOSE
        } else null)
            ?: (pool.clips.indices.filter { it != preferredOpenerIndex }
                .maxByOrNull { titleScore(pool.clips[it], visualMap) }
                ?: (if (preferredOpenerIndex == 0) 1 else 0))
        val (openerIndex, openerClip) = if (ownPool) {
            preferredOpenerIndex to pool.clips[preferredOpenerIndex]
        } else selectOpener(pool, titleIndex, preferredOpenerIndex, visualMap)
        val remaining = pool.clips.indices.filter { it != openerIndex && it != titleIndex }
        val available = remaining.flatMap { index -> withoutInterval(pool.clips[index], openerClip) }
            .filter { it.sourceEndMs - it.sourceStartMs >= MIN_SOURCE_MOMENT_MS }
        val exact = pool.clips.size >= 16 && available.size <= CASCADE_SCENES &&
            available.sumOf { it.sourceEndMs - it.sourceStartMs } >= CASCADE_SCENES * 300L
        val sourceMoments = if (exact) partitionMoments(available, CASCADE_SCENES)
            else available.flatMap(::split).ifEmpty { adaptiveMoments(pool, openerIndex, titleIndex) }
        val orderedMoments = alternate(sourceMoments.map { describe(it, visualMap) })
        require(orderedMoments.isNotEmpty()) { "FEAR could not derive any cascade moments" }

        var adaptiveCursor = 0
        val clips = FearStrobeProfile.scenes.mapIndexed { index, scene ->
            val cascadeMoment = if (scene.role == FearStrobeProfile.SceneRole.CASCADE) {
                orderedMoments[adaptiveCursor++ % orderedMoments.size]
            } else null
            val selected = when (scene.role) {
                FearStrobeProfile.SceneRole.OPENER -> openerClip
                FearStrobeProfile.SceneRole.TITLE_CLOSE_UP -> pool.clips[titleIndex]
                FearStrobeProfile.SceneRole.CASCADE -> requireNotNull(cascadeMoment).clip
                FearStrobeProfile.SceneRole.BLACK_TAIL -> orderedMoments.last().clip
            }
            val durationMs = usToMs(scene.endUs) - usToMs(scene.startUs)
            val motion = when {
                scene.role == FearStrobeProfile.SceneRole.OPENER -> MontageGraph.Motion.PUSH_IN
                scene.role != FearStrobeProfile.SceneRole.CASCADE -> MontageGraph.Motion.HOLD
                index in ZOOM_PUNCH_SCENES -> MontageGraph.Motion.PUSH_IN
                index in PULL_OUT_SCENES -> MontageGraph.Motion.PUSH_OUT
                else -> MontageGraph.Motion.PUSH_IN
            }
            selected.copy(
                id = "fear-${scene.role.name.lowercase()}-$index",
                outputDurationMs = durationMs,
                transitionIn = if (index == 0) MontageGraph.Transition.OPEN else MontageGraph.Transition.HARD_CUT,
                motion = motion,
                transform = when (scene.role) {
                    FearStrobeProfile.SceneRole.OPENER -> openerZoomCue()
                    FearStrobeProfile.SceneRole.CASCADE -> cascadeMoment?.let { zoomCue(index, it) }
                        ?: MontageGraph.ClipTransform.hold()
                    else -> MontageGraph.ClipTransform.hold()
                },
                speedRamp = MontageGraph.SpeedRamp.constant(),
                beatAnchorMs = usToMs(scene.startUs),
                transitionDurationMs = null,
                exposureBias = selected.exposureBias + fearExposure(scene.role, selected, visualMap),
                redBias = selected.redBias + .018f,
                greenBias = selected.greenBias - .012f,
                blueBias = selected.blueBias + .028f
            )
        }
        val overlays = buildList {
            FearStrobeProfile.pulses.forEachIndexed { index, pulse ->
                val black = pulse.kind == FearStrobeProfile.PulseKind.BLACK
                add(MontageGraph.Overlay(
                    id = "fear-step-$index",
                    startMs = pulse.startUs / 1_000L,
                    endMs = pulse.endUs / 1_000L,
                    kind = "authored-measured-step",
                    blendMode = if (black) MontageGraph.BlendMode.MULTIPLY else MontageGraph.BlendMode.SCREEN,
                    overlayKind = if (black) MontageGraph.OverlayKind.BLACK_FADE else MontageGraph.OverlayKind.FLASH,
                    opacity = 1f,
                    red = if (black) 0f else 1f,
                    green = if (black) 0f else 1f,
                    blue = if (black) 0f else 1f
                ))
            }
        }
        val effects = GpuEffectGraph(buildList {
            listOf(1_066_667L, 2_733_333L).forEachIndexed { cue, start ->
                add(GpuEffectGraph.Node("fear-opener-split-$cue", GpuEffectGraph.Kind.GLITCH,
                    start, start + 166_667L, .17f))
                add(GpuEffectGraph.Node("fear-opener-lens-$cue", GpuEffectGraph.Kind.LENS_BLUR,
                    start, start + 166_667L, .06f))
            }
            FearStrobeProfile.scenes.forEachIndexed { index, scene ->
                if (index in STRONG_DEFOCUS_SCENES) {
                    add(GpuEffectGraph.Node("fear-defocus-$index", GpuEffectGraph.Kind.DEFOCUS_BLUR,
                        scene.startUs, minOf(scene.startUs + 140_000L, scene.endUs), .72f))
                }
                val cutAccent = index in ZOOM_PUNCH_SCENES || index == 1
                val entryAccent = index in STRONG_DEFOCUS_SCENES
                if (cutAccent || entryAccent) {
                    val start = if (entryAccent) scene.startUs else maxOf(scene.startUs, scene.endUs - 133_333L)
                    val end = if (entryAccent) minOf(scene.startUs + 133_333L, scene.endUs) else scene.endUs
                    add(GpuEffectGraph.Node("fear-split-$index", GpuEffectGraph.Kind.GLITCH,
                        start, end, if (entryAccent) .18f else .13f,
                        if (index % 2 == 0) 1f else -1f))
                    add(GpuEffectGraph.Node("fear-lens-$index", GpuEffectGraph.Kind.LENS_BLUR,
                        start, end, .06f))
                }
            }
        })
        val graph = MontageGraph(
            sourceDurationMs = pool.sourceDurationMs,
            outputDurationMs = FearStrobeProfile.OUTPUT_DURATION_US / 1_000L,
            clips = clips,
            audioTrack = MontageGraph.AudioTrack(audioSourceId),
            overlays = overlays,
            metadata = NleProjectMetadata(generator = "${FearStrobeProfile.ID}:${if (exact) "exact" else "adaptive"}"),
            parameterTracks = ParameterTrackFactory.fromLegacy(pool.copy(
                outputDurationMs = clips.sumOf { it.outputDurationMs }, clips = clips
            )),
            frameAttachments = pool.frameAttachments,
            effectGraph = effects
        )
        return BuildResult(graph, if (exact) CoverageMode.EXACT else CoverageMode.ADAPTIVE)
    }

    private fun split(clip: MontageGraph.Clip): List<MontageGraph.Clip> {
        val middle = (clip.sourceStartMs + clip.sourceEndMs) / 2L
        if (middle <= clip.sourceStartMs || middle >= clip.sourceEndMs) return emptyList()
        return listOf(
            clip.copy(sourceEndMs = middle),
            clip.copy(sourceStartMs = middle)
        )
    }

    private fun selectOpener(pool: MontageGraph, titleIndex: Int, preferredIndex: Int,
        visual: VisualEventMap): Pair<Int, MontageGraph.Clip> {
        val targetMs = FearStrobeProfile.TITLE_START_US / 1_000L
        val fallback = preferredIndex to fullSpeedOpener(
            pool.clips[preferredIndex], pool.clips[titleIndex], pool.sourceDurationMs)
        if (pool.sourceDurationMs < targetMs || pool.clips.any { it.sourceIndex != 0 }) return fallback
        val latest = pool.sourceDurationMs - targetMs
        val candidates = ((0L..latest step 250L).toList() + latest).distinct()
        val title = pool.clips[titleIndex]
        val scored = candidates.asSequence().filter { candidate ->
            candidate + targetMs <= title.sourceStartMs || candidate >= title.sourceEndMs
        }.mapNotNull { candidate ->
            val observations = visual.observations.filter {
                it.sourceTimeUs / 1_000L in candidate until candidate + targetMs
            }
            if (observations.size < 4) return@mapNotNull null
            val motion = observations.map {
                (it.cameraMotion.magnitude + it.subjectMotion.magnitude).coerceIn(0f, 1.5f)
            }.sorted()
            val scales = observations.mapNotNull { it.composition?.subjectScale }
            val scaleRange = if (scales.size >= 2) scales.max() - scales.min() else 0f
            // Reserve clean close faces for the title and the first cascade beat.
            val closeFace = observations.maxOf {
                (it.face?.confidence ?: 0f) * (it.composition?.subjectScale ?: 0f) * it.visualQuality
            }
            val humanCoverage = observations.count {
                maxOf(it.face?.confidence ?: 0f, it.humanPresenceConfidence) >= .45f
            }.toFloat() / observations.size
            val score = motion.average().toFloat() * .30f + motion[motion.size / 4] * .30f +
                scaleRange * .20f + observations.maxOf { it.occlusionConfidence } * .10f +
                observations.map { it.visualQuality }.average().toFloat() * .10f - closeFace * .22f
            Triple(candidate, score, humanCoverage)
        }.toList()
        // A high-motion animal shot must not displace a sustained human portrait in FEAR's opener.
        val completePortraits = scored.filter { it.third >= .99f }
        val portraitCandidates = completePortraits.ifEmpty { scored.filter { it.third >= .75f } }
        val start = (portraitCandidates.ifEmpty { scored }).maxByOrNull { it.second }?.first
            ?: return fallback
        val centre = start + targetMs / 2L
        val index = pool.clips.indices.filter { it != titleIndex }.minByOrNull { candidate ->
            val clip = pool.clips[candidate]
            abs((clip.sourceStartMs + clip.sourceEndMs) / 2L - centre)
        } ?: preferredIndex
        return index to pool.clips[index].copy(sourceStartMs = start, sourceEndMs = start + targetMs)
    }

    private fun openerZoomCue(): MontageGraph.ClipTransform = MontageGraph.ClipTransform(listOf(
        MontageGraph.ClipTransform.Keyframe(0f, 1.015f),
        MontageGraph.ClipTransform.Keyframe(.28f, 1.025f),
        MontageGraph.ClipTransform.Keyframe(.34f, 1.060f),
        MontageGraph.ClipTransform.Keyframe(.42f, 1.035f),
        MontageGraph.ClipTransform.Keyframe(.75f, 1.045f),
        MontageGraph.ClipTransform.Keyframe(.82f, 1.075f),
        MontageGraph.ClipTransform.Keyframe(1f, 1.045f)
    ))

    private fun fullSpeedOpener(selected: MontageGraph.Clip, title: MontageGraph.Clip,
        sourceDurationMs: Long): MontageGraph.Clip {
        val targetMs = FearStrobeProfile.TITLE_START_US / 1_000L
        if (selected.sourceEndMs - selected.sourceStartMs >= targetMs || sourceDurationMs < targetMs)
            return selected
        val earliest = (selected.sourceEndMs - targetMs).coerceAtLeast(0L)
        val latest = selected.sourceStartMs.coerceAtMost(sourceDurationMs - targetMs)
        val centred = ((selected.sourceStartMs + selected.sourceEndMs - targetMs) / 2L)
            .coerceIn(earliest, latest)
        val candidates = listOf(earliest, latest, centred,
            (title.sourceStartMs - targetMs).coerceIn(earliest, latest),
            title.sourceEndMs.coerceIn(earliest, latest)).distinct()
        val start = candidates.minWithOrNull(compareBy<Long> { candidate ->
            (minOf(candidate + targetMs, title.sourceEndMs) -
                maxOf(candidate, title.sourceStartMs)).coerceAtLeast(0L)
        }.thenBy { abs(it - centred) }) ?: centred
        return selected.copy(sourceStartMs = start, sourceEndMs = start + targetMs)
    }

    private fun withoutInterval(clip: MontageGraph.Clip, reserved: MontageGraph.Clip): List<MontageGraph.Clip> {
        if (clip.sourceIndex != reserved.sourceIndex || clip.sourceEndMs <= reserved.sourceStartMs ||
            clip.sourceStartMs >= reserved.sourceEndMs) return listOf(clip)
        return buildList {
            if (clip.sourceStartMs < reserved.sourceStartMs)
                add(clip.copy(sourceEndMs = reserved.sourceStartMs))
            if (clip.sourceEndMs > reserved.sourceEndMs)
                add(clip.copy(sourceStartMs = reserved.sourceEndMs))
        }
    }

    private fun partitionMoments(available: List<MontageGraph.Clip>, count: Int): List<MontageGraph.Clip> {
        val pieces = IntArray(available.size) { 1 }
        repeat(count - available.size) {
            val next = available.indices.maxByOrNull { index ->
                (available[index].sourceEndMs - available[index].sourceStartMs).toFloat() / pieces[index]
            } ?: return@repeat
            pieces[next]++
        }
        return available.flatMapIndexed { index, clip ->
            val duration = clip.sourceEndMs - clip.sourceStartMs
            (0 until pieces[index]).map { part ->
                clip.copy(
                    sourceStartMs = clip.sourceStartMs + duration * part / pieces[index],
                    sourceEndMs = clip.sourceStartMs + duration * (part + 1) / pieces[index]
                )
            }
        }
    }

    private fun adaptiveMoments(pool: MontageGraph, openerIndex: Int, titleIndex: Int): List<MontageGraph.Clip> =
        pool.clips.indices.filter { it != openerIndex && it != titleIndex }.flatMap { split(pool.clips[it]) }
            .ifEmpty { pool.clips.filterIndexed { index, _ -> index != openerIndex } }

    private fun describe(clip: MontageGraph.Clip, visual: VisualEventMap): Moment {
        val samples = samples(clip, visual)
        val scale = samples.mapNotNull { it.composition?.subjectScale }.median(.5f)
        // Keep signed pose channels separate. Collapsing them to an absolute "expression" score
        // made opposite head turns look identical and allowed A/B/A runs in the portrait cascade.
        val yaw = samples.mapNotNull { it.face?.yawDegrees }.median(0f)
        val pitch = samples.mapNotNull { it.face?.pitchDegrees }.median(0f)
        val gestures = samples.filter { it.gestureEvidenceAvailable }.map { it.gestureConfidence }
        val gesture = gestures.takeIf { it.isNotEmpty() }?.median(0f)
        val motion = samples.map { it.cameraMotion.magnitude + it.subjectMotion.magnitude }.median(0f)
        val radialMotion = samples.map { it.cameraMotion.radial + it.subjectMotion.radial }.median(0f)
        val faceConfidence = samples.mapNotNull { it.face?.confidence }.median(0f)
        val quality = samples.map { it.visualQuality }.median(0f)
        return Moment(clip, scale, yaw, pitch, gesture, motion, radialMotion, faceConfidence, quality)
    }

    /**
     * Starts the cascade on a clean face, then maximises distance from the recent three moments.
     * The short memory prevents visually repetitive A/B/A ping-pong while preserving the authored
     * cut clock and the source windows selected by the event pipeline.
     */
    private fun alternate(input: List<Moment>): List<Moment> {
        if (input.size < 2) return input
        val remaining = input.toMutableList()
        val result = mutableListOf(remaining.removeAt(remaining.indices.maxByOrNull {
            val moment = remaining[it]
            moment.quality * .35f + moment.faceConfidence * .30f + moment.scale * .20f +
                (moment.gesture ?: 0f) * .10f + moment.motion.coerceIn(0f, 1f) * .05f
        } ?: 0))
        while (remaining.isNotEmpty()) {
            val index = remaining.indices.maxByOrNull {
                val candidate = remaining[it]
                val recent = result.takeLast(3)
                val adjacentSeparation = momentDistance(candidate, result.last())
                val recentSeparation = recent.minOf { previous -> momentDistance(candidate, previous) }
                adjacentSeparation * .78f + recentSeparation * .22f +
                    candidate.quality * .04f + candidate.faceConfidence * .03f
            } ?: 0
            result += remaining.removeAt(index)
        }
        return result
    }

    private fun momentDistance(left: Moment, right: Moment): Float =
        abs(left.scale - right.scale) * .46f +
            (abs(left.yaw - right.yaw) / 90f).coerceIn(0f, 1f) * .20f +
            (abs(left.pitch - right.pitch) / 70f).coerceIn(0f, 1f) * .06f +
            (if (left.gesture != null && right.gesture != null)
                abs(left.gesture - right.gesture) * .10f else 0f) +
            abs(left.motion - right.motion).coerceIn(0f, 1f) * .18f

    private fun openerScore(clip: MontageGraph.Clip, visual: VisualEventMap): Float = samples(clip, visual)
        .maxOfOrNull { it.occlusionConfidence * .65f + it.cameraMotion.magnitude * .2f +
            it.subjectMotion.magnitude * .15f } ?: 0f

    private fun titleScore(clip: MontageGraph.Clip, visual: VisualEventMap): Float = samples(clip, visual)
        .map { (it.face?.confidence ?: 0f) * .35f +
            (it.composition?.subjectScale ?: 0f) * .25f +
            (it.composition?.quality ?: 0f) * .25f + it.visualQuality * .15f -
            (it.cameraMotion.magnitude + it.subjectMotion.magnitude) * .05f }
        .average().takeIf { !it.isNaN() }?.toFloat() ?: 0f

    private fun fearExposure(role: FearStrobeProfile.SceneRole, clip: MontageGraph.Clip,
        visual: VisualEventMap): Float {
        if (role == FearStrobeProfile.SceneRole.BLACK_TAIL) return 0f
        val observed = samples(clip, visual)
        if (observed.isEmpty()) return 0f
        val sourceLuma = observed.map { it.meanLuma }.median(.5f)
        val target = when (role) {
            FearStrobeProfile.SceneRole.OPENER -> .42f
            FearStrobeProfile.SceneRole.TITLE_CLOSE_UP -> .40f
            else -> .38f
        }
        val floor = if (role == FearStrobeProfile.SceneRole.OPENER) -.12f else -.24f
        return (target / sourceLuma.coerceAtLeast(.15f) - 1f).coerceIn(floor, 0f)
    }

    private fun samples(clip: MontageGraph.Clip, visual: VisualEventMap) = visual.observations.filter {
        it.sourceTimeUs / 1_000L in clip.sourceStartMs until clip.sourceEndMs
    }

    private fun List<Float>.median(fallback: Float): Float =
        sorted().let { if (it.isEmpty()) fallback else it[it.size / 2] }

    private fun zoomCue(index: Int, moment: Moment): MontageGraph.ClipTransform {
        // A shot already moving strongly toward/away from camera needs less synthetic scale.
        val nativeScaleMotion = abs(moment.radialMotion).coerceIn(0f, 1f)
        val quietFactor = (1f - nativeScaleMotion * .65f).coerceIn(.35f, 1f)
        val start = 1.025f
        val drift = .014f * quietFactor
        val punch = if (index in ZOOM_PUNCH_SCENES) {
            val framingFactor = if (moment.scale > .85f) .76f else 1f
            .080f * quietFactor * framingFactor
        } else 0f
        val keyframes = if (index in PULL_OUT_SCENES && punch == 0f) listOf(
            MontageGraph.ClipTransform.Keyframe(0f, start + drift),
            MontageGraph.ClipTransform.Keyframe(1f, start)
        ) else if (punch > 0f) listOf(
            MontageGraph.ClipTransform.Keyframe(0f, start),
            MontageGraph.ClipTransform.Keyframe(.62f, start + drift),
            MontageGraph.ClipTransform.Keyframe(.72f, start + drift),
            MontageGraph.ClipTransform.Keyframe(1f, start + drift + punch)
        ) else listOf(
            MontageGraph.ClipTransform.Keyframe(0f, start),
            MontageGraph.ClipTransform.Keyframe(1f, start + drift)
        )
        return MontageGraph.ClipTransform(keyframes)
    }

    private fun usToMs(us: Long): Long = us / 1_000L
    private const val CASCADE_SCENES = 27
    private const val MIN_SOURCE_MOMENT_MS = 150L
    private val STRONG_DEFOCUS_SCENES = setOf(2, 15, 25)
    private val ZOOM_PUNCH_SCENES = setOf(7, 9, 10, 17, 22, 23)
    private val PULL_OUT_SCENES = setOf(2, 5, 11, 15, 19, 24)
}
