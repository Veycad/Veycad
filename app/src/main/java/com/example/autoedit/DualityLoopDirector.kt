package com.veycad.app

import kotlin.math.abs

/** Directs a repeated shot-language phrase, choosing either source for each montage role. */
internal object DualityLoopDirector {
    private data class Selection(
        val startMs: Long,
        val endMs: Long,
        val score: Float,
        val observations: List<VisualEventMap.Observation>,
        val motionContext: List<VisualEventMap.Observation>
    ) {
        val meanLuma: Float get() = observations.map { it.meanLuma }.average().toFloat()
    }

    private data class Accent(val sourceFraction: Float, val strength: Float)

    fun build(
        primary: VisualEventMap,
        secondary: VisualEventMap,
        audioSourceId: String,
        audioMap: AudioBeatMap? = null
    ): MontageGraph {
        require(audioSourceId.isNotBlank())
        val sources = listOf(primary, secondary)
        val musicalAccentsUs = audioMap?.let { map ->
            (map.beats.filter { it.strength >= .35f }.map { map.timestampUs(it.sampleIndex) } +
                map.onsets.filter { it.strength >= .35f }.map { map.timestampUs(it.sampleIndex) }).distinct()
        }.orEmpty()
        require(sources.all {
            it.durationUs / 1_000L >= DualityLoopProfile.MINIMUM_SOURCE_DURATION_MS
        }) { "Each DUALITY source must provide at least ${DualityLoopProfile.MINIMUM_SOURCE_DURATION_MS} ms" }

        val used = List(2) { mutableListOf<LongRange>() }
        val counts = IntArray(2)
        var previousSource = -1
        var sourceRun = 0
        // Reprise the framing/movement phrase, not the same physical source windows.
        val phrase = listOf(MontageGraph.ShotRole.ESTABLISHING, MontageGraph.ShotRole.ACTION,
            MontageGraph.ShotRole.DETAIL, MontageGraph.ShotRole.CLOSE, MontageGraph.ShotRole.ACTION,
            MontageGraph.ShotRole.ESTABLISHING, MontageGraph.ShotRole.DETAIL,
            MontageGraph.ShotRole.ACTION, MontageGraph.ShotRole.CLOSE,
            MontageGraph.ShotRole.ESTABLISHING, MontageGraph.ShotRole.ACTION, MontageGraph.ShotRole.CLOSE)
        // Perception reports subject occupancy, not a universal cinematic shot-size label.
        // Calibrate across both inputs so a tilted/partly detected portrait isn't automatically wide.
        val scales = sources.flatMap { it.observations }.filter { hasHuman(it) }
            .mapNotNull { it.composition?.subjectScale }.sorted()
        fun scaleAt(fraction: Float, fallback: Float) = if (scales.isEmpty()) fallback else
            scales[((scales.size - 1) * fraction).toInt()]
        val targetLuma = sources.flatMap { it.observations }.map { it.meanLuma }.sorted().let {
            it[it.size / 2].coerceIn(.22f, .42f)
        }
        var previous: Selection? = null
        val accents = mutableListOf<Pair<Int, Accent>>()
        fun roleAt(index: Int) = when {
            index == 0 -> MontageGraph.ShotRole.OPENING
            index == DualityLoopProfile.boundariesMs.lastIndex - 1 -> MontageGraph.ShotRole.FINALE
            else -> phrase[(index - 1) % phrase.size]
        }
        fun scaleTargetFor(role: MontageGraph.ShotRole) = when (role) {
            MontageGraph.ShotRole.ESTABLISHING -> scaleAt(.10f, .30f)
            MontageGraph.ShotRole.CLOSE -> scaleAt(.85f, .75f)
            MontageGraph.ShotRole.OPENING -> scaleAt(.35f, .42f)
            else -> scaleAt(.50f, .52f)
        }
        val clips = DualityLoopProfile.boundariesMs.zipWithNext().mapIndexed { index, (start, end) ->
            val durationMs = end - start
            val role = roleAt(index)
            val scaleTarget = scaleTargetFor(role)
            val remaining = DualityLoopProfile.boundariesMs.size - 1 - index
            val eligible = sources.indices.filter { source ->
                val nextRun = if (source == previousSource) sourceRun + 1 else 1
                val future = remaining - 1
                nextRun <= 3 && sources.indices.all { other ->
                    val nextCount = counts[other] + if (other == source) 1 else 0
                    val carriedRun = if (other == source) nextRun else 0
                    // Reserve enough future slots without forcing an eight-shot tail from B.
                    val maximumFuture = future - (future + carriedRun) / 4
                    nextCount + maximumFuture >= 8
                }
            }
            val choices = eligible.map { source ->
                source to chooseWindow(sources[source], durationMs, role, scaleTarget, used[source], previous,
                    source == previousSource)
            }
            val (sourceIndex, selection) = choices.maxBy { (source, candidate) ->
                // Don't spend a third slot on a marginal action if it blocks the only good next role.
                val blockedRole = if (source == previousSource && sourceRun == 2 && remaining > 1) {
                    val nextRole = roleAt(index + 1)
                    val nextDuration = DualityLoopProfile.boundariesMs[index + 2] - end
                    val available = sources.indices.map { nextSource ->
                        chooseWindow(sources[nextSource], nextDuration, nextRole, scaleTargetFor(nextRole),
                            used[nextSource] + if (nextSource == source) listOf(candidate.startMs until candidate.endMs) else emptyList(),
                            candidate, nextSource == source).score
                    }
                    (available[source] - available[1 - source]).coerceAtLeast(0f) * .75f
                } else 0f
                candidate.score + (counts.max() - counts[source]) * .035f -
                    (if (source == previousSource) sourceRun * .025f else 0f) - blockedRole
            }
            counts[sourceIndex]++
            sourceRun = if (sourceIndex == previousSource) sourceRun + 1 else 1
            previousSource = sourceIndex
            used[sourceIndex] += selection.startMs until selection.endMs
            previous = selection
            val accent = if (role == MontageGraph.ShotRole.OPENING) null else accentFor(selection)
            val ramp = accent?.let { rampFor(it) } ?: MontageGraph.SpeedRamp.constant()
            if (accent != null) accents += index to accent
            val motion = when {
                role == MontageGraph.ShotRole.OPENING -> MontageGraph.Motion.PUSH_IN
                role == MontageGraph.ShotRole.FINALE -> MontageGraph.Motion.PUSH_OUT
                index % 4 == 1 -> MontageGraph.Motion.PUSH_IN
                index % 4 == 3 -> MontageGraph.Motion.PUSH_OUT
                else -> MontageGraph.Motion.HOLD
            }
            MontageGraph.Clip(
                id = "duality-$index-source-$sourceIndex",
                sourceStartMs = selection.startMs,
                sourceEndMs = selection.endMs,
                outputDurationMs = durationMs,
                role = role,
                transitionIn = if (index == 0) MontageGraph.Transition.OPEN else MontageGraph.Transition.HARD_CUT,
                motion = motion,
                confidence = selection.score.coerceIn(.05f, 1f),
                beatAnchorMs = start,
                transform = when (motion) {
                    MontageGraph.Motion.PUSH_IN -> zoom(.98f, 1.045f)
                    MontageGraph.Motion.PUSH_OUT -> zoom(1.04f, .99f)
                    else -> MontageGraph.ClipTransform.hold()
                },
                speedRamp = ramp,
                exposureBias = exposureFor(selection.meanLuma, targetLuma),
                sourceIndex = sourceIndex
            )
        }
        val effects = GpuEffectGraph(buildList {
            // Rank real movement accents, not every nth cut. Leave clean shots between clusters.
            val occupied = mutableListOf<Long>()
            accents.filter { (index, _) -> clips[index].role == MontageGraph.ShotRole.ACTION }
                .sortedByDescending { it.second.strength }.forEach { (index, accent) ->
                    val clip = clips[index]
                    val slowAt = clip.speedRamp.keyframes.minBy { it.speed }.at
                    val motionPeakUs = (DualityLoopProfile.boundariesMs[index] * 1_000L +
                        clip.outputDurationMs * 1_000L * slowAt).toLong()
                    // Snap only a nearby audible accent; never relocate a gesture to an unrelated beat.
                    val peakUs = musicalAccentsUs.filter {
                        abs(it - motionPeakUs) <= 85_000L &&
                            it >= DualityLoopProfile.boundariesMs[index] * 1_000L + 160_000L &&
                            it <= DualityLoopProfile.boundariesMs[index + 1] * 1_000L - 100_000L
                    }.minByOrNull { abs(it - motionPeakUs) } ?: motionPeakUs
                    if (occupied.size < 6 && occupied.none { abs(it - peakUs) < 850_000L }) {
                        occupied += peakUs
                        val startUs = (peakUs - 160_000L).coerceAtLeast(
                            DualityLoopProfile.boundariesMs[index] * 1_000L)
                        add(GpuEffectGraph.Node("duality-motion-soft-$index", GpuEffectGraph.Kind.DEFOCUS_BLUR,
                            startUs, peakUs, .08f + accent.strength * .10f))
                        add(GpuEffectGraph.Node("duality-motion-lens-$index", GpuEffectGraph.Kind.LENS_BLUR,
                            startUs, peakUs + 50_000L, .12f + accent.strength * .12f))
                        add(GpuEffectGraph.Node("duality-motion-light-$index", GpuEffectGraph.Kind.GLOW,
                            peakUs - 80_000L, peakUs + 80_000L, .18f + accent.strength * .12f))
                    }
                }
        }.sortedBy { it.startUs })
        val base = MontageGraph(
            sourceDurationMs = sources.maxOf { it.durationUs / 1_000L },
            outputDurationMs = DualityLoopProfile.OUTPUT_DURATION_US / 1_000L,
            clips = clips,
            audioTrack = MontageGraph.AudioTrack(audioSourceId),
            metadata = NleProjectMetadata(generator = DualityLoopProfile.ID),
            effectGraph = effects
        )
        return base.copy(parameterTracks = ParameterTrackFactory.fromLegacy(base))
    }

    private fun chooseWindow(
        source: VisualEventMap,
        outputDurationMs: Long,
        role: MontageGraph.ShotRole,
        scaleTarget: Float,
        used: List<LongRange>,
        previous: Selection?,
        sameSource: Boolean
    ): Selection {
        val ratios = if (role == MontageGraph.ShotRole.OPENING) listOf(1f) else listOf(1f, .90f, 1.15f)
        return ratios.map { ratio ->
            val selection = selectWindow(source, (outputDurationMs * ratio).toLong(), role,
                scaleTarget, used, previous, sameSource)
            val accent = accentFor(selection)
            val desired = when {
                accent == null -> 1f
                role == MontageGraph.ShotRole.ACTION -> 1.15f
                role == MontageGraph.ShotRole.CLOSE || role == MontageGraph.ShotRole.DETAIL -> .90f
                else -> 1f
            }
            selection.copy(score = selection.score - abs(ratio - desired) * .45f)
        }.maxBy { it.score }
    }

    /** Reject a held expression and isolated/opposing flow; retain an interior, readable change. */
    private fun accentFor(selection: Selection): Accent? {
        val coherent = coherentMotion(selection.motionContext).magnitude.coerceIn(0f, 1f)
        return selection.motionContext.zipWithNext().mapNotNull { (before, now) ->
            val fraction = (now.sourceTimeUs / 1_000f - selection.startMs) /
                (selection.endMs - selection.startMs)
            if (fraction !in .22f.. .82f || now.occlusionConfidence > .65f || now.visualQuality < .55f) {
                return@mapNotNull null
            }
            val turn = if (before.face != null && now.face != null) {
                (abs(now.face.yawDegrees - before.face.yawDegrees) / 25f).coerceIn(0f, 1f) *
                    minOf(before.face.confidence, now.face.confidence)
            } else 0f
            val gestureChange = if (now.gestureEvidenceAvailable && before.gestureEvidenceAvailable)
                abs(now.gestureConfidence - before.gestureConfidence) else 0f
            val strength = maxOf(turn, coherent, gestureChange * .65f)
            if (strength < .22f) null else Accent(fraction, strength)
        }.maxByOrNull { it.strength }
    }

    private fun rampFor(accent: Accent): MontageGraph.SpeedRamp {
        val smooth = MontageGraph.SpeedRamp.CubicBezier(.35f, 0f, .65f, 1f)
        val fast = 1.28f + accent.strength * .28f
        val slow = .82f - accent.strength * .08f
        fun curve(at: Float): MontageGraph.SpeedRamp {
            val ramp = MontageGraph.SpeedRamp(listOf(
                MontageGraph.SpeedRamp.Keyframe(0f, 1f, smooth),
                MontageGraph.SpeedRamp.Keyframe(at * .30f, fast, smooth),
                MontageGraph.SpeedRamp.Keyframe(at, slow, smooth),
                MontageGraph.SpeedRamp.Keyframe((at + .14f).coerceAtMost(.90f), slow, smooth),
                MontageGraph.SpeedRamp.Keyframe(1f, 1f, smooth)
            ))
            // Limit severe stepping before solving the event position, so the constrained
            // curve, not an unconstrained intermediate, maps the source gesture to slow time.
            val mean = (0 until 80).map { ramp.speedAt((it + .5f) / 80f) }.average().toFloat()
            if (slow / mean >= .80f) return ramp
            val blend = (.20f * mean / (mean - slow)).coerceIn(0f, 1f)
            return ramp.copy(keyframes = ramp.keyframes.map {
                it.copy(speed = mean + (it.speed - mean) * blend)
            })
        }
        // Invert the renderer's integrated/normalised mapping. The actual source gesture,
        // rather than a decorative speed key, must land in the slow portion of the output.
        var low = .18f
        var high = .78f
        repeat(18) {
            val at = (low + high) * .5f
            if (curve(at).sourceFractionAt(at) < accent.sourceFraction) low = at else high = at
        }
        return curve((low + high) * .5f)
    }

    private fun selectWindow(
        source: VisualEventMap,
        durationMs: Long,
        role: MontageGraph.ShotRole,
        scaleTarget: Float,
        used: List<LongRange>,
        previous: Selection?,
        sameSource: Boolean
    ): Selection {
        val sourceDurationMs = source.durationUs / 1_000L - DualityLoopProfile.SOURCE_END_GUARD_MS
        val starts = buildList {
            add(0L)
            add(sourceDurationMs - durationMs)
            source.observations.forEach {
                val at = it.sourceTimeUs / 1_000L
                add(at)
                add(at - durationMs / 2L)
                add(at - durationMs * 2L / 3L)
                add(at - durationMs)
            }
            source.events.forEach {
                add(it.usableWindow.startUs / 1_000L)
                add(it.usableWindow.endUs / 1_000L - durationMs)
            }
        }.map { it.coerceIn(0L, sourceDurationMs - durationMs) }.distinct()
        val candidates = starts.map { start ->
            val end = start + durationMs
            val observations = source.observations.filter {
                it.sourceTimeUs >= start * 1_000L && it.sourceTimeUs < end * 1_000L
            }.ifEmpty {
                listOf(source.observations.minBy { abs(it.sourceTimeUs / 1_000L - (start + end) / 2L) })
            }
            val semantic = observations.map { observation ->
                semanticScore(observation, role, scaleTarget)
            }.average().toFloat()
            val readability = observations.minOf { it.visualQuality } * .12f -
                observations.count { it.occlusionConfidence > .65f }.toFloat() / observations.size * .18f
            val openingInstability = if (role == MontageGraph.ShotRole.OPENING) {
                val motion = observations.filter { it.sourceTimeUs > start * 1_000L }.map {
                    (it.cameraMotion.magnitude + it.subjectMotion.magnitude).coerceIn(0f, 1f)
                }.ifEmpty { listOf(0f) }
                val scales = observations.mapNotNull { it.composition?.subjectScale }
                val yaws = observations.mapNotNull { it.face?.yawDegrees }
                val scaleChange = if (scales.isEmpty()) 0f else scales.max() - scales.min()
                val yawChange = if (yaws.isEmpty()) 0f else ((yaws.max() - yaws.min()) / 60f).coerceIn(0f, 1f)
                motion.average().toFloat() * .25f + motion.max() * .20f +
                    scaleChange * .45f + yawChange * .18f +
                    (observations.zipWithNext().maxOfOrNull { (left, right) ->
                        abs(left.meanLuma - right.meanLuma)
                    } ?: 0f) * .20f
            } else 0f
            // Short clips often contain only two 250 ms samples. One adjacent sample supports
            // flow at the cut without changing the selected footage or its visual-role score.
            val motionContext = source.observations.filter {
                it.sourceTimeUs >= (start - 250L).coerceAtLeast(0L) * 1_000L &&
                    it.sourceTimeUs < (end + 250L) * 1_000L
            }.ifEmpty { observations }
            val pair = previous?.let { pairScore(it, observations, motionContext, role) } ?: 0f
            val coherent = coherentMotion(observations).magnitude.coerceIn(0f, 1f)
            val yaws = observations.mapNotNull { it.face?.yawDegrees }
            val turn = if (yaws.size < 2) 0f else
                (abs(yaws.last() - yaws.first()) / 25f).coerceIn(0f, 1f)
            val presentationRepeat = if (sameSource && previous != null) {
                val leftScale = previous.observations.mapNotNull { it.composition?.subjectScale }.average()
                val rightScale = observations.mapNotNull { it.composition?.subjectScale }.average()
                val leftYaw = previous.observations.mapNotNull { it.face?.yawDegrees }.average()
                val rightYaw = observations.mapNotNull { it.face?.yawDegrees }.average()
                if (leftScale.isFinite() && rightScale.isFinite() && leftYaw.isFinite() && rightYaw.isFinite()) {
                    (1f - (abs(leftScale - rightScale) / .18).toFloat()).coerceIn(0f, 1f) *
                        (1f - (abs(leftYaw - rightYaw) / 25).toFloat()).coerceIn(0f, 1f) * .18f *
                        (1f - maxOf((coherent / .35f).coerceIn(0f, 1f), turn))
                } else 0f
            } else 0f
            // A held grimace can produce local flow without a real turn or coherent body/camera move.
            val actionProgress = if (role == MontageGraph.ShotRole.ACTION) {
                coherent * .18f + turn * .18f
            } else 0f
            val dominant = coherentMotion(observations)
            val reversal = if (dominant.magnitude > .10f) observations.count {
                val vector = if (it.subjectMotion.magnitude > it.cameraMotion.magnitude)
                    it.subjectMotion else it.cameraMotion
                vector.magnitude > .10f && vector.direction() != dominant.direction()
            }.toFloat() / observations.size * .12f else 0f
            val actionBoundary = source.events.filter {
                it.type in setOf(VisualEventMap.EventType.GESTURE, VisualEventMap.EventType.FACE_TURN,
                    VisualEventMap.EventType.SUBJECT_MOVE)
            }.maxOfOrNull {
                val at = it.peakTimeUs / 1_000L
                if (at in start until end && minOf(at - start, end - at) < 120L) {
                    it.strength * it.confidence * .22f
                } else 0f
            } ?: 0f
            val sourceCut = observations.filter { it.sourceTimeUs > start * 1_000L }
                .maxOfOrNull { it.sceneChangeConfidence } ?: 0f
            val overlap = used.sumOf { range ->
                (minOf(end - 1L, range.last) - maxOf(start, range.first) + 1L).coerceAtLeast(0L)
            }.toFloat() / durationMs
            Selection(start, end, semantic + readability + pair + actionProgress - presentationRepeat - reversal - openingInstability - actionBoundary -
                sourceCut * .55f -
                overlap.coerceAtMost(1f) * .90f, observations, motionContext)
        }
        // Exact repeats are a last resort, not a way to keep winning with one high-score frame.
        val fresh = candidates.filter { candidate ->
            used.none { it.first == candidate.startMs && it.last == candidate.endMs - 1L }
        }
        val distinct = fresh.ifEmpty { candidates }
        val humanWindows = distinct.filter { candidate ->
            candidate.observations.count { hasHuman(it) }.toFloat() / candidate.observations.size >= .50f
        }
        // Presence is a suitability gate, not a reward for the most confident frontal face.
        val pool = humanWindows.ifEmpty { distinct }
        val uninterrupted = if (role == MontageGraph.ShotRole.OPENING) pool.filter {
            it.observations.none { observation ->
                observation.sourceTimeUs > it.startMs * 1_000L && observation.sceneChangeConfidence >= .50f
            }
        } else pool
        return uninterrupted.ifEmpty { pool }.maxBy { it.score }
    }

    private fun hasHuman(observation: VisualEventMap.Observation): Boolean =
        observation.humanPresenceConfidence >= .45f ||
            (observation.face?.confidence ?: 0f) >= .65f ||
            (observation.gestureEvidenceAvailable && observation.gestureConfidence >= .45f)

    private fun semanticScore(observation: VisualEventMap.Observation, role: MontageGraph.ShotRole, scaleTarget: Float): Float {
        val motion = (observation.cameraMotion.magnitude + observation.subjectMotion.magnitude)
            .coerceIn(0f, 1f)
        val face = observation.face?.confidence ?: 0f
        val composition = observation.composition?.quality ?: 0f
        val scale = observation.composition?.subjectScale ?: .5f
        val scaleFit = (1f - abs(scale - scaleTarget) / .30f).coerceIn(0f, 1f)
        val yaw = abs(observation.face?.yawDegrees ?: 0f)
        val profile = (1f - abs(yaw - 35f) / 35f).coerceIn(0f, 1f)
        val roll = abs(observation.face?.rollDegrees ?: 0f)
        val gesture = observation.gestureConfidence.takeIf { observation.gestureEvidenceAvailable } ?: 0f
        return when (role) {
            MontageGraph.ShotRole.OPENING ->
                face * .12f + composition * .15f + observation.visualQuality * .20f +
                    (1f - motion) * .25f + scaleFit * .23f +
                    (1f - (yaw / 40f).coerceIn(0f, 1f)) * .05f -
                    (roll / 35f).coerceIn(0f, 1f) * .20f - gesture * .12f
            MontageGraph.ShotRole.FINALE ->
                observation.visualQuality * .25f + motion * .45f + gesture * .30f
            MontageGraph.ShotRole.ACTION ->
                motion * .40f + gesture * .25f + observation.visualQuality * .20f +
                    observation.occlusionConfidence * .15f
            MontageGraph.ShotRole.CLOSE ->
                scaleFit * .45f + face * .15f + composition * .15f +
                    observation.visualQuality * .15f + (1f - motion) * .10f
            MontageGraph.ShotRole.ESTABLISHING ->
                scaleFit * .45f + composition * .15f + face * .10f +
                    observation.visualQuality * .15f + (1f - motion) * .15f -
                    (roll / 45f).coerceIn(0f, 1f) * .15f
            else ->
                profile * .40f + composition * .15f + observation.visualQuality * .20f +
                    scaleFit * .15f + motion * .10f
        }
    }

    /** Median signed flow resists a single codec/cut vector; opposing samples don't author a move. */
    private fun coherentMotion(observations: List<VisualEventMap.Observation>): VisualEventMap.Vector {
        fun median(values: List<Float>): Float {
            val sorted = values.sorted()
            return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) * .5f
        }
        fun flow(subject: Boolean): VisualEventMap.Vector {
            val vectors = observations.map { if (subject) it.subjectMotion else it.cameraMotion }
            val centre = VisualEventMap.Vector(median(vectors.map { it.x }), median(vectors.map { it.y }),
                median(vectors.map { it.radial }))
            val moving = vectors.filter { it.magnitude > .10f }
            val agreement = if (moving.isEmpty()) 1f else
                moving.count { it.direction() == centre.direction() }.toFloat() / moving.size
            val coherence = (agreement * 2f - 1f).coerceIn(0f, 1f)
            return VisualEventMap.Vector(centre.x * coherence, centre.y * coherence, centre.radial * coherence)
        }
        val camera = flow(false)
        val subject = flow(true)
        return if (subject.magnitude > camera.magnitude) subject else camera
    }

    private fun pairScore(
        outgoingWindow: Selection,
        incomingWindow: List<VisualEventMap.Observation>,
        incomingContext: List<VisualEventMap.Observation>,
        role: MontageGraph.ShotRole
    ): Float {
        val outgoing = outgoingWindow.observations.last()
        val incoming = incomingWindow.first()
        val left = coherentMotion(outgoingWindow.motionContext.takeLast(3))
        val right = coherentMotion(incomingContext.take(3))
        val direction = if (left.magnitude > .10f && right.magnitude > .10f) {
            if (left.direction() == right.direction()) .20f else -.14f
        } else 0f
        val scale = if (outgoing.composition != null && incoming.composition != null) {
            val change = abs(outgoing.composition.subjectScale - incoming.composition.subjectScale)
            val desired = if (role == MontageGraph.ShotRole.CLOSE || role == MontageGraph.ShotRole.ESTABLISHING) .24f else .10f
            (1f - abs(change - desired) / .45f).coerceIn(0f, 1f) * .14f
        } else 0f
        // Reward a readable light/dark counterpoint, but not a near-black/white discontinuity.
        val lightChange = abs(outgoing.meanLuma - incoming.meanLuma)
        val light = (1f - abs(lightChange - .16f) / .50f).coerceIn(0f, 1f) * .05f
        return direction + scale + light
    }

    private fun exposureFor(current: Float, target: Float): Float {
        val result = ((target / current.coerceAtLeast(.08f) - 1f) * .40f).coerceIn(-.18f, .12f)
        // The shader uses a multiplicative grade: never lower an already low-key source.
        return if (current < .18f) result.coerceAtLeast(0f) else result
    }

    private fun zoom(from: Float, to: Float) = MontageGraph.ClipTransform(listOf(
        MontageGraph.ClipTransform.Keyframe(0f, from),
        MontageGraph.ClipTransform.Keyframe(1f, to)
    ))
}
