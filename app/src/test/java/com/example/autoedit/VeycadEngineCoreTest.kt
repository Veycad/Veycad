package com.example.autoedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class VeycadEngineCoreTest {
    @Test fun graph_and_interpreter_preserve_exact_output_clock() {
        val graph = graph(transition = MontageGraph.Transition.WHIP)
        val plan = MontageGraphInterpreter.interpret(graph)

        assertEquals(2_000L, plan.durationMs)
        assertEquals(1_000L, plan.clips[1].timelineStartMs)
        assertEquals(320L, plan.transitions.single().durationMs)
    }

    @Test fun graph_rejects_duration_drift_at_its_boundary() {
        assertThrows(IllegalArgumentException::class.java) {
            MontageGraph(2_000L, 1_999L, clips = listOf(clip("a", 0L, 1_000L)))
        }
    }

    @Test fun cubic_speed_ramp_maps_source_time_monotonically() {
        val ramp = MontageGraph.SpeedRamp(listOf(
            MontageGraph.SpeedRamp.Keyframe(0f, .6f, MontageGraph.SpeedRamp.CubicBezier(.2f, .8f, .3f, 1f)),
            MontageGraph.SpeedRamp.Keyframe(.45f, 2f),
            MontageGraph.SpeedRamp.Keyframe(1f, .8f)
        ))
        val fractions = (0..20).map { ramp.sourceFractionAt(it / 20f) }

        assertEquals(0f, fractions.first(), .0001f)
        assertEquals(1f, fractions.last(), .0001f)
        assertTrue(fractions.zipWithNext().all { (left, right) -> right >= left })
    }

    @Test fun exact_blackout_window_is_only_two_to_four_encoded_frames() {
        val active = HighQualityFramePlan.build(graph(MontageGraph.Transition.BLACKOUT), 30).frames.count {
            TransitionTimeline.blendFor(it) != null
        }

        assertTrue("active=$active", active in 2..4)
        assertEquals(105L, TransitionTimeline.durationMs(MontageGraph.Transition.BLACKOUT))
    }

    @Test fun gpu_transition_model_has_real_two_source_midpoint() {
        val frame = GpuTransitionModel.sample(MontageGraph.Transition.WHIP, .5f, directionX = -1f)

        assertEquals(.5f, frame.incomingAlpha, .001f)
        assertEquals(.5f, frame.outgoingAlpha, .001f)
        assertTrue(frame.directionalBlur > 0f)
        assertTrue(frame.incomingOffsetX < 0f && frame.outgoingOffsetX > 0f)
    }

    @Test fun export_shader_source_contract_does_not_apply_transition_alpha_twice() {
        val shader = MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER

        assertTrue(shader.contains("outgoing*uOutgoingAlpha+incoming*uIncomingAlpha"))
        assertFalse(shader.contains("mix(outgoing*uOutgoingAlpha,incoming*uIncomingAlpha"))
    }

    @Test fun foreground_reentry_has_delayed_subject_envelope_and_outline() {
        val before = GpuTransitionModel.sample(MontageGraph.Transition.FOREGROUND_REENTRY, .02f)
        val firstAuthorAccent = GpuTransitionModel.sample(MontageGraph.Transition.FOREGROUND_REENTRY, .22f)
        val active = GpuTransitionModel.sample(MontageGraph.Transition.FOREGROUND_REENTRY, .40f)
        val referenceMidpoint = GpuTransitionModel.sample(MontageGraph.Transition.FOREGROUND_REENTRY, .60f)
        val settledBeforePlate = GpuTransitionModel.sample(MontageGraph.Transition.FOREGROUND_REENTRY, .72f)
        val background = GpuTransitionModel.sample(MontageGraph.Transition.FOREGROUND_REENTRY, .92f)
        val backgroundLanding = GpuTransitionModel.sample(MontageGraph.Transition.FOREGROUND_REENTRY, 1f)

        assertEquals(0f, before.foregroundReentry, .0001f)
        assertTrue(firstAuthorAccent.foregroundReentry in .27f..0.33f)
        assertEquals(0f, firstAuthorAccent.originalBackgroundReveal, .0001f)
        assertTrue(firstAuthorAccent.openingAccentPulse > .95f)
        assertEquals(0f, before.openingAccentPulse, .0001f)
        assertTrue(active.foregroundReentry in .35f..0.42f)
        assertEquals(0f, active.originalBackgroundReveal, .0001f)
        assertTrue(referenceMidpoint.foregroundReentry in .62f..0.65f)
        assertTrue(settledBeforePlate.foregroundReentry > .95f)
        assertTrue(background.originalBackgroundReveal in .05f..0.5f)
        assertTrue(backgroundLanding.originalBackgroundReveal > .7f)
        assertTrue(active.outlineStrength < .05f)
        assertTrue(settledBeforePlate.outlineStrength > .85f)
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("ghostRawUv"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("uOutlineStrength"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("stableMask"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("uMaskTexel"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("shiftedCore"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("edgeSpill"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "vec3(.055,.012,.085)*uOpeningAccentPulse"
        ))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("darkStage"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("uOriginalBackgroundReveal"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("uForegroundMode"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "mix(stageErodedAverage,refinedMask"
        ))
        val foregroundBranch = MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER
            .indexOf("if(uForegroundMode>.5")
        val erodedAverage = MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER
            .indexOf("float stageErodedAverage=", foregroundBranch)
        val erodedAverageUse = MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER
            .indexOf("mix(stageErodedAverage,refinedMask", foregroundBranch)
        assertTrue(erodedAverage in foregroundBranch until erodedAverageUse)
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("maskGradient"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("interiorColour"))
        // Source-contract guard only: visual correctness still requires a device render.
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "interiorRawUv=vec2(interiorMaskUv.x,1.-interiorMaskUv.y)"
        ))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "uIncomingTexMatrix*vec4(interiorRawUv,0.,1.)"
        ))
        assertFalse(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "safeTextureUv+interiorDirection"
        ))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("entranceTravel"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "subjectTextureUv=(uIncomingTexMatrix*vec4(subjectRawUv,0.,1.)).xy"
        ))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "if(uForegroundMode<.5&&uLayerOpacity<.999)result.rgb=applyPost(result.rgb)"
        ))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("vScreenTexCoord"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("vSemanticTexCoord"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("measuredFlow"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("uAttachmentConfidence.z"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("mirrorUv"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("echoUv"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("counterEcho"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("echoPulse"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("uLayerOpacity*1.45"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("uLayerKind>4.5"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("uLayerKind>5.5"))
        assertTrue(
            MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.indexOf("if(uForegroundMode>.5") <
                MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.indexOf("else if(uUseTransition<.5)")
        )
        assertTrue(MediaCodecSpeedRampRenderer.POST_FRAGMENT_SHADER.contains("screenBlend"))
        assertTrue(MediaCodecSpeedRampRenderer.POST_FRAGMENT_SHADER.contains("uAuxiliary"))
        assertTrue(MediaCodecSpeedRampRenderer.POST_FRAGMENT_SHADER.contains("uDepth"))
        assertTrue(MediaCodecSpeedRampRenderer.POST_FRAGMENT_SHADER.contains("separation"))
        assertEquals(2_500L, TransitionTimeline.durationMs(MontageGraph.Transition.FOREGROUND_REENTRY))
        assertEquals(0f, RenderedVisualSampler.clippingArtifact(1f, intentionalDarkFrame = true), .0001f)
        assertTrue(RenderedVisualSampler.clippingArtifact(1f, intentionalDarkFrame = false) > 0f)
        assertTrue(RenderedVisualSampler.isIntentionalForegroundDarkStage(
            MontageGraph.Transition.FOREGROUND_REENTRY,
            .84f
        ))
        assertFalse(RenderedVisualSampler.isIntentionalForegroundDarkStage(
            MontageGraph.Transition.FOREGROUND_REENTRY,
            .90f
        ))
    }

    @Test fun audio_loop_and_sample_clock_end_exactly_on_graph_duration() {
        val segments = AudioExportPlan.loop(trackDurationUs = 1_500_000L, outputDurationUs = 4_000_000L)

        assertEquals(3, segments.size)
        assertEquals(4_000_000L, segments.last().outputStartUs + segments.last().sourceEndUs)
        assertEquals(1_000_000L, AudioExportPlan.presentationTimeUs(48_000L, 48_000))
    }

    @Test fun final_export_contract_requires_portrait_pixels_and_aac() {
        val accepted = ExportContract.validateVmeFinal(
            ExportContract.Probe(1080, 1920, 0, 20_020L, "audio/mp4a-latm"),
            20_000L,
            requireFullHd = true
        )
        val rotated = ExportContract.validateVmeFinal(
            ExportContract.Probe(1920, 1080, 90, 20_000L, null),
            20_000L,
            requireFullHd = true
        )

        assertTrue(accepted.accepted)
        assertTrue(!rotated.accepted)
    }

    @Test fun frame_attachments_are_bound_by_source_pts_not_output_index() {
        val attachment = FrameAttachments(500_000L, mask = FrameAttachments.Plane(1, 1, listOf(1f), .9f))
        val timeline = FrameAttachmentTimeline(listOf(attachment))

        assertNotNull(timeline.nearest(620_000L))
        assertNull(timeline.nearest(800_000L))
    }

    @Test fun semantic_planes_keep_primitive_storage_and_value_equality() {
        // Storage/equality are size-independent; the previous 49,152-cell fixture added no
        // coverage. Keep unequal values so a broken content comparison is still exposed.
        val values = floatArrayOf(0f, .25f, .5f, 1f)
        val plane = FrameAttachments.Plane(2, 2, values, .93f)

        assertEquals(FloatArray::class, plane.values::class)
        assertEquals(plane, plane.copy(values = values.copyOf()))
        assertEquals(plane.hashCode(), plane.copy(values = values.copyOf()).hashCode())
        assertFalse(plane == plane.copy(values = floatArrayOf(0f, .25f, .75f, 1f)))
    }

    @Test fun live_cutout_matte_is_interpolated_on_the_exact_decoder_pts() {
        val left = FrameAttachments(
            1_000_000L,
            mask = FrameAttachments.Plane(2, 1, listOf(0f, .4f), .9f),
            subjectQuality = .6f
        )
        val right = FrameAttachments(
            1_500_000L,
            mask = FrameAttachments.Plane(2, 1, listOf(1f, .8f), 1f),
            subjectQuality = .8f
        )

        val sample = FrameAttachmentTimeline(listOf(left, right)).interpolated(1_250_000L)

        assertNotNull(sample)
        assertEquals(1_250_000L, sample?.sourceTimeUs)
        assertTrue(requireNotNull(left.mask).values.contentEquals(requireNotNull(sample?.mask).values))
        assertSame(left.mask, sample?.mask)
        assertEquals(right.mask, sample?.maskBlendTarget)
        assertEquals(.5f, sample?.maskBlendProgress ?: 0f, .0001f)
        assertEquals(.9f, sample?.mask?.confidence ?: 0f, .0001f)
        assertEquals(.7f, sample?.subjectQuality ?: 0f, .0001f)
    }

    @Test fun foreground_reentry_advances_pts_aligned_mattes_instead_of_retaining_the_boundary() {
        val attachments = (1_000_000L..2_000_000L step 250_000L).mapIndexed { index, pts ->
            FrameAttachments(
                pts,
                mask = FrameAttachments.Plane(1, 1, listOf(.72f + index * .04f), .92f)
            )
        }
        val timeline = FrameAttachmentTimeline(attachments)
        val plan = HighQualityFramePlan.build(
            graph(MontageGraph.Transition.FOREGROUND_REENTRY).copy(frameAttachments = timeline),
            30
        )
        val transitionFrames = plan.frames.filter {
            it.clipIndex == 1 && it.transitionProgress?.let { progress -> progress in 0f..1f } == true
        }

        assertTrue(transitionFrames.size > 10)
        assertTrue(transitionFrames.map { it.sourceTimeUs }.distinct().size > 20)
        assertTrue(transitionFrames.mapNotNull { it.attachments?.sourceTimeUs }.distinct().size > 20)
        assertTrue(transitionFrames.any { it.attachments?.mask != attachments.first().mask })
    }

    @Test fun diagnostic_output_window_rebases_pts_and_keeps_both_boundary_clips() {
        val complete = HighQualityFramePlan.build(graph(MontageGraph.Transition.FOREGROUND_REENTRY), 30)
        val windowed = MediaCodecSpeedRampRenderer.outputWindow(
            complete,
            MediaCodecSpeedRampRenderer.OutputWindow(900_000L, 1_300_000L)
        )

        assertEquals(400_000L, windowed.durationUs)
        assertEquals(0L, windowed.frames.first().outputTimeUs)
        assertTrue(windowed.frames.last().outputTimeUs < windowed.durationUs)
        assertEquals(setOf(0, 1), windowed.frames.map { it.clipIndex }.toSet())
        assertTrue(windowed.frames.filter { it.clipIndex == 1 }.all {
            it.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY
        })
    }

    @Test fun parameter_tracks_are_the_single_animation_contract() {
        val base = graph(MontageGraph.Transition.HARD_CUT)
        val tracks = ParameterTrackFactory.fromLegacy(base)
        val migrated = base.copy(parameterTracks = tracks)
        val frame = HighQualityFramePlan.build(migrated, 30).frames.first()

        assertTrue(tracks.any { it.target.endsWith("transform.scale") })
        assertEquals(base.clips.first().transform.sample(0f).scale, frame.transform.scale, .0001f)
    }

    @Test fun effect_and_overlay_graphs_are_deterministic() {
        val effect = GpuEffectGraph(listOf(
            GpuEffectGraph.Node("glow", GpuEffectGraph.Kind.GLOW, 0L, 1_000L, .8f)
        ))
        val overlay = MontageGraph.Overlay("flash", 0L, 1_000L, "flash", opacity = .6f)

        assertEquals(.8f, effect.sample(500L).glow, 0f)
        assertEquals(.6f, LayerCompositorModel.sample(listOf(overlay), 500L).opacity, 0f)
        val times = listOf(-1L, 0L, 250L, 500L, 750L, 1_000L)
        val first = times.map { effect.sample(it) to LayerCompositorModel.sample(listOf(overlay), it) }
        assertEquals(first, times.reversed().map {
            effect.sample(it) to LayerCompositorModel.sample(listOf(overlay), it)
        }.reversed())
        assertEquals(listOf(0f, 0f, .4f, .8f, .4f, 0f), first.map { it.first.glow })
    }

    @Test fun authored_black_fade_is_monotonic_and_reaches_black() {
        val fade = MontageGraph.Overlay(
            "fade", 1_000L, 2_000L, "black", MontageGraph.BlendMode.MULTIPLY,
            MontageGraph.OverlayKind.BLACK_FADE, 1f, 0f, 0f, 0f
        )

        val samples = listOf(1_000L, 1_250L, 1_500L, 1_750L, 1_999L).map {
            LayerCompositorModel.sample(listOf(fade), it).opacity
        }
        assertTrue(samples.zipWithNext().all { (left, right) -> right >= left })
        assertTrue(samples.last() > .99f)
    }

    @Test fun subject_stage_has_a_readable_hold_between_its_eases() {
        val stage = MontageGraph.Overlay(
            "stage", 1_000L, 2_000L, "subject", MontageGraph.BlendMode.OVERLAY,
            MontageGraph.OverlayKind.SUBJECT_STAGE, .96f
        )

        assertEquals(0f, LayerCompositorModel.sample(listOf(stage), 1_000L).opacity, .001f)
        assertTrue(LayerCompositorModel.sample(listOf(stage), 1_080L).opacity > .9f)
        assertTrue(LayerCompositorModel.sample(listOf(stage), 1_200L).opacity > .9f)
        assertTrue(LayerCompositorModel.sample(listOf(stage), 1_650L).opacity > .9f)
        assertTrue(LayerCompositorModel.sample(listOf(stage), 1_950L).opacity < .3f)
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("float subject=mix(smoothstep"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("float stageEroded=min"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("stageExpanded-subject"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "float edgeTightening=smoothstep(.62,.78,uForegroundReentry)"
        ))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "vec2 coreStep=uMaskTexel*mix(.65,2.88,edgeTightening)"
        ))
        assertTrue(!MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("float outline="))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("stageFaceProtection"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "subject=max(subject,stageFaceProtection*.97*uAttachmentConfidence.x)"
        ))
        val spillPass = MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.indexOf("stageSpill*.88")
        val faceRestore = MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.indexOf(
            "stageFaceProtection*.82"
        )
        assertTrue(spillPass >= 0 && faceRestore > spillPass)
    }

    @Test fun sigma_mirror_has_no_secondary_source_contract() {
        val sigma = graph(MontageGraph.Transition.HARD_CUT).copy(
            metadata = NleProjectMetadata(generator = ReferenceMontageProfile.ID),
            overlays = listOf(MontageGraph.Overlay("pair", 0L, 1_000L, "pair",
                MontageGraph.BlendMode.OVERLAY, MontageGraph.OverlayKind.MIRROR_SLICE, .6f,
                secondaryTimelineStartMs = 0L)))
        val plan = HighQualityFramePlan.build(sigma)
        val mirror = plan.frames.filter { it.layer.kind == MontageGraph.OverlayKind.MIRROR_SLICE }
        assertTrue(mirror.isNotEmpty())
        assertTrue(mirror.all { it.secondarySourceTimeUs == null })
        assertFalse(MediaCodecSpeedRampRenderer.isTemporalLayer(MontageGraph.OverlayKind.MIRROR_SLICE, true))
        assertTrue(MediaCodecSpeedRampRenderer.isTemporalLayer(MontageGraph.OverlayKind.MIRROR_SLICE, false))
        assertFalse(MediaCodecSpeedRampRenderer.isTemporalLayer(MontageGraph.OverlayKind.DOUBLE_EXPOSURE, true))
        val inspector = VeykadRenderInspector.Collector(sigma, plan.frames.size)
        mirror.forEach { inspector.record(it, null, false) }
        assertFalse(inspector.summary().issues.contains("temporal_layer_sources_too_close_to_read"))
        assertFalse(inspector.summary().issues.contains("authored_temporal_layer_missing_second_source_pts"))
    }

    @Test fun sigma_echo_ignores_legacy_secondary_roles_and_keeps_the_live_primary() {
        val original = graph(MontageGraph.Transition.HARD_CUT)
        val sigma = original.copy(metadata = NleProjectMetadata(generator = ReferenceMontageProfile.ID),
            overlays = listOf(MontageGraph.Overlay("echo", 0L, 1_000L, "echo",
                MontageGraph.BlendMode.SCREEN, MontageGraph.OverlayKind.DOUBLE_EXPOSURE, .6f,
                secondaryTimelineStartMs = 1_000L)))
        val frames = HighQualityFramePlan.build(sigma).frames.filter { it.layer.opacity > .01f }
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all { it.secondarySourceTimeUs == null })
        assertTrue(frames.map { it.sourceTimeUs }.distinct().size > 10)
    }

    @Test fun temporal_layers_use_a_distinct_second_source_pts_and_gpu_texture() {
        val temporalGraph = graph(MontageGraph.Transition.HARD_CUT).copy(overlays = listOf(
            MontageGraph.Overlay(
                "double", 500L, 1_000L, "double", MontageGraph.BlendMode.SCREEN,
                MontageGraph.OverlayKind.DOUBLE_EXPOSURE, .6f,
                secondarySourceOffsetMs = 900L
            )
        ))
        val frame = HighQualityFramePlan.build(temporalGraph).frames.first {
            it.outputTimeUs >= 700_000L
        }
        val secondaryUs = MediaCodecSpeedRampRenderer.temporalLayerSourceTimeUs(frame, 2_000_000L)

        assertEquals(frame.sourceTimeUs + 900_000L, secondaryUs)
        assertTrue(secondaryUs != frame.sourceTimeUs)
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("texture2D(uOutgoing,echoUv)"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("faceProtection()"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("readableOpacity"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("readableSplit"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("verticalSeam"))
        assertFalse(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("vScreenTexCoord.y*6.0"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("uOpeningAccentPulse"))

        val inspector = VeykadRenderInspector.Collector(temporalGraph, 1)
        inspector.record(frame, null, dualDecoder = true, secondarySourceTimeUs = secondaryUs)
        assertEquals(1, inspector.summary().temporalLayerFrames)
        assertFalse(inspector.summary().issues.contains("authored_temporal_layer_missing_second_source_pts"))
        assertFalse(inspector.summary().issues.contains("temporal_layer_sources_too_close_to_read"))
        assertNull(inspector.evidence().single().decodedSourceTimeUs)
        val decodedInspector = VeykadRenderInspector.Collector(temporalGraph, 2)
        val texturePts = frame.sourceTimeUs + 20_000L
        for (plannedOffset in listOf(0L, 10_000L)) {
            decodedInspector.record(frame.copy(sourceTimeUs = frame.sourceTimeUs + plannedOffset),
                null, true, secondaryUs, texturePts, secondaryUs + 15_000L)
        }
        assertEquals(2, decodedInspector.evidence().map { it.sourceTimeUs }.distinct().size)
        assertEquals(1, decodedInspector.evidence().map { it.decodedSourceTimeUs }.distinct().size)
        assertEquals(secondaryUs + 15_000L,
            decodedInspector.evidence().first().decodedSecondarySourceTimeUs)
    }

    @Test fun heartbeat_takes_configured_temporal_offset_without_false_positive() {
        // This test concerns the 67ms temporal layer, not absent live source handles.
        // Leave the shared other-product fixture unchanged; give Heartbeat sufficient
        // distinct coverage for its 1.133s live finale instead of silently slowing it.
        val base = pool()
        val heartbeatPool = base.copy(outputDurationMs = 22_500L,
            clips = base.clips.mapIndexed { index, clip -> clip.copy(
                sourceEndMs = clip.sourceStartMs + 1_500L,
                outputDurationMs = 1_500L,
                beatAnchorMs = index * 1_500L
            ) })
        val heartbeat = HeartbeatDirector.build(heartbeatPool, "heartbeat-audio")
        val frame = HighQualityFramePlan.build(heartbeat, 60).frames.first {
            it.layer.kind == MontageGraph.OverlayKind.DOUBLE_EXPOSURE &&
                it.layer.opacity >= .25f && it.sourceTimeUs >= 100_000L
        }
        val secondaryUs = frame.sourceTimeUs - 67_000L
        val inspector = VeykadRenderInspector.Collector(heartbeat, 1)
        inspector.record(frame, null, true, secondarySourceTimeUs = secondaryUs)
        assertFalse(inspector.summary().issues.contains("temporal_layer_sources_too_close_to_read"))
    }

    @Test fun opening_cutout_advances_texture_and_pts_aligned_matte_without_freezing() {
        fun mask(value: Float) = FrameAttachments.Plane(2, 2, List(4) { value }, .92f)
        val masks = (0L..2_000_000L step 250_000L).mapIndexed { index, pts ->
            FrameAttachments(
                pts,
                mask = mask(.55f + index * .02f),
                subjectQuality = .9f,
                maskTemporalIou = .9f
            )
        }
        val opening = MontageGraph(
            sourceDurationMs = 2_000L,
            outputDurationMs = 2_000L,
            clips = listOf(
                clip("opening", 0L, 2_000L, MontageGraph.Transition.FOREGROUND_REENTRY)
            ),
            frameAttachments = FrameAttachmentTimeline(masks)
        )
        val openingFrames = HighQualityFramePlan.build(opening).frames
        assertTrue(openingFrames.map { it.sourceTimeUs }.distinct().size > 50)
        assertTrue(openingFrames.zipWithNext().all { (left, right) ->
            right.sourceTimeUs >= left.sourceTimeUs
        })
        assertTrue(openingFrames.all { frame ->
            frame.attachments != null &&
                kotlin.math.abs(frame.sourceTimeUs - frame.attachments.sourceTimeUs) <= 125_000L
        })
        val attachmentPts = openingFrames.mapNotNull { it.attachments?.sourceTimeUs }.distinct()
        assertTrue("attachmentPts=$attachmentPts", attachmentPts.size >= 5)
        val openingInspector = VeykadRenderInspector.Collector(opening, openingFrames.size)
        openingFrames.forEach { frame ->
            openingInspector.record(frame, TransitionTimeline.blendFor(frame), dualDecoder = false)
        }
        assertTrue(openingInspector.summary().foregroundReentrySourcePts > 50)
        assertFalse(openingInspector.summary().issues.contains("foreground_reentry_source_frozen"))

        val stage = opening.copy(
            clips = listOf(clip("stage", 0L, 2_000L, MontageGraph.Transition.OPEN)),
            overlays = listOf(
                MontageGraph.Overlay(
                    "stage", 500L, 1_500L, "subject", MontageGraph.BlendMode.OVERLAY,
                    MontageGraph.OverlayKind.SUBJECT_STAGE, .9f
                )
            )
        )
        val liveStage = HighQualityFramePlan.build(stage).frames.filter {
            it.layer.kind == MontageGraph.OverlayKind.SUBJECT_STAGE
        }
        assertTrue(liveStage.isNotEmpty())
        assertTrue(liveStage.map { it.sourceTimeUs }.distinct().size > 20)
        assertTrue(liveStage.zipWithNext().all { (left, right) ->
            right.sourceTimeUs >= left.sourceTimeUs
        })
        assertTrue(liveStage.mapNotNull { it.attachments?.sourceTimeUs }.distinct().size >= 3)
        val stagePlan = HighQualityFramePlan.build(stage)
        val stageInspector = VeykadRenderInspector.Collector(stage, stagePlan.frames.size)
        stagePlan.frames.forEach { frame ->
            stageInspector.record(frame, TransitionTimeline.blendFor(frame), dualDecoder = false)
        }
        assertTrue(stageInspector.summary().subjectStageSourcePts > 20)
        assertFalse(stageInspector.summary().issues.contains("subject_stage_source_frozen"))
    }

    private fun pool() = MontageGraph(
        sourceDurationMs = 30_000L,
        outputDurationMs = 15_000L,
        clips = (0 until 15).map { i ->
            MontageGraph.Clip(
                "pool-$i",
                i * 1_500L,
                i * 1_500L + 1_000L,
                1_000L,
                MontageGraph.ShotRole.CLOSE,
                MontageGraph.Transition.FOREGROUND_REENTRY,
                MontageGraph.Motion.PUSH_IN,
                0.9f,
                i * 1_000L
            )
        }
    )

    private fun graph(transition: MontageGraph.Transition): MontageGraph {
        val clips = listOf(
            clip("a", 0L, 1_000L, MontageGraph.Transition.OPEN),
            clip("b", 1_000L, 1_000L, transition)
        )
        return MontageGraph(2_000L, 2_000L, clips = clips)
    }

    private fun clip(
        id: String,
        sourceStartMs: Long,
        outputDurationMs: Long,
        transition: MontageGraph.Transition = MontageGraph.Transition.OPEN
    ) = MontageGraph.Clip(
        id = id,
        sourceStartMs = sourceStartMs,
        sourceEndMs = sourceStartMs + 1_000L,
        outputDurationMs = outputDurationMs,
        role = if (id == "a") MontageGraph.ShotRole.OPENING else MontageGraph.ShotRole.FINALE,
        transitionIn = transition,
        motion = if (transition == MontageGraph.Transition.WHIP) MontageGraph.Motion.WHIP_LEFT else MontageGraph.Motion.HOLD,
        confidence = .9f,
        beatAnchorMs = sourceStartMs
    )
}
