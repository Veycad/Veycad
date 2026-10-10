package com.veycad.app

import kotlin.math.abs
import kotlin.math.pow

/** Production assembly of the measured Heartbeat phrase from selected source windows.
 * Source ranking stays local; authored live durations constrain handles and unavailable coverage
 * is rejected rather than slowed or padded.
 */
internal object HeartbeatDirector {
    /** Pool roles are ranked from the current source. Fixed numeric remaps tuned to an older
     * capture collapsed three distinct IMG_1647 moments into already-used portraits. */
    internal fun selectedPoolRole(authoredRole: Int): Int = authoredRole

    /** Paired experiment: only remove temporal lag, retaining the spatial shader. */
    fun synchronousEchoControl(graph: MontageGraph): MontageGraph {
        require(graph.metadata.generator.startsWith(HeartbeatMontageProfile.ID))
        return graph.copy(overlays = graph.overlays.map {
            if (it.kind == "heartbeat-echo-experimental") it.copy(secondarySourceOffsetMs = 0L) else it
        })
    }

    /** Effect-only control: retains selected footage, grade, clocks and attachments. */
    fun legacyEchoControl(graph: MontageGraph): MontageGraph {
        require(graph.metadata.generator.startsWith(HeartbeatMontageProfile.ID))
        return graph.copy(overlays = graph.overlays.map {
            if (it.kind == "heartbeat-echo-experimental") it.copy(kind = "reference-double-exposure") else it
        })
    }

    fun build(pool: MontageGraph, audioSourceId: String, echoOffsetMs: Long = -67L,
        allowShiftedHandles: Boolean = true, allowProfileGap: Boolean = true,
        visualMap: VisualEventMap? = null, checkCancelled: () -> Unit = {}): MontageGraph {
        checkCancelled()
        require(pool.clips.size >= 15) { "Heartbeat needs 15 selected source windows; coverage is insufficient" }
        val roles = pool.clips.take(REQUIRED_POOL_ROLES).toMutableList()
        val preferredRoleOrder = measuredCompositionRoleOrder(
            roles,
            pool.frameAttachments,
            visualMap
        )
        // Composition is a preference, not permission to independently grow unequal source
        // windows into each other. Reserve the final live handles together after that mapping.
        val reservations = reserveLiveRoles(pool, roles, preferredRoleOrder,
            allowShiftedHandles, allowProfileGap, echoOffsetMs, checkCancelled)
        val measuredRoleOrder = reservations.map { it.sourceRole }
        val occupied = reservations.map { reservation -> roles[reservation.sourceRole].copy(
            sourceStartMs = reservation.startMs, sourceEndMs = reservation.endMs) }
        val distinctInterludeRole = visualMap?.let { visual ->
            selectDistinctSubjectlessInterlude(pool, visual, occupied, echoOffsetMs, checkCancelled)?.let { interlude ->
                roles += interlude
                roles.lastIndex
            }
        }
        fun ms(us: Long) = (us + 500L) / 1_000L
        val scenes = HeartbeatMontageProfile.scenes
        // Rounding the 21.166667 s author endpoint up to 21.167 s admits an extra
        // 60 fps frame at 21.166667 in this half-open schedule.
        val outputDurationMs = HeartbeatMontageProfile.OUTPUT_DURATION_US / 1_000L
        val clips = scenes.mapIndexed { index, scene ->
            checkCancelled()
            // Animals remain valid standalone montage subjects. The authored role map retains
            // their strongest views while avoiding a long block of nearly identical couch shots.
            val selectedRole = if (index == scenes.lastIndex) {
                // Close the phrase on the verified readable human motif. Animal moments remain
                // in their authored middle/reprise slots; the reference finale returns to its hero.
                measuredRoleOrder[HeartbeatMontageProfile.FINALE_PORTRAIT_ROLE]
            } else if (scene.sourceRole == DISTINCT_INTERLUDE_AUTHORED_ROLE && distinctInterludeRole != null) {
                distinctInterludeRole
            } else measuredRoleOrder[selectedPoolRole(scene.sourceRole)]
            val selected = roles[selectedRole]
            val outputMs = ms(scene.endUs) - ms(scene.startUs)
            // Constant ramp describes the curve, not absolute playback speed.
            // Reusing a 1.5 s pool range for a .45 s beat slot accidentally played
            // it at 3.33x. Keep the selected moment's centre and trim its handles.
            val reserved = if (selectedRole == distinctInterludeRole) {
                LiveRoleReservation(selectedRole, selected.sourceStartMs, selected.sourceEndMs)
            } else reservations[if (index == scenes.lastIndex) {
                HeartbeatMontageProfile.FINALE_PORTRAIT_ROLE
            } else selectedPoolRole(scene.sourceRole)]
            val sourceStartMs = reserved.startMs + (reserved.endMs - reserved.startMs - outputMs) / 2L
            val lightAccent = HeartbeatMontageProfile.lightAccents.firstOrNull {
                it.startUs == scene.startUs && it.endUs == scene.endUs
            }
            selected.copy(
                id = "heartbeat-$index-role-$selectedRole",
                sourceStartMs = sourceStartMs,
                sourceEndMs = sourceStartMs + outputMs,
                outputDurationMs = outputMs,
                // Measured light pulses and defocus below own the transition accents.
                // Slot parity is not evidence for a whip, Sigma foreground stage or blackout.
                transitionIn = if (index == 0) MontageGraph.Transition.OPEN
                    else MontageGraph.Transition.HARD_CUT,
                beatAnchorMs = ms(scene.startUs),
                // Original Sigma envelopes are not musical evidence for Heartbeat.
                speedRamp = MontageGraph.SpeedRamp.constant(),
                transform = when {
                    lightAccent != null -> lightPortraitTransform(selected.copy(
                        sourceStartMs = sourceStartMs, sourceEndMs = sourceStartMs + outputMs),
                        pool.frameAttachments)
                    // The 6.167 s role and its reprise are the same complete backlit plate in the
                    // reference. Preserve role 8 and its framing; defocus/echo own the two accents.
                    else -> MontageGraph.ClipTransform.hold()
                },
                motion = MontageGraph.Motion.HOLD,
                exposureBias = selected.exposureBias +
                    if (index == 0) HeartbeatMontageProfile.OPENING_EXPOSURE_BIAS else 0f,
                transitionDurationMs = null
            )
        }.toMutableList()
        clips += clips.last().copy(id = "heartbeat-black-tail",
            outputDurationMs = outputDurationMs - ms(HeartbeatMontageProfile.LAST_VISUAL_END_US),
            transitionIn = MontageGraph.Transition.HARD_CUT,
            beatAnchorMs = ms(HeartbeatMontageProfile.LAST_VISUAL_END_US))
        val lightGradeTracks = HeartbeatMontageProfile.lightAccents.mapIndexed { index, accent ->
            val sceneIndex = scenes.indexOfFirst { it.startUs == accent.startUs && it.endUs == accent.endUs }
            require(sceneIndex >= 0) { "Heartbeat light accent must match a measured scene" }
            val clip = clips[sceneIndex]
            fun grade(exposure: Float) = listOf(
                clip.redBias, clip.greenBias, clip.blueBias, clip.exposureBias + exposure
            )
            ParameterTrack(
                id = "heartbeat-light-grade-$index",
                target = ParameterTargets.clip(clip.id, "grade.rgbaBias"),
                type = ParameterTrack.ValueType.VEC4,
                keyframes = listOf(
                    ParameterTrack.Keyframe(accent.startUs, grade(accent.entryExposure),
                        ParameterTrack.Interpolation.LINEAR),
                    ParameterTrack.Keyframe(accent.startUs + 100_000L, grade(accent.plateauExposure),
                        ParameterTrack.Interpolation.HOLD),
                    ParameterTrack.Keyframe(accent.endUs - 100_000L, grade(accent.plateauExposure),
                        ParameterTrack.Interpolation.LINEAR),
                    ParameterTrack.Keyframe(accent.endUs, grade(accent.exitExposure),
                        ParameterTrack.Interpolation.HOLD)
                )
            )
        }
        val sceneGradeTracks = scenes.mapIndexedNotNull { index, scene ->
            val clip = clips[index]
            val ownsDedicatedCurve = index == scenes.lastIndex ||
                HeartbeatMontageProfile.lightAccents.any {
                    it.startUs == scene.startUs && it.endUs == scene.endUs
                }
            if (ownsDedicatedCurve) return@mapIndexedNotNull null
            val sourceLuma = medianSourceLuma(visualMap, clip) ?: return@mapIndexedNotNull null
            val exposure = clip.exposureBias + exposureDeltaForTargetLuma(
                scene.targetLuma,
                sourceLuma,
                clip.exposureBias
            )
            val grade = listOf(clip.redBias, clip.greenBias, clip.blueBias, exposure)
            ParameterTrack(
                id = "heartbeat-scene-grade-$index",
                target = ParameterTargets.clip(clip.id, "grade.rgbaBias"),
                type = ParameterTrack.ValueType.VEC4,
                keyframes = listOf(
                    ParameterTrack.Keyframe(scene.startUs, grade, ParameterTrack.Interpolation.HOLD),
                    ParameterTrack.Keyframe(scene.endUs, grade, ParameterTrack.Interpolation.HOLD)
                )
            )
        }
        val finaleScene = scenes.last()
        val finaleClip = clips[scenes.lastIndex]
        fun finaleGrade(exposure: Float) = listOf(
            finaleClip.redBias,
            finaleClip.greenBias,
            finaleClip.blueBias,
            finaleClip.exposureBias + exposure
        )
        val finaleSourceLuma = medianSourceLuma(visualMap, finaleClip)
        // Invert the existing Heartbeat grade from the actual selected source plate. Fixed
        // deltas were calibrated for an older, darker wide shot and clipped the newly selected
        // close hero. The dark punctuation hides the deliberate drop into the final echo.
        val finaleKeyframes = if (finaleSourceLuma != null) {
            fun target(luma: Float) = finaleGrade(exposureDeltaForTargetLuma(
                luma, finaleSourceLuma, finaleClip.exposureBias
            ))
            listOf(
                ParameterTrack.Keyframe(finaleScene.startUs, target(FINALE_ENTRY_LUMA),
                    ParameterTrack.Interpolation.LINEAR),
                ParameterTrack.Keyframe(finaleScene.startUs + 250_000L, target(FINALE_PLATEAU_LUMA),
                    ParameterTrack.Interpolation.HOLD),
                ParameterTrack.Keyframe(HeartbeatMontageProfile.FINALE_ECHO_START_US - 16_667L,
                    target(FINALE_PLATEAU_LUMA), ParameterTrack.Interpolation.HOLD),
                ParameterTrack.Keyframe(HeartbeatMontageProfile.FINALE_ECHO_START_US,
                    target(FINALE_ECHO_ENTRY_LUMA), ParameterTrack.Interpolation.LINEAR),
                ParameterTrack.Keyframe(HeartbeatMontageProfile.FINALE_ECHO_END_US,
                    target(FINALE_ECHO_EXIT_LUMA), ParameterTrack.Interpolation.HOLD),
                ParameterTrack.Keyframe(finaleScene.endUs, target(FINALE_ECHO_EXIT_LUMA),
                    ParameterTrack.Interpolation.HOLD)
            )
        } else listOf(
            ParameterTrack.Keyframe(finaleScene.startUs, finaleGrade(.16f),
                ParameterTrack.Interpolation.LINEAR),
            ParameterTrack.Keyframe(finaleScene.startUs + 250_000L, finaleGrade(.85f),
                ParameterTrack.Interpolation.HOLD),
            ParameterTrack.Keyframe(finaleScene.endUs - 150_000L, finaleGrade(.72f),
                ParameterTrack.Interpolation.LINEAR),
            ParameterTrack.Keyframe(finaleScene.endUs, finaleGrade(.30f),
                ParameterTrack.Interpolation.HOLD)
        )
        val finaleGradeTrack = ParameterTrack(
            id = "heartbeat-finale-grade",
            target = ParameterTargets.clip(finaleClip.id, "grade.rgbaBias"),
            type = ParameterTrack.ValueType.VEC4,
            keyframes = finaleKeyframes
        )
        val overlays = authoredOverlays(echoOffsetMs)
        val graph = MontageGraph(pool.sourceDurationMs,outputDurationMs,
            clips = clips, audioTrack = MontageGraph.AudioTrack(audioSourceId),overlays = overlays,
            parameterTracks = sceneGradeTracks + lightGradeTracks + finaleGradeTrack,
            frameAttachments = pool.frameAttachments,
            effectGraph = GpuEffectGraph(scenes.drop(4).filterNot { it.echo }.mapIndexed { index,scene ->
                GpuEffectGraph.Node("heartbeat-defocus-$index",GpuEffectGraph.Kind.DEFOCUS_BLUR,
                    scene.startUs, minOf(scene.startUs + 250_000L, scene.endUs), .85f)
            }),
            metadata = NleProjectMetadata(generator = HeartbeatMontageProfile.ID + ":production"))
        // Use the real existing aggregate audit, not a looser per-pair surrogate. Deliberate
        // reprise and opaque tail remain excluded by that audit; its release threshold is intact.
        if (RenderedMp4Acceptance.repeatedSourceRatio(graph) > 0f) {
            throw MaterialRejectedException("insufficient_distinct_moments",
                "Heartbeat could not reserve distinct final live source intervals")
        }
        // Includes optional interlude substitution and the actual strongest sampled overlay.
        // This verifies requested PTS, not decoded evidence or artistic/temporal acceptance.
        if (HighQualityFramePlan.build(graph, HeartbeatMontageProfile.REFERENCE_FPS).frames.any { frame ->
                checkCancelled()
                frame.layer.heartbeatEcho &&
                    (frame.sourceTimeUs + frame.layer.secondarySourceOffsetMs * 1_000L)
                        .let { it < 0L || it >= graph.sourceDurationMs * 1_000L }
            }) {
            throw MaterialRejectedException("insufficient_distinct_moments",
                "Heartbeat needs source handles for its unclamped temporal echo")
        }
        return graph
    }

    /** Single authored layer definition shared by the final graph and source-handle planning. */
    private fun authoredOverlays(echoOffsetMs: Long): List<MontageGraph.Overlay> {
        fun ms(us: Long) = (us + 500L) / 1_000L
        val scenes = HeartbeatMontageProfile.scenes
        val outputDurationMs = HeartbeatMontageProfile.OUTPUT_DURATION_US / 1_000L
        return HeartbeatMontageProfile.pulses.mapIndexed { index, pulse ->
            // Layer sampling floors output PTS to milliseconds. Use that same clock;
            // rounding a 1766.667 ms start to 1767 would lose its only 60 fps frame.
            MontageGraph.Overlay("heartbeat-pulse-$index",pulse.startUs / 1_000L,pulse.endUs / 1_000L,
                "heartbeat-measured-step", overlayKind = if (pulse.kind == HeartbeatMontageProfile.PulseKind.WHITE)
                    MontageGraph.OverlayKind.FLASH else MontageGraph.OverlayKind.BLACK_FADE, opacity = 1f)
        } + scenes.filter { it.echo }.mapIndexed { index, scene ->
            MontageGraph.Overlay("heartbeat-echo-$index",ms(scene.startUs),ms(scene.endUs),
                "heartbeat-echo-experimental", overlayKind = MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
                opacity = .62f, secondarySourceOffsetMs = echoOffsetMs)
        } + MontageGraph.Overlay(
            "heartbeat-finale-echo-stutter",
            HeartbeatMontageProfile.FINALE_ECHO_START_US / 1_000L,
            (HeartbeatMontageProfile.FINALE_ECHO_END_US + 999L) / 1_000L,
            "heartbeat-echo-experimental",
            overlayKind = MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
            opacity = .62f,
            secondarySourceOffsetMs = echoOffsetMs
        ) + MontageGraph.Overlay("heartbeat-tail",ms(HeartbeatMontageProfile.LAST_VISUAL_END_US),
            outputDurationMs,"heartbeat-measured-step",
            overlayKind = MontageGraph.OverlayKind.BLACK_FADE,opacity = 1f)
    }

    /** Millisecond lengths of actual uses, including the hero override of the authored finale.
     * Differencing rounded endpoints matches build(), rather than independently ceiling each cut. */
    internal fun liveRoleLengths(): List<Long> = (0 until REQUIRED_POOL_ROLES).map { role ->
        HeartbeatMontageProfile.scenes.mapIndexedNotNull { index, scene ->
            val effectiveRole = if (index == HeartbeatMontageProfile.scenes.lastIndex)
                HeartbeatMontageProfile.FINALE_PORTRAIT_ROLE else scene.sourceRole
            if (effectiveRole == role) (scene.endUs + 500L) / 1_000L -
                (scene.startUs + 500L) / 1_000L else null
        }.max()
    }

    internal data class LiveRoleReservation(val sourceRole: Int, val startMs: Long, val endMs: Long)

    private data class EchoUse(val sceneIndex: Int, val firstLocalUs: Long, val lastLocalUs: Long)
    internal data class EchoFootprint(val firstRelativeUs: Long, val lastRelativeUs: Long)

    /** Immutable authored timing only, not source observations or invented decoded evidence.
     * Use the real 60fps scheduler/compositor so concealed pulses, low-opacity echoes, rounded
     * cut endpoints and the late finale echo all have exactly the same clock as production. */
    private val authoredEchoUses: List<EchoUse> by lazy {
        fun ms(us: Long) = (us + 500L) / 1_000L
        val clips = HeartbeatMontageProfile.scenes.mapIndexed { index, scene ->
            val durationMs = ms(scene.endUs) - ms(scene.startUs)
            MontageGraph.Clip("heartbeat-timing-$index", 0L, durationMs, durationMs,
                MontageGraph.ShotRole.DETAIL, MontageGraph.Transition.HARD_CUT,
                MontageGraph.Motion.HOLD, 1f, ms(scene.startUs))
        }.toMutableList()
        val outputMs = HeartbeatMontageProfile.OUTPUT_DURATION_US / 1_000L
        val tailMs = outputMs - ms(HeartbeatMontageProfile.LAST_VISUAL_END_US)
        clips += clips.last().copy(id = "heartbeat-timing-tail", sourceEndMs = tailMs,
            outputDurationMs = tailMs)
        val timingGraph = MontageGraph(outputMs, outputMs, clips = clips,
            overlays = authoredOverlays(0L),
            metadata = NleProjectMetadata(generator = HeartbeatMontageProfile.ID + ":timing"))
        HighQualityFramePlan.build(timingGraph, HeartbeatMontageProfile.REFERENCE_FPS).frames
            .filter { it.layer.heartbeatEcho }.groupBy { it.clipIndex }.map { (index, frames) ->
                EchoUse(index, frames.minOf { it.sourceTimeUs }, frames.maxOf { it.sourceTimeUs })
            }
    }

    /** Source positions relative to one jointly reserved interval AFTER each use's centred trim.
     * The interlude owns role4's first/reprise insert, never its separately selected hero finale. */
    internal fun echoFootprint(role: Int, reservedDurationMs: Long,
        includeFinale: Boolean = true): EchoFootprint? {
        fun ms(us: Long) = (us + 500L) / 1_000L
        val positions = authoredEchoUses.filter { use ->
            val isFinale = use.sceneIndex == HeartbeatMontageProfile.scenes.lastIndex
            val effectiveRole = if (isFinale) HeartbeatMontageProfile.FINALE_PORTRAIT_ROLE
                else HeartbeatMontageProfile.scenes[use.sceneIndex].sourceRole
            effectiveRole == role && (includeFinale || !isFinale)
        }.map { use ->
            val scene = HeartbeatMontageProfile.scenes[use.sceneIndex]
            val outputMs = ms(scene.endUs) - ms(scene.startUs)
            require(reservedDurationMs >= outputMs)
            val trimUs = (reservedDurationMs - outputMs) / 2L * 1_000L
            (trimUs + use.firstLocalUs) to (trimUs + use.lastLocalUs)
        }
        return positions.takeIf { it.isNotEmpty() }?.let {
            EchoFootprint(it.minOf { position -> position.first }, it.maxOf { position -> position.second })
        }
    }

    private fun fitsEchoFootprint(startMs: Long, sourceDurationMs: Long,
        footprint: EchoFootprint?, echoOffsetMs: Long): Boolean {
        if (footprint == null) return true
        // Check magnitudes before microsecond arithmetic; even diagnostic Long extremes cannot
        // overflow into a seemingly valid source timestamp. No renderer clamping is planned.
        if (echoOffsetMs !in -sourceDurationMs..sourceDurationMs) return false
        val offsetUs = echoOffsetMs * 1_000L
        return startMs * 1_000L + footprint.firstRelativeUs + offsetUs >= 0L &&
            startMs * 1_000L + footprint.lastRelativeUs + offsetUs < sourceDurationMs * 1_000L
    }

    /** Joint half-open source reservations. First retain the measured composition permutation;
     * if it cannot fit, search length-compatible permutations of its ordinary roles before a
     * typed material refusal. The opening, hero/finale and light portrait keep their measured
     * identities, with the independently selected original hero as a compatible finale fallback.
     * A shorter interval is never used to slow or pad an authored slot. */
    internal fun reserveLiveRoles(
        pool: MontageGraph,
        roles: List<MontageGraph.Clip>,
        preferredOrder: List<Int>,
        allowShiftedHandles: Boolean,
        allowProfileGap: Boolean,
        echoOffsetMs: Long = -67L,
        checkCancelled: () -> Unit = {}
    ): List<LiveRoleReservation> {
        val lengths = liveRoleLengths()
        if (echoOffsetMs !in -pool.sourceDurationMs..pool.sourceDurationMs) {
            throw MaterialRejectedException("insufficient_distinct_moments",
                "Heartbeat temporal offset cannot fit the available source")
        }
        require(roles.size >= REQUIRED_POOL_ROLES && preferredOrder.take(REQUIRED_POOL_ROLES)
            .sorted() == (0 until REQUIRED_POOL_ROLES).toList())
        val echoFootprints = lengths.mapIndexed { role, durationMs -> echoFootprint(role, durationMs) }
        val options = Array(REQUIRED_POOL_ROLES) { target ->
            Array(REQUIRED_POOL_ROLES) { source ->
                checkCancelled()
                liveHandleStarts(pool, roles[source], lengths[target],
                    allowShiftedHandles, allowProfileGap, checkCancelled)
                    .filter { fitsEchoFootprint(it, pool.sourceDurationMs,
                        echoFootprints[target], echoOffsetMs) }.map { start ->
                    LiveRoleReservation(source, start, start + lengths[target])
                }
            }
        }
        val pinned = setOf(OPENING_CUE_ROLE, CLOSE_CUE_ROLE, LIGHT_ACCENT_CUE_ROLE)
        // A measured close/wide inversion may put the short action window into the 1.133s
        // finale. Its wider face is a preference, not a reason to reject an independently
        // selected readable hero whose original long reservation is actually compatible.
        val heroFallbackOrder = preferredOrder.toMutableList().also { order ->
            val originalHeroPosition = order.indexOf(CLOSE_CUE_ROLE)
            val measuredHero = order[CLOSE_CUE_ROLE]
            order[CLOSE_CUE_ROLE] = CLOSE_CUE_ROLE
            order[originalHeroPosition] = measuredHero
        }
        fun choices(target: Int, remap: Boolean, order: List<Int>): List<LiveRoleReservation> {
            val preferred = order[target]
            val pinnedSources = pinned.map { order[it] }.toSet()
            val alternatives = if (!remap || target in pinned) emptyList() else
                (0 until REQUIRED_POOL_ROLES).filter { it != preferred && it !in pinnedSources }
                    .sortedWith(compareBy<Int> { source ->
                        // Preserve the closest measured cadence when length forces reassignment.
                        val authored = order.indexOf(source)
                        val width = HeartbeatMontageProfile.targetFaceWidthForRole(target)
                        val other = HeartbeatMontageProfile.targetFaceWidthForRole(authored)
                        if (width == null || other == null) 1f else abs(width - other)
                    }.thenBy { it })
            return (listOf(preferred) + alternatives).flatMap { options[target][it] }
        }
        fun search(remap: Boolean, order: List<Int>): List<LiveRoleReservation>? {
            val rawDomains = (0 until REQUIRED_POOL_ROLES).map { choices(it, remap, order) }
            // Two handles which block exactly the same choices in every other role are
            // interchangeable for this constraint problem. Retain the preferred one. Without
            // this exact quotient, dozens of irrelevant shifted handles multiplied an otherwise
            // local impossible overlap into a factorial search. No feasible assignment is lost.
            val domains = rawDomains.mapIndexed { target, candidates ->
                checkCancelled()
                val seen = HashSet<java.util.BitSet>()
                candidates.filter { candidate ->
                    checkCancelled()
                    val conflicts = java.util.BitSet()
                    var ordinal = 0
                    rawDomains.forEachIndexed { otherTarget, otherCandidates ->
                        for (other in otherCandidates) {
                            if (otherTarget != target && (candidate.sourceRole == other.sourceRole ||
                                    roles[candidate.sourceRole].sourceIndex == roles[other.sourceRole].sourceIndex &&
                                    candidate.startMs < other.endMs && other.startMs < candidate.endMs)) {
                                conflicts.set(ordinal)
                            }
                            ordinal++
                        }
                    }
                    seen.add(conflicts)
                }
            }
            val selected = arrayOfNulls<LiveRoleReservation>(REQUIRED_POOL_ROLES)
            fun compatible(candidate: LiveRoleReservation): Boolean = selected.filterNotNull().all { other ->
                candidate.sourceRole != other.sourceRole &&
                    (roles[candidate.sourceRole].sourceIndex != roles[other.sourceRole].sourceIndex ||
                        candidate.endMs <= other.startMs || other.endMs <= candidate.startMs)
            }
            fun visit(remaining: Int): Boolean {
                checkCancelled()
                if (remaining == 0) return true
                val available = selected.indices.filter { selected[it] == null }
                    .map { target -> target to domains[target].filter(::compatible) }
                if (available.any { it.second.isEmpty() }) return false
                // Forward-check both independent necessary conditions. Counting shifted handles
                // as distinct source roles hides Hall shortages and can explore factorially many
                // impossible permutations. Counting their shared time repeatedly likewise hides
                // a temporal shortfall. These are proofs of impossibility, not search timeouts.
                val matchedTargets = IntArray(REQUIRED_POOL_ROLES) { -1 }
                fun match(target: Int, seen: BooleanArray): Boolean {
                    val candidates = available.single { it.first == target }.second
                    for (source in candidates.map { it.sourceRole }.distinct()) {
                        if (seen[source]) continue
                        seen[source] = true
                        if (matchedTargets[source] < 0 || match(matchedTargets[source], seen)) {
                            matchedTargets[source] = target
                            return true
                        }
                    }
                    return false
                }
                if (available.any { !match(it.first, BooleanArray(REQUIRED_POOL_ROLES)) }) return false
                val forcedDuration = available.mapNotNull { (target, candidates) ->
                    val indices = candidates.map { roles[it.sourceRole].sourceIndex }.distinct()
                    if (indices.size == 1) indices.single() to lengths[target] else null
                }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
                for ((sourceIndex, demand) in forcedDuration) {
                    val ranges = available.flatMap { it.second }.filter {
                        roles[it.sourceRole].sourceIndex == sourceIndex
                    }.sortedWith(compareBy<LiveRoleReservation> { it.startMs }.thenBy { it.endMs })
                    var unionMs = 0L
                    var endMs = -1L
                    for (range in ranges) {
                        unionMs += (range.endMs - maxOf(range.startMs, endMs)).coerceAtLeast(0L)
                        endMs = maxOf(endMs, range.endMs)
                    }
                    if (unionMs < demand) return false
                }
                val (target, candidates) = available.minWith(
                    compareBy<Pair<Int, List<LiveRoleReservation>>> { it.second.size }
                        .thenByDescending { lengths[it.first] }.thenBy { it.first })
                for (candidate in candidates) {
                    selected[target] = candidate
                    if (visit(remaining - 1)) return true
                    selected[target] = null
                }
                return false
            }
            return if (visit(REQUIRED_POOL_ROLES)) selected.map { requireNotNull(it) } else null
        }
        return search(false, preferredOrder) ?: search(true, preferredOrder) ?:
            search(false, heroFallbackOrder) ?: search(true, heroFallbackOrder) ?:
            throw MaterialRejectedException(
            "insufficient_distinct_moments",
            "Heartbeat needs disjoint readable handles for every measured live role and finale")
    }

    private fun liveHandleStarts(
        pool: MontageGraph,
        selected: MontageGraph.Clip,
        durationMs: Long,
        allowShiftedHandles: Boolean,
        allowProfileGap: Boolean,
        checkCancelled: () -> Unit = {}
    ): List<Long> = liveHandleStartSequence(pool.sourceDurationMs, pool.frameAttachments, selected,
        durationMs, allowShiftedHandles,
        allowProfileGap, checkCancelled).toList()

    /** Necessary only: before composition exists a candidate can serve ANY authored live use.
     * Keeping this deliberately permissive superset cannot discard a valid mapped assignment.
     * Short contained uses are checked first and need no expensive attachment scan. */
    internal fun hasAnyLiveUse(pool: MontageGraph, selected: MontageGraph.Clip,
        echoOffsetMs: Long = -67L, allowShiftedHandles: Boolean = true,
        allowProfileGap: Boolean = true, checkCancelled: () -> Unit = {}): Boolean =
        hasAnyLiveUse(pool.sourceDurationMs, pool.frameAttachments, selected, echoOffsetMs,
            allowShiftedHandles, allowProfileGap, checkCancelled)

    internal fun hasAnyLiveUse(sourceDurationMs: Long, timeline: FrameAttachmentTimeline,
        selected: MontageGraph.Clip, echoOffsetMs: Long = -67L, allowShiftedHandles: Boolean = true,
        allowProfileGap: Boolean = true, checkCancelled: () -> Unit = {}): Boolean {
        if (echoOffsetMs !in -sourceDurationMs..sourceDurationMs) return false
        val lengths = liveRoleLengths()
        for (target in lengths.indices.sortedWith(compareBy<Int> { lengths[it] }.thenBy { it })) {
            checkCancelled()
            val footprint = echoFootprint(target, lengths[target])
            if (liveHandleStartSequence(sourceDurationMs, timeline, selected, lengths[target], allowShiftedHandles,
                    allowProfileGap, checkCancelled) { start ->
                    fitsEchoFootprint(start, sourceDurationMs, footprint, echoOffsetMs)
                }.any()) return true
        }
        return false
    }

    private fun liveHandleStartSequence(sourceDurationMs: Long, timeline: FrameAttachmentTimeline,
        selected: MontageGraph.Clip,
        durationMs: Long, allowShiftedHandles: Boolean, allowProfileGap: Boolean,
        checkCancelled: () -> Unit, bounds: (Long) -> Boolean = { true }): Sequence<Long> {
        if (durationMs <= 0L || sourceDurationMs < durationMs) return emptySequence()
        val centreMs = (selected.sourceStartMs + selected.sourceEndMs) / 2L
        val centred = centreMs - durationMs / 2L
        val shifts = if (allowShiftedHandles) listOf(0L) +
            (25L..minOf(250L, durationMs / 4L) step 25L).flatMap { listOf(-it, it) }
        else listOf(0L)
        return shifts.asSequence().map { (centred + it).coerceIn(0L, sourceDurationMs - durationMs) }
            .distinct().filter { start ->
                checkCancelled()
                val end = start + durationMs
                bounds(start) && centreMs in start until end &&
                    // Trimming an already selected role uses its evidence. Extending beyond it
                    // requires the unchanged continuous readable-face/profile-gap contract.
                    (start >= selected.sourceStartMs && end <= selected.sourceEndMs ||
                        readableExtension(timeline, start, end, allowProfileGap))
            }
    }

    /** Static reframing for the measured bright portrait role. It changes neither source PTS nor
     * speed. Face coordinates come from the same local analysis attached to the selected source. */
    internal fun lightPortraitTransform(
        selected: MontageGraph.Clip,
        timeline: FrameAttachmentTimeline
    ): MontageGraph.ClipTransform {
        val portrait = portraitTransform(selected, timeline, .22f)
        if (portrait != MontageGraph.ClipTransform.hold()) return portrait
        val centreUs = (selected.sourceStartMs + selected.sourceEndMs) * 500L
        val face = timeline.nearest(centreUs, 300_000L)?.faceRegion
            ?.takeIf { it.confidence >= .60f && it.width > .02f && it.width <= .33f }
            ?: return MontageGraph.ClipTransform.hold()
        // Use a softer fallback when portraitTransform is too strict (wide face):
        // enough zoom for readable composition without introducing edge-crop artifacts.
        val scale = (0.24f / face.width).coerceIn(1.00f, 1.16f)
        if (scale <= 1.04f) return MontageGraph.ClipTransform.hold()
        val targetX = .50f
        val targetY = .38f
        val sourceFaceScreenX = .5f + scale * (face.centerX - .5f)
        val sourceFaceScreenY = .5f + scale * (face.centerY - .5f)
        val translationLimit = (scale - 1f) / 2f
        val translateX = (targetX - sourceFaceScreenX).coerceIn(-translationLimit, translationLimit)
        // Android GL geometry is bottom-up while face evidence is top-down.
        val translateY = (sourceFaceScreenY - targetY).coerceIn(-translationLimit, translationLimit)
        return MontageGraph.ClipTransform(
            listOf(
                MontageGraph.ClipTransform.Keyframe(0f, scale, translateX, translateY),
                MontageGraph.ClipTransform.Keyframe(1f, scale, translateX, translateY)
            )
        )
    }

    internal fun portraitTransform(
        selected: MontageGraph.Clip,
        timeline: FrameAttachmentTimeline,
        targetFaceWidth: Float,
        maximumTranslation: Float = .30f
    ): MontageGraph.ClipTransform {
        require(targetFaceWidth in .10f.. .40f)
        require(maximumTranslation in .10f.. .50f)
        val centreUs = (selected.sourceStartMs + selected.sourceEndMs) * 500L
        val face = timeline.nearest(centreUs, 300_000L)?.faceRegion
            ?.takeIf { it.confidence >= .65f && it.width > .02f }
            ?: return MontageGraph.ClipTransform.hold()
        // These composition targets are a bounded tuning hypothesis reviewed against the local
        // author reference. They intentionally cap crop strength to avoid the former side crop.
        val scale = (targetFaceWidth / face.width).coerceIn(1f, 2.2f)
        if (scale <= 1.02f) return MontageGraph.ClipTransform.hold()
        val targetX = .5f
        val targetY = .38f
        val sourceFaceScreenX = .5f + scale * (face.centerX - .5f)
        val sourceFaceScreenY = .5f + scale * (face.centerY - .5f)
        // A translated zoom must still cover the output. Moving farther than half of the
        // extra scaled extent exposes the clear colour as a black edge.
        val translationLimit = minOf(maximumTranslation, (scale - 1f) / 2f)
        val translateX = (targetX - sourceFaceScreenX)
            .coerceIn(-translationLimit, translationLimit)
        // Android GL geometry uses bottom-up Y while semantic evidence is top-down.
        val translateY = (sourceFaceScreenY - targetY)
            .coerceIn(-translationLimit, translationLimit)
        val keyframe = MontageGraph.ClipTransform.Keyframe(
            0f, scale, translateX, translateY
        )
        return MontageGraph.ClipTransform(listOf(keyframe, keyframe.copy(at = 1f)))
    }

    /** Assign the already selected source windows to the measured Heartbeat scale cadence. The
     * mapping compares ranks rather than absolute widths because the square reference and portrait
     * source have different crops. It runs only with complete semantic evidence, remains a
     * permutation, reserves the dark opening, independently selected finale and the already
     * verified light-accent portrait. This is only a role-identity permutation; build's subsequent
     * joint reservation proves distinct final intervals, which a permutation alone cannot prove. */
    internal fun measuredCompositionRoleOrder(
        roles: List<MontageGraph.Clip>,
        timeline: FrameAttachmentTimeline,
        visualMap: VisualEventMap? = null
    ): List<Int> {
        val order = roles.indices.toMutableList()
        if (roles.size < REQUIRED_POOL_ROLES) return order
        fun medianFaceWidth(role: Int): Float? {
            val clip = roles[role]
            val values = timeline.frames.asSequence()
                .filter { it.sourceTimeUs / 1_000L in clip.sourceStartMs..clip.sourceEndMs }
                .mapNotNull { it.faceRegion?.takeIf { face -> face.confidence >= .65f }?.width }
                .sorted()
                .toList()
            if (values.size < 2) return null
            return values[values.size / 2]
        }
        val currentCloseScale = medianFaceWidth(CLOSE_CUE_ROLE)
        val currentWideScale = medianFaceWidth(WIDE_ACTION_CUE_ROLE)
        val invertedFinalePair = currentCloseScale != null && currentWideScale != null &&
            currentCloseScale + MINIMUM_ROLE_SCALE_SEPARATION < currentWideScale
        val finaleRole = if (invertedFinalePair) WIDE_ACTION_CUE_ROLE else CLOSE_CUE_ROLE
        if (invertedFinalePair) {
            order[CLOSE_CUE_ROLE] = WIDE_ACTION_CUE_ROLE
            order[WIDE_ACTION_CUE_ROLE] = CLOSE_CUE_ROLE
        }

        val targetRoles = (0 until REQUIRED_POOL_ROLES).filter { role ->
            role != CLOSE_CUE_ROLE && role != LIGHT_ACCENT_CUE_ROLE &&
                HeartbeatMontageProfile.targetFaceWidthForRole(role) != null
        }
        val sourceRoles = (0 until REQUIRED_POOL_ROLES).filter { role ->
            role != OPENING_CUE_ROLE && role != finaleRole && role != LIGHT_ACCENT_CUE_ROLE
        }
        val measuredSources = sourceRoles.mapNotNull { role ->
            medianFaceWidth(role)?.let { width -> role to width }
        }
        if (targetRoles.size != sourceRoles.size || measuredSources.size != sourceRoles.size) {
            return order
        }
        val sourceScaleSpan = measuredSources.maxOf { it.second } - measuredSources.minOf { it.second }
        if (sourceScaleSpan < MINIMUM_SOURCE_SCALE_SPAN) return order
        val rankedTargets = targetRoles.sortedWith(
            compareBy<Int> { HeartbeatMontageProfile.targetFaceWidthForRole(it)!! }.thenBy { it }
        )
        val rankedSources = measuredSources.sortedWith(
            compareBy<Pair<Int, Float>> { it.second }.thenBy { it.first }
        )
        val sourceMotion = sourceRoles.mapNotNull { role ->
            val clip = roles[role]
            visualMap?.observations?.asSequence()
                ?.filter { it.sourceTimeUs / 1_000L in clip.sourceStartMs..clip.sourceEndMs }
                ?.map { it.cameraMotion.magnitude + it.subjectMotion.magnitude }
                ?.sorted()
                ?.toList()
                ?.takeIf { it.size >= 2 }
                ?.let { values -> role to values[values.size / 2] }
        }.toMap()
        val useMotion = sourceMotion.size == sourceRoles.size &&
            sourceMotion.values.maxOrNull()!! - sourceMotion.values.minOrNull()!! >=
            MINIMUM_SOURCE_MOTION_SPAN
        if (useMotion) {
            val targetScales = normalized(targetRoles.map {
                HeartbeatMontageProfile.targetFaceWidthForRole(it)!!
            })
            val sourceScales = normalized(sourceRoles.map { role ->
                measuredSources.single { it.first == role }.second
            })
            val targetMotions = normalized(targetRoles.map {
                HeartbeatMontageProfile.targetMotionForRole(it)!!
            })
            val sourceMotions = normalized(sourceRoles.map { sourceMotion.getValue(it) })
            val assignment = minimumCostAssignment(targetRoles.size) { targetIndex, sourceIndex ->
                SCALE_COST_WEIGHT * abs(targetScales[targetIndex] - sourceScales[sourceIndex]) +
                    MOTION_COST_WEIGHT * abs(targetMotions[targetIndex] - sourceMotions[sourceIndex])
            }
            targetRoles.indices.forEach { index ->
                order[targetRoles[index]] = sourceRoles[assignment[index]]
            }
        } else {
            rankedTargets.zip(rankedSources).forEach { (targetRole, source) ->
                order[targetRole] = source.first
            }
        }
        order[CLOSE_CUE_ROLE] = finaleRole
        order[LIGHT_ACCENT_CUE_ROLE] = LIGHT_ACCENT_CUE_ROLE
        return order
    }

    private fun normalized(values: List<Float>): List<Float> {
        val minimum = values.minOrNull() ?: return emptyList()
        val span = (values.maxOrNull() ?: minimum) - minimum
        if (span <= .0001f) return List(values.size) { .5f }
        return values.map { (it - minimum) / span }
    }

    /** Exact deterministic assignment for the 12 remappable Heartbeat roles (2^12 states). */
    private fun minimumCostAssignment(
        size: Int,
        cost: (targetIndex: Int, sourceIndex: Int) -> Float
    ): List<Int> {
        require(size in 1..15)
        val stateCount = 1 shl size
        val totals = FloatArray(stateCount) { Float.POSITIVE_INFINITY }
        val previous = IntArray(stateCount) { -1 }
        val chosen = IntArray(stateCount) { -1 }
        totals[0] = 0f
        for (mask in 0 until stateCount) {
            val targetIndex = Integer.bitCount(mask)
            if (targetIndex >= size || !totals[mask].isFinite()) continue
            for (sourceIndex in 0 until size) {
                val bit = 1 shl sourceIndex
                if (mask and bit != 0) continue
                val next = mask or bit
                val candidate = totals[mask] + cost(targetIndex, sourceIndex)
                if (candidate < totals[next]) {
                    totals[next] = candidate
                    previous[next] = mask
                    chosen[next] = sourceIndex
                }
            }
        }
        val assignment = IntArray(size)
        var mask = stateCount - 1
        for (targetIndex in size - 1 downTo 0) {
            assignment[targetIndex] = chosen[mask]
            mask = previous[mask]
        }
        check(mask == 0 && assignment.all { it >= 0 })
        return assignment.toList()
    }

    internal fun exposureDeltaForTargetLuma(
        targetLuma: Float,
        sourceLuma: Float,
        baseExposure: Float
    ): Float {
        require(targetLuma in .05f.. .95f && sourceLuma in .02f..1f && baseExposure.isFinite())
        val targetBeforeGamma = targetLuma.pow(1f / HEARTBEAT_GRADE_GAMMA)
        val totalExposure = (targetBeforeGamma / sourceLuma - 1f).coerceIn(-.85f, .95f)
        return totalExposure - baseExposure
    }

    internal fun medianSourceLuma(
        visualMap: VisualEventMap?,
        clip: MontageGraph.Clip
    ): Float? = visualMap?.observations
        ?.asSequence()
        ?.filter { it.sourceTimeUs / 1_000L in clip.sourceStartMs..clip.sourceEndMs }
        ?.map { it.meanLuma }
        ?.sorted()
        ?.toList()
        ?.takeIf { it.size >= 2 }
        ?.let { it[it.size / 2] }

    /** Prefer centred handles; shift at most 250 ms while retaining the selected event inside. */
    internal fun readableWindowStart(timeline: FrameAttachmentTimeline, centreMs: Long,
        durationMs: Long, sourceDurationMs: Long, allowProfileGap: Boolean = true): Long? {
        if(durationMs<=0 || sourceDurationMs<durationMs) return null
        val centred=centreMs-durationMs/2L
        val shifts=listOf(0L)+(25L..minOf(250L,durationMs/4L) step 25L).flatMap { listOf(-it,it) }
        return shifts.asSequence().map { (centred+it).coerceIn(0L,sourceDurationMs-durationMs) }
            .distinct().firstOrNull { start -> centreMs in start until start+durationMs &&
                readableExtension(timeline,start,start+durationMs,allowProfileGap) }
    }

    /** Reuse existing semantic evidence; never invent unseen handles to hide a slow plan. */
    internal fun readableExtension(timeline: FrameAttachmentTimeline, startMs: Long, endMs: Long,
        allowProfileGap: Boolean = true): Boolean {
        if(startMs<0L || endMs<=startMs) return false
        val startUs=startMs*1_000L
        val endUs=endMs*1_000L
        val left=timeline.nearest(startUs,125_000L) ?: return false
        val right=timeline.nearest(endUs,125_000L) ?: return false
        val evidence=(listOf(left)+timeline.frames.filter { it.sourceTimeUs in startUs..endUs }+right)
            .distinctBy { it.sourceTimeUs }.sortedBy { it.sourceTimeUs }
        if(evidence.zipWithNext().any { (a,b) -> b.sourceTimeUs-a.sourceTimeUs > 300_000L }) return false
        fun face(frame: FrameAttachments) = (frame.faceRegion?.confidence ?: 0f)>=.65f
        return evidence.withIndex().all { (index,frame) ->
            if(frame.subjectOcclusion>.35f) false
            else if(face(frame)) frame.subjectQuality>=.55f
            else {
                val previous=evidence.getOrNull(index-1)
                val next=evidence.getOrNull(index+1)
                allowProfileGap && previous!=null && next!=null && face(previous) && face(next) &&
                    next.sourceTimeUs-previous.sourceTimeUs<=500_000L &&
                    frame.subjectQuality>=.5f && (frame.mask?.confidence ?: 0f)>=.9f &&
                    frame.maskTemporalIou>=.65f
            }
        }
    }

    /**
     * Finds one visually useful non-face interval that the generic 15-role pool missed. The
     * measured 3.733 s reference cue hides the face during a fast full-body action, so an animal,
     * object or action insert belongs there. Putting it at the former 8.700 s cue erased a clearly
     * measured face from both the first phrase and its reprise.
     */
    internal fun selectDistinctSubjectlessInterlude(
        pool: MontageGraph,
        visual: VisualEventMap,
        roles: List<MontageGraph.Clip> = pool.clips.take(15),
        echoOffsetMs: Long = -67L,
        checkCancelled: () -> Unit = {}
    ): MontageGraph.Clip? {
        if (roles.isEmpty() || visual.observations.size < 3) return null
        val durationMs = INTERLUDE_DURATION_MS.coerceAtMost(pool.sourceDurationMs)
        if (durationMs <= 0L) return null
        val roleCentres = roles.map { (it.sourceStartMs + it.sourceEndMs) / 2L }
        data class Candidate(val startMs: Long, val score: Float)
        val candidates = visual.observations.asSequence().mapNotNull { anchor ->
            checkCancelled()
            val centreMs = anchor.sourceTimeUs / 1_000L
            val startMs = (centreMs - durationMs / 2L)
                .coerceIn(0L, (pool.sourceDurationMs - durationMs).coerceAtLeast(0L))
            if (!fitsEchoFootprint(startMs, pool.sourceDurationMs,
                    echoFootprint(DISTINCT_INTERLUDE_AUTHORED_ROLE, durationMs,
                        includeFinale = false), echoOffsetMs)) return@mapNotNull null
            // The caller supplies the actual joint reservations after composition assignment.
            // Novelty of centres is not proof of distinct source time: try the lower-ranked
            // eligible intervals too when the most attractive insert is already occupied.
            if (roles.any { role -> role.sourceIndex == roles[DISTINCT_INTERLUDE_AUTHORED_ROLE].sourceIndex &&
                    startMs < role.sourceEndMs && role.sourceStartMs < startMs + durationMs }) {
                return@mapNotNull null
            }
            val samples = visual.observations.filter {
                it.sourceTimeUs / 1_000L in startMs until startMs + durationMs
            }
            if (samples.size < 3) return@mapNotNull null
            val noFaceRatio = samples.count { it.faceInferenceSucceeded && it.face == null }
                .toFloat() / samples.size
            val maximumOcclusion = samples.maxOf { it.occlusionConfidence }
            val quality = samples.map { it.visualQuality }.average().toFloat()
            if (noFaceRatio < .75f || maximumOcclusion >= .55f || quality < .48f) {
                return@mapNotNull null
            }
            val composition = samples.mapNotNull { it.composition?.quality }
                .average().toFloat().takeIf { it.isFinite() } ?: .45f
            val motion = samples.map {
                it.cameraMotion.magnitude + it.subjectMotion.magnitude
            }.average().toFloat()
            val motionInterest = (motion / .16f).coerceIn(0f, 1f)
            val distance = roleCentres.minOf { abs(it - centreMs) }.toFloat() /
                pool.sourceDurationMs.coerceAtLeast(1L)
            val novelty = (distance / .12f).coerceIn(0f, 1f)
            Candidate(startMs, quality * .36f + composition * .18f + noFaceRatio * .20f +
                motionInterest * .12f + novelty * .14f)
        }.distinctBy { it.startMs }.toList()
        val best = candidates.maxByOrNull { it.score }?.takeIf { it.score >= .58f } ?: return null
        return roles[DISTINCT_INTERLUDE_AUTHORED_ROLE].copy(
            id = "heartbeat-distinct-interlude",
            sourceStartMs = best.startMs,
            sourceEndMs = best.startMs + durationMs,
            role = MontageGraph.ShotRole.DETAIL,
            transitionIn = MontageGraph.Transition.HARD_CUT,
            motion = MontageGraph.Motion.HOLD,
            confidence = best.score.coerceIn(.05f, 1f),
            transform = MontageGraph.ClipTransform.hold(),
            speedRamp = MontageGraph.SpeedRamp.constant()
        )
    }

    private const val DISTINCT_INTERLUDE_AUTHORED_ROLE = 4
    private const val INTERLUDE_DURATION_MS = 934L
    private const val REQUIRED_POOL_ROLES = 15
    private const val OPENING_CUE_ROLE = 0
    private const val CLOSE_CUE_ROLE = 4
    private const val LIGHT_ACCENT_CUE_ROLE = 9
    private const val WIDE_ACTION_CUE_ROLE = 11
    private const val MINIMUM_ROLE_SCALE_SEPARATION = .08f
    private const val MINIMUM_SOURCE_SCALE_SPAN = .12f
    private const val MINIMUM_SOURCE_MOTION_SPAN = .10f
    private const val SCALE_COST_WEIGHT = .60f
    private const val MOTION_COST_WEIGHT = .40f
    private const val HEARTBEAT_GRADE_GAMMA = 1.10f
    private const val FINALE_ENTRY_LUMA = .44f
    private const val FINALE_PLATEAU_LUMA = .72f
    private const val FINALE_ECHO_ENTRY_LUMA = .42f
    private const val FINALE_ECHO_EXIT_LUMA = .38f
}
