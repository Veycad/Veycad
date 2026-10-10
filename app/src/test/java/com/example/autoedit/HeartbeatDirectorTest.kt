package com.veycad.app

import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class HeartbeatDirectorTest {
    @Test fun short_profile_gap_needs_bracketing_faces_stable_mask_and_no_occlusion() {
        val face=FrameAttachments.FaceRegion(.5f,.4f,.4f,.4f,.9f)
        val frames=(0L..1_000_000L step 250_000L).map { t -> FrameAttachments(t,
            mask=FrameAttachments.Plane(1,1,floatArrayOf(1f),.96f),subjectQuality=.53f,
            maskTemporalIou=.8f,faceRegion=if(t==500_000L) null else face)
            .let { if(it.faceRegion!=null) it.copy(subjectQuality=.8f) else it } }
        fun accepts(items:List<FrameAttachments>)=HeartbeatDirector.readableExtension(FrameAttachmentTimeline(items),0,1000)
        assertTrue(accepts(frames))
        assertFalse(HeartbeatDirector.readableExtension(FrameAttachmentTimeline(frames),0,1000,false))
        assertFalse(accepts(frames.map { if(it.sourceTimeUs==750_000L) it.copy(faceRegion=null) else it }))
        assertFalse(accepts(frames.map { it.copy(faceRegion=null) }))
        assertFalse(accepts(frames.map { if(it.faceRegion==null) it.copy(mask=null,depth=it.mask) else it }))
        assertFalse(accepts(frames.map { if(it.faceRegion==null) it.copy(maskTemporalIou=.3f) else it }))
        assertFalse(accepts(frames.map { if(it.faceRegion==null) it.copy(subjectOcclusion=.8f) else it }))
    }
    @Test fun paired_handle_control_does_not_slow_an_unreadable_centred_handle() {
        val evidence = FrameAttachmentTimeline((0L..30_000_000L step 250_000L).map { t ->
            FrameAttachments(t, mask=FrameAttachments.Plane(1,1,floatArrayOf(1f),1f),
                subjectQuality=.8f, subjectOcclusion=if(t==1_750_000L) .8f else 0f,
                faceRegion=FrameAttachments.FaceRegion(.5f,.4f,.4f,.4f,.9f))
        })
        val original = pool().let { graph -> graph.copy(frameAttachments=evidence,
            clips=graph.clips.mapIndexed { i,c -> when(i) {
                0 -> c.copy(sourceStartMs=27_500,sourceEndMs=29_000)
                4 -> c.copy(sourceStartMs=1200,sourceEndMs=1400)
                else -> c
            } }) }
        val candidate=HeartbeatDirector.build(original,"audio",0L,allowShiftedHandles=true)
        val control=assertThrows(MaterialRejectedException::class.java) {
            HeartbeatDirector.build(original,"audio",0L,allowShiftedHandles=false)
        }
        assertEquals("insufficient_distinct_moments",control.code)
        val finale = candidate.clips[HeartbeatMontageProfile.scenes.lastIndex]
        assertTrue(finale.sourceStartMs != 1300L - finale.outputDurationMs / 2L)
        candidate.clips.take(26).forEach { clip ->
            assertEquals(clip.outputDurationMs,clip.sourceEndMs-clip.sourceStartMs)
        }
        assertEquals(1200L,original.clips[4].sourceStartMs)
        assertEquals(1400L,original.clips[4].sourceEndMs)
    }
    @Test fun extension_can_shift_away_from_bad_handle_without_discarding_selected_event() {
        val frames=(0L..2_000_000L step 250_000L).map { t -> FrameAttachments(t,
            mask=FrameAttachments.Plane(1,1,floatArrayOf(1f),1f),subjectQuality=.8f,
            subjectOcclusion=if(t==1_750_000L) .8f else 0f,
            faceRegion=FrameAttachments.FaceRegion(.5f,.4f,.4f,.4f,.9f)) }
        val timeline=FrameAttachmentTimeline(frames)
        assertFalse(HeartbeatDirector.readableExtension(timeline,800,1800))
        val start=HeartbeatDirector.readableWindowStart(timeline,1300,1000,2000)!!
        assertTrue(kotlin.math.abs(start-800)<=250)
        assertTrue(1300L in start until start+1000)
        assertTrue(HeartbeatDirector.readableExtension(timeline,start,start+1000))
        val unsafe=FrameAttachmentTimeline(frames.map { if(it.sourceTimeUs==1_250_000L)
            it.copy(subjectOcclusion=.8f) else it })
        assertNull(HeartbeatDirector.readableWindowStart(unsafe,1300,1000,2000))
        assertNull(HeartbeatDirector.readableWindowStart(timeline,1300,3000,2000))
    }
    @Test fun slow_slot_extension_requires_continuous_readable_face_evidence() {
        fun frame(t:Long)=FrameAttachments(t,mask=FrameAttachments.Plane(1,1,floatArrayOf(1f),1f),
            subjectQuality=.8f,faceRegion=FrameAttachments.FaceRegion(.5f,.4f,.4f,.4f,.9f))
        val frames=(0L..1_000_000L step 250_000L).map(::frame)
        assertTrue(HeartbeatDirector.readableExtension(FrameAttachmentTimeline(frames),0,1000))
        assertFalse(HeartbeatDirector.readableExtension(FrameAttachmentTimeline(),0,1000))
        assertFalse(HeartbeatDirector.readableExtension(FrameAttachmentTimeline(listOf(frames.first(),frames.last())),0,1000))
        assertFalse(HeartbeatDirector.readableExtension(FrameAttachmentTimeline(frames.mapIndexed { i,f ->
            if(i==2) f.copy(subjectOcclusion=.8f) else f }),0,1000))
        assertFalse(HeartbeatDirector.readableExtension(FrameAttachmentTimeline(frames),0,1500))
        val fullTimeline=FrameAttachmentTimeline((0L..30_000_000L step 250_000L).map(::frame))
        val graph=HeartbeatDirector.build(pool().copy(frameAttachments=fullTimeline),"audio")
        graph.clips.take(26).forEach { clip ->
            assertEquals(clip.outputDurationMs,clip.sourceEndMs-clip.sourceStartMs)
            assertTrue(clip.sourceStartMs>=0 && clip.sourceEndMs<=graph.sourceDurationMs)
        }
    }
    @Test fun inspector_distinguishes_constant_curve_from_absolute_source_speed() {
        val graph=HeartbeatDirector.build(pool(),"audio")
        val collector=VeykadRenderInspector.Collector(graph,3)
        val frame=HighQualityFramePlan.build(graph,60).frames.first()
        collector.record(frame.copy(sourceTimeUs=0),null,false)
        collector.record(frame.copy(outputTimeUs=20_000,sourceTimeUs=60_000),null,false)
        collector.record(frame.copy(outputTimeUs=40_000,sourceTimeUs=60_000,clipIndex=1),null,false)
        assertNull(collector.evidence()[0].effectiveSourceSpeed)
        assertEquals(1f,collector.evidence()[1].speed,0f)
        assertEquals(3f,collector.evidence()[1].effectiveSourceSpeed!!,0f)
        assertNull(collector.evidence()[2].effectiveSourceSpeed)
    }
    @Test fun explicit_synchronous_recipe_matches_the_existing_control_without_changing_other_layers() {
        val original = HeartbeatDirector.build(pool(),"audio")
        assertEquals(HeartbeatDirector.synchronousEchoControl(original),
            HeartbeatDirector.build(pool(),"audio",echoOffsetMs = 0L))
    }
    @Test fun inspector_records_face_actually_attached_to_render_frame() {
        val graph = HeartbeatDirector.build(pool(),"audio")
        val face = FrameAttachments.FaceRegion(.4f,.42f,.8f,.46f,.9f)
        val frame = HighQualityFramePlan.build(graph,60).frames.first().copy(
            attachments = FrameAttachments(0,mask = FrameAttachments.Plane(1,1,floatArrayOf(1f),1f),faceRegion = face))
        val collector = VeykadRenderInspector.Collector(graph,2)
        collector.record(frame,null,false)
        collector.record(frame.copy(attachments = null),null,false)
        assertEquals(face,collector.evidence()[0].faceRegion)
        assertNull(collector.evidence()[1].faceRegion)
    }
    @Test fun synchronous_control_removes_only_echo_lag_and_preserves_legacy_default() {
        val graph = HeartbeatDirector.build(pool(), "audio")
        val control = HeartbeatDirector.synchronousEchoControl(graph)
        assertEquals(graph.copy(overlays = control.overlays), control)
        graph.overlays.zip(control.overlays).forEach { (a, b) ->
            assertEquals(if (a.kind == "heartbeat-echo-experimental")
                a.copy(secondarySourceOffsetMs = 0L) else a, b)
        }
        val frames = HighQualityFramePlan.build(control,60).frames
        val echo = frames.first { it.layer.heartbeatEcho }
        assertEquals(echo.sourceTimeUs,
            MediaCodecSpeedRampRenderer.temporalLayerSourceTimeUs(echo,30_000_000L))
        val legacy = echo.copy(layer = echo.layer.copy(heartbeatEcho = false))
        assertEquals((echo.sourceTimeUs - 420_000L).coerceAtLeast(0L),
            MediaCodecSpeedRampRenderer.temporalLayerSourceTimeUs(legacy,30_000_000L))
        HighQualityFramePlan.build(graph,60).frames.zip(frames).forEach { (a,b) ->
            assertEquals(a.sourceTimeUs,b.sourceTimeUs)
            assertEquals(a.outputTimeUs,b.outputTimeUs)
            assertEquals(a.effects,b.effects)
        }
    }
    @Test fun shader_source_contract_declares_external_samplers_separately() {
        val shader = MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER
        assertTrue(shader.contains("uniform samplerExternalOES uIncoming;"))
        assertTrue(shader.contains("uniform samplerExternalOES uOutgoing;"))
        assertFalse(Regex("samplerExternalOES[^;]*,").containsMatchIn(shader))
    }
    @Test fun echo_control_changes_only_shader_route_not_footage_or_timing() {
        val graph = HeartbeatDirector.build(pool(), "audio")
        val control = HeartbeatDirector.legacyEchoControl(graph)
        assertEquals(graph.copy(overlays = control.overlays), control)
        graph.overlays.zip(control.overlays).forEach { (a,b) ->
            assertEquals(a.copy(kind = b.kind), b)
        }
        assertTrue(LayerCompositorModel.sample(graph.overlays,12_000).heartbeatEcho)
        assertFalse(LayerCompositorModel.sample(control.overlays,12_000).heartbeatEcho)
        val a = HighQualityFramePlan.build(graph,60).frames
        val b = HighQualityFramePlan.build(control,60).frames
        assertEquals(a.size,b.size)
        a.zip(b).forEach { (x,y) ->
            assertEquals(x.sourceTimeUs,y.sourceTimeUs)
            assertEquals(x.outputTimeUs,y.outputTimeUs)
            assertEquals(x.effects,y.effects)
            assertEquals(x.layer.copy(heartbeatEcho = false),y.layer)
        }
    }
    @Test fun heartbeat_echo_plan_and_shader_source_contract_preserve_separate_legacy_route() {
        val graph = HeartbeatDirector.build(pool(),"audio")
        assertTrue(LayerCompositorModel.sample(graph.overlays,12_000).heartbeatEcho)
        val legacy = MontageGraph.Overlay("legacy",0,1000,"reference-double-exposure",
            overlayKind = MontageGraph.OverlayKind.DOUBLE_EXPOSURE,opacity = .45f)
        assertFalse(LayerCompositorModel.sample(listOf(legacy),500).heartbeatEcho)
        val frame = HighQualityFramePlan.build(graph,60).frames.first { it.outputTimeUs == 12_000_000L }
        assertTrue(frame.layer.heartbeatEcho)
        assertEquals(frame.sourceTimeUs - 67_000L,
            MediaCodecSpeedRampRenderer.temporalLayerSourceTimeUs(frame,30_000_000L))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("if(uHeartbeatEcho>.5)"))
        // Structural shader guard only; visual approval still requires decoded MP4 review.
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("texture2D(uOutgoing,trailUv3).rgb*.15"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "float echoStrength=clamp(uLayerOpacity/.62,0.,1.)"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "float zoomStep=.05+.17*echoStrength"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "vec2 trailDrift=vec2(.038,-.014)*echoStrength"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("(1.+zoomStep*3.)"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "float trailMask=texture2D(uMask,vec2(rawTrail1.x,1.-rawTrail1.y)).r*.55"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "float maskReliability=smoothstep(.72,.92,uAttachmentConfidence.x)"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "float silhouetteGate=mix(1.,.18+.82*smoothstep(.24,.66,trailMask),maskReliability)"))
        assertFalse(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("vec2 stepOffset="))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("vec2(uFaceRegion.x,1.-uFaceRegion.y)"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("uOutgoingTexMatrix*vec4(faceRawUv,0.,1.)"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "float faceRestore=mix(.70,.30,clamp(uLayerOpacity/.62,0.,1.))"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "readableFace*faceRestore"))
        assertFalse(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("readableFace*.95"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains("uOpeningTitleTexture"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "titleAlpha*uTitleBaseOpacity*uTitleOpacity"))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "if(uHeartbeatProfile>.5&&exposure<=1.)c=pow(c,vec3(1.10))"))
    }
    @Test fun heartbeat_reprise_uses_measured_resolve_persist_and_build_envelopes() {
        val graph = HeartbeatDirector.build(pool(), "audio")
        val echoLayers = graph.overlays.filter { it.kind == "heartbeat-echo-experimental" }
        val sceneLayers = echoLayers.filterNot { it.id == "heartbeat-finale-echo-stutter" }
        assertEquals(11, sceneLayers.size)
        sceneLayers.take(4).forEach { layer ->
            assertEquals(layer.opacity, LayerCompositorModel.sample(listOf(layer), layer.startMs).opacity, 0f)
            val middle = LayerCompositorModel.sample(listOf(layer), (layer.startMs + layer.endMs) / 2L).opacity
            assertEquals(layer.opacity * .5f, middle, .005f)
            assertTrue(LayerCompositorModel.sample(listOf(layer), layer.endMs - 1L).opacity < .001f)
        }
        val persistent = sceneLayers[4]
        assertEquals(persistent.opacity, LayerCompositorModel.sample(
            listOf(persistent), persistent.startMs).opacity, 0f)
        assertEquals(persistent.opacity * .5f, LayerCompositorModel.sample(
            listOf(persistent), (persistent.startMs + persistent.endMs) / 2L).opacity, .005f)
        assertEquals(persistent.opacity * .22f, LayerCompositorModel.sample(
            listOf(persistent), persistent.endMs - 1L).opacity, .005f)
        sceneLayers.drop(5).forEach { layer ->
            val start = LayerCompositorModel.sample(listOf(layer), layer.startMs).opacity
            val middle = LayerCompositorModel.sample(listOf(layer),
                (layer.startMs + layer.endMs) / 2L).opacity
            val end = LayerCompositorModel.sample(listOf(layer), layer.endMs - 1L).opacity
            assertEquals(layer.opacity * .18f, start, .001f)
            assertTrue(start < middle)
            assertTrue(middle < end)
            assertEquals(layer.opacity, end, .005f)
        }
        val finale = echoLayers.single { it.id == "heartbeat-finale-echo-stutter" }
        assertEquals(.62f, LayerCompositorModel.sample(listOf(finale), finale.startMs).opacity, 0f)
        assertEquals(.62f, LayerCompositorModel.sample(listOf(finale), finale.endMs - 1L).opacity, 0f)
        val legacy = MontageGraph.Overlay("legacy", 0, 1_000, "reference-double-exposure",
            overlayKind = MontageGraph.OverlayKind.DOUBLE_EXPOSURE, opacity = .45f)
        assertEquals(0f, LayerCompositorModel.sample(listOf(legacy), 0).opacity, 0f)
        assertTrue(LayerCompositorModel.sample(listOf(legacy), 999).opacity <
            LayerCompositorModel.sample(listOf(legacy), 500).opacity)
    }

    @Test fun opening_uses_its_measured_dark_grade_without_dimming_the_following_scene() {
        val base = pool()
        val graph = HeartbeatDirector.build(base, "audio")

        assertEquals(
            base.clips.first().exposureBias + HeartbeatMontageProfile.OPENING_EXPOSURE_BIAS,
            graph.clips.first().exposureBias,
            .0001f
        )
        assertEquals(base.clips[1].exposureBias, graph.clips[1].exposureBias, .0001f)
    }

    @Test fun finale_returns_to_a_readable_human_motif_and_builds_to_the_black_cut() {
        val graph = HeartbeatDirector.build(pool(), "audio")
        val finaleIndex = HeartbeatMontageProfile.scenes.lastIndex
        val finale = graph.clips[finaleIndex]
        val scene = HeartbeatMontageProfile.scenes.last()
        val track = graph.parameterTracks.single { it.id == "heartbeat-finale-grade" }

        assertTrue(finale.id.endsWith("role-${HeartbeatMontageProfile.FINALE_PORTRAIT_ROLE}"))
        assertEquals(ParameterTargets.clip(finale.id, "grade.rgbaBias"), track.target)
        assertEquals(scene.startUs, track.keyframes.first().timeUs)
        assertEquals(scene.endUs, track.keyframes.last().timeUs)
        assertEquals(finale.exposureBias + .16f, track.sample(scene.startUs).last(), .0001f)
        assertEquals(finale.exposureBias + .85f,
            track.sample(scene.startUs + 250_000L).last(), .0001f)
        assertEquals(HeartbeatMontageProfile.LAST_VISUAL_END_US, scene.endUs)
    }
    @Test fun finale_grade_adapts_to_selected_source_luma_and_drops_after_dark_punctuation() {
        val base = pool()
        val finaleSource = base.clips[HeartbeatMontageProfile.FINALE_PORTRAIT_ROLE]
        val observations = listOf(
            (finaleSource.sourceStartMs + finaleSource.sourceEndMs) / 2L - 200L,
            (finaleSource.sourceStartMs + finaleSource.sourceEndMs) / 2L,
            (finaleSource.sourceStartMs + finaleSource.sourceEndMs) / 2L + 200L
        ).map { timeMs ->
            VisualEventMap.Observation(
                sourceTimeUs = timeMs * 1_000L,
                face = VisualEventMap.Face(.9f, 0f),
                personMaskConfidence = .95f,
                meanLuma = .62f,
                composition = VisualEventMap.Composition(.55f, .8f, .8f, .7f, .8f)
            )
        }
        val graph = HeartbeatDirector.build(
            base,
            "audio",
            visualMap = VisualEventMap(base.sourceDurationMs * 1_000L, emptyList(), observations)
        )
        val track = graph.parameterTracks.single { it.id == "heartbeat-finale-grade" }
        val scene = HeartbeatMontageProfile.scenes.last()
        val plateau = track.sample(scene.startUs + 250_000L).last()
        val echoEntry = track.sample(HeartbeatMontageProfile.FINALE_ECHO_START_US).last()
        val echoExit = track.sample(HeartbeatMontageProfile.FINALE_ECHO_END_US).last()
        assertEquals(6, track.keyframes.size)
        // Apply the documented grade to the measured source. Calling the production
        // exposure helper to calculate the expectation would hide a shared inversion bug.
        assertEquals(.72f, (.62f * (1f + plateau)).pow(1.10f), .0001f)
        assertTrue(echoEntry < plateau)
        assertTrue(echoExit < echoEntry)
        assertEquals(echoExit, track.sample(scene.endUs).last(), .0001f)
    }
    @Test fun ordinary_scene_grade_uses_its_measured_reference_luma_without_replacing_accent_curves() {
        val base = pool()
        val observations = base.clips.flatMap { clip ->
            val centreMs = (clip.sourceStartMs + clip.sourceEndMs) / 2L
            listOf(centreMs - 100L, centreMs + 100L).map { timeMs ->
                VisualEventMap.Observation(
                    sourceTimeUs = timeMs * 1_000L,
                    meanLuma = .60f,
                    face = null,
                    composition = VisualEventMap.Composition(.5f, .8f, .8f, .8f, .8f)
                )
            }
        }.sortedBy { it.sourceTimeUs }
        val visual = VisualEventMap(base.sourceDurationMs * 1_000L, emptyList(), observations)
        val graph = HeartbeatDirector.build(base, "audio", visualMap = visual)
        val ordinary = graph.parameterTracks.filter { it.id.startsWith("heartbeat-scene-grade-") }
        assertEquals(23, ordinary.size)
        assertTrue(ordinary.none { it.id in setOf(
            "heartbeat-scene-grade-9", "heartbeat-scene-grade-20", "heartbeat-scene-grade-25"
        ) })
        val scene = HeartbeatMontageProfile.scenes[1]
        val track = ordinary.single { it.id == "heartbeat-scene-grade-1" }
        assertEquals(.336f, (.60f * (1f + track.sample(scene.startUs).last())).pow(1.10f), .0001f)
        assertEquals(track.sample(scene.startUs).last(), track.sample(scene.endUs).last(), 0f)
        assertEquals(2, graph.parameterTracks.count { it.id.startsWith("heartbeat-light-grade-") })
        assertEquals(1, graph.parameterTracks.count { it.id == "heartbeat-finale-grade" })
    }
    @Test fun measured_light_role_uses_grade_curve_without_displacing_reprise_echo() {
        val graph = HeartbeatDirector.build(pool(), "audio")
        assertEquals(2, graph.parameterTracks.count { it.id.startsWith("heartbeat-light-grade-") })
        val frames = HighQualityFramePlan.build(graph, 60).frames
        assertEquals(3f, frames.single { it.outputTimeUs == 7_500_000L }.exposureBias, .0001f)
        val reprise = frames.single { it.outputTimeUs == 15_600_000L }
        assertEquals(3f, reprise.exposureBias, .0001f)
        assertTrue(reprise.layer.heartbeatEcho)
        assertEquals(.44104f, reprise.layer.opacity, .002f)
        val entry = frames.single { it.outputTimeUs == 15_300_000L }
        assertEquals(.75f, entry.exposureBias, .0001f)
        val exit = frames.single { it.outputTimeUs == 15_783_333L }
        assertEquals(1.750005f, exit.exposureBias, .001f)
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "uHeartbeatProfile>.5&&exposure>1."))
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "1.-exp(-exposure*.55)"))
    }
    @Test fun measured_light_peak_keeps_the_current_sources_distinct_role_and_live_motion() {
        val base = pool()
        val distant = base.clips[9]
        fun evidence(clip: MontageGraph.Clip, face: FrameAttachments.FaceRegion) =
            FrameAttachments((clip.sourceStartMs + clip.sourceEndMs) * 500L,
                mask = FrameAttachments.Plane(1, 1, floatArrayOf(1f), 1f), faceRegion = face)
        val timeline = FrameAttachmentTimeline(listOf(
            evidence(distant, FrameAttachments.FaceRegion(.57f, .3125f, .10f, .06f, .9f))
        ))
        val graph = HeartbeatDirector.build(base.copy(frameAttachments = timeline), "audio")
        val first = graph.clips[9].transform
        val reprise = graph.clips[20].transform
        assertTrue(graph.clips[9].id.endsWith("role-9"))
        assertTrue(graph.clips[20].id.endsWith("role-9"))
        assertEquals(first, reprise)
        assertFalse(first == MontageGraph.ClipTransform.hold())
        assertTrue(graph.clips[9].sourceStartMs >= distant.sourceStartMs)
        assertTrue(graph.clips[9].sourceEndMs <= distant.sourceEndMs)
        val live = HighQualityFramePlan.build(graph, 60).frames.filter { it.clipIndex == 20 }
        assertTrue(live.zipWithNext().all { (a, b) -> b.sourceTimeUs > a.sourceTimeUs })
    }
    @Test fun wide_face_light_peak_uses_soft_zoom_instead_of_wide_crop_hold() {
        val base = pool()
        val selected = base.clips[9]
        val centreUs = (selected.sourceStartMs + selected.sourceEndMs) * 500L
        val timeline = FrameAttachmentTimeline(listOf(
            FrameAttachments(centreUs, mask = FrameAttachments.Plane(1, 1, floatArrayOf(1f), 1f),
                faceRegion = FrameAttachments.FaceRegion(.56f, .34f, .23f, .17f, .88f))
        ))
        val clip = selected.copy()
        val transform = HeartbeatDirector.lightPortraitTransform(clip, timeline)
        assertNotEquals(MontageGraph.ClipTransform.hold(), transform)
        assertTrue(transform.keyframes.size >= 2)
        assertTrue(transform.keyframes[0].scale > 1.04f)
        assertTrue(transform.keyframes[0].scale < 1.16f)
        assertEquals(transform.keyframes[0].scale, transform.keyframes[1].scale, 0f)
    }
    @Test fun backlit_slot_preserves_its_authored_role_and_framing_without_sigma_stage() {
        val base = pool()
        val role = base.clips[8]
        val face = FrameAttachments.FaceRegion(.68f, .31f, .255f, .14f, .92f)
        val evidence = FrameAttachmentTimeline((0L..30_000_000L step 250_000L).map { timeUs ->
            FrameAttachments(timeUs, mask = FrameAttachments.Plane(1, 1, floatArrayOf(1f), .94f),
                subjectQuality = .72f, faceRegion = face)
        })
        val graph = HeartbeatDirector.build(base.copy(frameAttachments = evidence), "audio")
        listOf(8, 19).forEach { sceneIndex ->
            val clip = graph.clips[sceneIndex]
            assertTrue(clip.id.endsWith("role-8"))
            assertEquals((role.sourceStartMs + role.sourceEndMs) / 2L - clip.outputDurationMs / 2L,
                clip.sourceStartMs)
            assertEquals(clip.outputDurationMs, clip.sourceEndMs - clip.sourceStartMs)
            assertTrue(clip.sourceEndMs <= graph.sourceDurationMs)
            val live = HighQualityFramePlan.build(graph, 60).frames.filter { it.clipIndex == sceneIndex }
            assertTrue(live.zipWithNext().all { (a, b) -> b.sourceTimeUs > a.sourceTimeUs })
        }
        assertEquals(MontageGraph.ClipTransform.hold(), graph.clips[8].transform)
        assertEquals(MontageGraph.ClipTransform.hold(), graph.clips[19].transform)
        assertEquals(MontageGraph.Transition.HARD_CUT, graph.clips[8].transitionIn)
        assertEquals(MontageGraph.Transition.HARD_CUT, graph.clips[19].transitionIn)
        val entrance = HighQualityFramePlan.build(graph, 60).frames.filter { it.clipIndex == 8 }
        assertTrue(entrance.mapNotNull(TransitionTimeline::blendFor).isEmpty())
        // Authored cuts are measured at 60 fps while clip durations are stored in whole
        // milliseconds. Assert the renderer's first scheduled frame samples the same effect
        // clock instead of pretending that it lands exactly on the sub-millisecond cut PTS.
        assertEquals(graph.effectGraph.sample(entrance.first().outputTimeUs).defocus,
            entrance.first().effects.defocus, 0f)
        assertTrue(entrance.first().effects.defocus > .7f)
        assertNull(graph.clips[8].transitionDurationMs)
        assertTrue(OpeningMatteRefinement.targets(graph).isEmpty())
        assertTrue(graph.overlays.none { it.overlayKind == MontageGraph.OverlayKind.SUBJECT_STAGE })
    }
    @Test fun animal_roles_remain_available_outside_the_human_isolation_slot() {
        val graph = HeartbeatDirector.build(pool(), "audio")
        // Role 7 is the verified dog/couch moment on this fixture. Remapping the authored
        // portrait role must not remove ordinary or reprise animal shots.
        assertTrue(graph.clips[7].id.endsWith("role-7"))
        assertTrue(graph.clips[18].id.endsWith("role-7"))
        assertEquals(MontageGraph.Transition.HARD_CUT, graph.clips[7].transitionIn)
        assertEquals(MontageGraph.Transition.HARD_CUT, graph.clips[18].transitionIn)
    }
    @Test fun clean_subjectless_interval_can_replace_one_repeated_portrait_role() {
        val base = pool()
        val observations = (0L until 30_000_000L step 250_000L).map { timeUs ->
            val inAnimalInsert = timeUs in 25_250_000L..26_750_000L
            VisualEventMap.Observation(
                sourceTimeUs = timeUs,
                cameraMotion = if (inAnimalInsert) VisualEventMap.Vector(.08f, 0f) else VisualEventMap.Vector(),
                subjectMotion = if (inAnimalInsert) VisualEventMap.Vector(.06f, 0f) else VisualEventMap.Vector(),
                face = if (inAnimalInsert) null else VisualEventMap.Face(.9f, 0f),
                faceInferenceSucceeded = true,
                visualQuality = if (inAnimalInsert) .91f else .62f,
                meanLuma = .5f,
                composition = VisualEventMap.Composition(.35f, .8f, .8f, .8f, .8f)
            )
        }
        val visual = VisualEventMap(30_000_000L, emptyList(), observations)
        val selected = HeartbeatDirector.selectDistinctSubjectlessInterlude(base, visual)
        assertNotNull(selected)
        assertTrue(selected!!.sourceStartMs in 24_700L..26_300L)
        val graph = HeartbeatDirector.build(base, "audio", visualMap = visual)
        listOf(4, 15).forEach { sceneIndex ->
            assertTrue(graph.clips[sceneIndex].id.endsWith("role-15"))
            assertTrue(graph.clips[sceneIndex].sourceStartMs in 24_700L..26_300L)
        }
        assertTrue(graph.clips[12].id.endsWith("role-12"))
        assertTrue(graph.clips[11].id.endsWith("role-11"))
        assertTrue(graph.clips.last { it.id != "heartbeat-black-tail" }.id.endsWith("role-4"))
        assertTrue(graph.clips.last().id == "heartbeat-black-tail")
    }
    @Test fun unknown_faces_do_not_supply_a_measured_subjectless_interlude() {
        val observations = (0L until 30_000_000L step 250_000L).map { timeUs ->
            VisualEventMap.Observation(timeUs,
                cameraMotion = VisualEventMap.Vector(.08f), subjectMotion = VisualEventMap.Vector(.06f),
                face = null, faceInferenceSucceeded = false, visualQuality = .91f,
                composition = VisualEventMap.Composition(.35f, .8f, .8f, .8f, .8f))
        }
        val unknown = VisualEventMap(30_000_000L, emptyList(), observations)
        assertNull(HeartbeatDirector.selectDistinctSubjectlessInterlude(pool(), unknown))
        // The same actual pictures are eligible only after successful face inference finds no face.
        val measuredEmpty = unknown.copy(observations = observations.map { it.copy(faceInferenceSucceeded = true) })
        assertNotNull(HeartbeatDirector.selectDistinctSubjectlessInterlude(pool(), measuredEmpty))
    }
    @Test fun stable_human_matte_does_not_import_sigma_isolation_into_heartbeat() {
        val base = pool()
        val stableRole = 11
        val stable = base.clips[stableRole]
        val timeline = FrameAttachmentTimeline((0L..30_000_000L step 250_000L).map { timeUs ->
            val isStable = timeUs / 1_000L in stable.sourceStartMs..stable.sourceEndMs
            FrameAttachments(
                sourceTimeUs = timeUs,
                mask = FrameAttachments.Plane(1, 1, floatArrayOf(1f), if (isStable) .96f else .70f),
                subjectQuality = if (isStable) .88f else .52f,
                subjectOcclusion = 0f,
                maskTemporalIou = if (isStable) .84f else .50f,
                faceRegion = FrameAttachments.FaceRegion(
                    .5f, .38f, if (isStable) .34f else .8f, .3f, .9f
                )
            )
        })
        val visual = VisualEventMap(30_000_000L, emptyList(),
            (0L until 30_000_000L step 250_000L).map { timeUs ->
                VisualEventMap.Observation(timeUs, face = VisualEventMap.Face(.9f, 0f))
            })
        val graph = HeartbeatDirector.build(
            base.copy(frameAttachments = timeline), "audio", visualMap = visual
        )
        val entrance = graph.clips[8]
        assertEquals(MontageGraph.Transition.HARD_CUT, entrance.transitionIn)
        assertNull(entrance.transitionDurationMs)
        assertTrue(entrance.id.startsWith("heartbeat-8-role-"))
        assertEquals(MontageGraph.Transition.HARD_CUT, graph.clips[19].transitionIn)
        assertTrue(graph.clips.none { it.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY })
        assertTrue(OpeningMatteRefinement.targets(graph).isEmpty())
    }
    @Test fun qa_samples_short_pulses_and_defocus_instead_of_only_uniform_checkpoints() {
        val graph = HeartbeatDirector.build(pool(),"audio")
        val times = RenderedVisualSampler.samplingTargets(graph,100_000)
        HeartbeatMontageProfile.pulses.forEach { assertTrue(times.contains(it.startUs)) }
        graph.effectGraph.nodes.forEach { assertTrue(times.contains(it.startUs)) }
        assertEquals(times.sorted().distinct(),times)
        val flash = HighQualityFramePlan.build(graph, 60).frames.single { it.outputTimeUs == 650_000L }
        assertTrue(RenderedVisualSampler.isIntentionalFlash(flash.layer))
        val whitePulse = HeartbeatMontageProfile.pulses.first {
            it.kind == HeartbeatMontageProfile.PulseKind.WHITE
        }
        assertTrue(RenderedVisualSampler.isIntentionalFlash(graph, whitePulse.endUs, 60, null))
        assertFalse(RenderedVisualSampler.isIntentionalFlash(
            graph,
            whitePulse.endUs + 16_667L,
            60,
            null
        ))
        val shader = MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER
        assertTrue(shader.contains("for(int i=0;i<64;i++)"))
        assertFalse(shader.contains("3-abs(x)"))
        val frame = HighQualityFramePlan.build(graph,60).frames.first { it.effects.defocus > .5f }
        val collector = VeykadRenderInspector.Collector(graph,1)
        collector.record(frame,null,false)
        assertEquals(frame.effects.defocus,collector.evidence().single().defocus,0f)
    }
    @Test fun defocus_decays_after_cut_and_is_absent_from_opening_and_tail() {
        val graph = HeartbeatDirector.build(pool(),"audio")
        val cut = HeartbeatMontageProfile.scenes[4].startUs
        assertEquals(0f,graph.effectGraph.sample(cut-1).defocus,0f)
        assertEquals(.85f,graph.effectGraph.sample(cut).defocus,.0001f)
        assertTrue(graph.effectGraph.sample(cut+125_000).defocus in .20f.. .23f)
        assertEquals(0f,graph.effectGraph.sample(cut+250_000).defocus,0f)
        assertEquals(0f,graph.effectGraph.sample(19_800_000).defocus,0f)
        assertEquals(0f,graph.effectGraph.sample(12_250_000).defocus,0f)
        assertEquals(0f,graph.effectGraph.sample(18_666_667).defocus,0f)
        assertEquals(0f,GpuEffectGraph().sample(cut).defocus,0f)
    }
    private fun pool(count: Int = 15) = MontageGraph(30_000,count*1_500L,
        // Clock/shader tests need genuine live handles, not the old 1s fixtures which silently
        // slowed longer measured slots and the 1.133s finale without any extension evidence.
        clips = (0 until count).map { i -> MontageGraph.Clip("pool-$i",i*1_500L,i*1_500L+1_500,
            1_500,MontageGraph.ShotRole.CLOSE,MontageGraph.Transition.FOREGROUND_REENTRY,
            MontageGraph.Motion.PUSH_IN,.8f,i*1_000L) })
    @Test fun recipe_reuses_selected_roles_but_not_sigma_transitions() {
        val base = pool()
        val graph = HeartbeatDirector.build(base,"heartbeat-audio")
        assertEquals(21_166L,graph.outputDurationMs)
        assertEquals(1_270, HighQualityFramePlan.build(graph, 60).frames.size)
        assertEquals(27,graph.clips.size)
        assertTrue(graph.clips[15].sourceStartMs >= base.clips[4].sourceStartMs)
        assertEquals(graph.clips[4].sourceEndMs,graph.clips[15].sourceEndMs)
        assertTrue(graph.clips.mapIndexedNotNull { index, clip ->
            index.takeIf { clip.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY }
        }.isEmpty())
        assertTrue(base.clips.all { it.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY })
        assertEquals(12,graph.overlays.count { it.overlayKind == MontageGraph.OverlayKind.DOUBLE_EXPOSURE })
        assertEquals("heartbeat-audio",graph.audioTrack!!.sourceId)
        assertFalse(ReferenceMontageProfile.appliesTo(graph))
    }
    @Test fun current_source_roles_are_not_replaced_by_an_older_sources_numeric_remap() {
        val graph = HeartbeatDirector.build(pool(), "audio")
        listOf(9, 10, 11, 12, 13, 14).forEach { role ->
            assertTrue(graph.clips[role].id.endsWith("role-$role"))
        }
        listOf(9, 11, 13).forEach { role ->
            assertTrue(graph.clips[role + 11].id.endsWith("role-$role"))
        }
        assertEquals(15, graph.clips.take(15).map {
            it.id.substringAfterLast("role-")
        }.distinct().size)
    }
    @Test fun measured_close_and_wide_cues_swap_only_when_semantic_scale_is_reversed() {
        val base = pool()
        val mask = FrameAttachments.Plane(1, 1, floatArrayOf(1f), .95f)
        fun evidence(role: Int, width: Float): List<FrameAttachments> {
            val clip = base.clips[role]
            return listOf(clip.sourceStartMs + 100L, clip.sourceEndMs - 100L).map { timeMs ->
                FrameAttachments(
                    sourceTimeUs = timeMs * 1_000L,
                    mask = mask,
                    faceRegion = FrameAttachments.FaceRegion(.5f, .38f, width, .2f, .9f)
                )
            }
        }
        val reversed = FrameAttachmentTimeline(
            (evidence(4, .22f) + evidence(11, .55f)).sortedBy { it.sourceTimeUs }
        )
        val order = HeartbeatDirector.measuredCompositionRoleOrder(base.clips, reversed)
        assertEquals(11, order[4])
        assertEquals(4, order[11])
        val graph = HeartbeatDirector.build(base.copy(frameAttachments = reversed), "audio")
        assertTrue(graph.clips[4].id.endsWith("role-11"))
        assertTrue(graph.clips[11].id.endsWith("role-4"))
        assertTrue(graph.clips.last { it.id != "heartbeat-black-tail" }.id.endsWith("role-11"))
        assertEquals(15, graph.clips.take(15).map { it.sourceStartMs to it.sourceEndMs }.distinct().size)

        val ambiguous = FrameAttachmentTimeline(
            (evidence(4, .42f) + evidence(11, .47f)).sortedBy { it.sourceTimeUs }
        )
        assertEquals(base.clips.indices.toList(),
            HeartbeatDirector.measuredCompositionRoleOrder(base.clips, ambiguous))
    }
    @Test fun complete_face_evidence_matches_source_scale_ranks_to_authored_cues_without_duplicates() {
        val base = pool()
        val mask = FrameAttachments.Plane(1, 1, floatArrayOf(1f), .95f)
        val sourceWidths = base.clips.indices.associateWith { role -> .10f + role * .02f }
        val evidence = FrameAttachmentTimeline(base.clips.flatMapIndexed { role, clip ->
            listOf(clip.sourceStartMs + 100L, clip.sourceEndMs - 100L).map { timeMs ->
                FrameAttachments(
                    sourceTimeUs = timeMs * 1_000L,
                    mask = mask,
                    faceRegion = FrameAttachments.FaceRegion(
                        .5f, .38f, sourceWidths.getValue(role), .2f, .9f
                    )
                )
            }
        }.sortedBy { it.sourceTimeUs })

        val order = HeartbeatDirector.measuredCompositionRoleOrder(base.clips, evidence)
        assertEquals(11, order[4])
        assertEquals(9, order[9])
        assertEquals(15, order.take(15).distinct().size)
        val targetRoles = (0 until 15).filter {
            it != 4 && it != 9 && HeartbeatMontageProfile.targetFaceWidthForRole(it) != null
        }.sortedWith(compareBy<Int> {
            HeartbeatMontageProfile.targetFaceWidthForRole(it)!!
        }.thenBy { it })
        val assignedWidths = targetRoles.map { sourceWidths.getValue(order[it]) }
        assertEquals(assignedWidths.sorted(), assignedWidths)

        val graph = HeartbeatDirector.build(base.copy(frameAttachments = evidence), "audio")
        assertEquals(15, graph.clips.take(15).map { it.sourceStartMs to it.sourceEndMs }.distinct().size)
        assertTrue(graph.clips.last { it.id != "heartbeat-black-tail" }.id.endsWith("role-11"))
    }
    @Test fun measured_live_motion_changes_only_the_remappable_composition_assignment() {
        val base = pool()
        val mask = FrameAttachments.Plane(1, 1, floatArrayOf(1f), .95f)
        val attachments = ArrayList<FrameAttachments>()
        val observations = ArrayList<VisualEventMap.Observation>()
        base.clips.forEachIndexed { role, clip ->
            val width = .10f + role * .02f
            val motion = .03f + (14 - role) * .045f
            listOf(clip.sourceStartMs + 100L, clip.sourceEndMs - 100L).forEach { timeMs ->
                attachments += FrameAttachments(
                    sourceTimeUs = timeMs * 1_000L,
                    mask = mask,
                    faceRegion = FrameAttachments.FaceRegion(.5f, .38f, width, .2f, .9f)
                )
                observations += VisualEventMap.Observation(
                    sourceTimeUs = timeMs * 1_000L,
                    cameraMotion = VisualEventMap.Vector(motion, 0f),
                    face = VisualEventMap.Face(.9f, 0f)
                )
            }
        }
        val timeline = FrameAttachmentTimeline(attachments.sortedBy { it.sourceTimeUs })
        val scaleOnly = HeartbeatDirector.measuredCompositionRoleOrder(base.clips, timeline)
        val motionAware = HeartbeatDirector.measuredCompositionRoleOrder(
            base.clips,
            timeline,
            VisualEventMap(30_000_000L, emptyList(), observations.sortedBy { it.sourceTimeUs })
        )

        assertNotEquals(scaleOnly, motionAware)
        assertEquals(15, motionAware.distinct().size)
        assertEquals(scaleOnly[0], motionAware[0])
        assertEquals(scaleOnly[4], motionAware[4])
        assertEquals(scaleOnly[9], motionAware[9])
    }
    @Test fun heartbeat_reprise_is_allowed_but_duplicate_inside_the_first_phrase_is_rejected() {
        val graph = HeartbeatDirector.build(pool(), "audio")
        assertEquals(0f, RenderedMp4Acceptance.repeatedSourceRatio(graph), .001f)
        val accidental = graph.copy(clips = graph.clips.mapIndexed { index, clip ->
            if (index == 10) clip.copy(
                sourceStartMs = graph.clips.first().sourceStartMs,
                sourceEndMs = graph.clips.first().sourceEndMs
            ) else clip
        })
        assertTrue(RenderedMp4Acceptance.repeatedSourceRatio(accidental) > 0f)
    }
    @Test fun measured_accents_do_not_gain_unmeasured_wipes_blackouts_or_sideways_crops() {
        val graph = HeartbeatDirector.build(pool(), "audio")
        assertEquals(MontageGraph.Transition.OPEN, graph.clips.first().transitionIn)
        assertTrue(graph.clips.drop(1).all { it.transitionIn == MontageGraph.Transition.HARD_CUT })
        assertTrue(graph.clips.all { it.motion == MontageGraph.Motion.HOLD })
        assertTrue(graph.clips.all { it.transform == MontageGraph.ClipTransform.hold() })
        assertEquals(HeartbeatMontageProfile.pulses.size,
            graph.overlays.count { it.id.startsWith("heartbeat-pulse-") })
        assertEquals(11, graph.effectGraph.nodes.size)
        assertTrue(graph.effectGraph.nodes.all { node -> node.endUs <= 11_750_000L })
        assertEquals(12, graph.overlays.count { it.overlayKind == MontageGraph.OverlayKind.DOUBLE_EXPOSURE })
    }
    @Test fun short_beat_slots_trim_handles_instead_of_accidentally_accelerating_source() {
        val base = pool()
        val graph = HeartbeatDirector.build(base,"audio")
        graph.clips.take(26).zip(HeartbeatMontageProfile.scenes).forEachIndexed { index, (clip,scene) ->
            val selectedRole = if (index == HeartbeatMontageProfile.scenes.lastIndex) {
                HeartbeatMontageProfile.FINALE_PORTRAIT_ROLE
            } else HeartbeatDirector.selectedPoolRole(scene.sourceRole)
            val original = base.clips[selectedRole]
            assertTrue(clip.sourceStartMs >= original.sourceStartMs)
            assertTrue(clip.sourceEndMs <= original.sourceEndMs)
            assertTrue(kotlin.math.abs((clip.sourceStartMs+clip.sourceEndMs)-
                (original.sourceStartMs+original.sourceEndMs)) <= 1L)
            assertTrue(clip.sourceEndMs-clip.sourceStartMs <= clip.outputDurationMs)
        }
        HighQualityFramePlan.build(graph,60).frames.zipWithNext().forEach { (a,b) ->
            if(a.clipIndex==b.clipIndex) {
                val sourceDelta = b.sourceTimeUs - a.sourceTimeUs
                val outputDelta = b.outputTimeUs - a.outputTimeUs
                assertTrue("clip=${a.clipIndex}, sourceDelta=$sourceDelta, outputDelta=$outputDelta",
                    sourceDelta <= outputDelta + 75L)
            }
        }
    }
    @Test fun unavailable_coverage_is_not_silently_replaced_with_repeated_crops() {
        assertThrows(IllegalArgumentException::class.java) { HeartbeatDirector.build(pool(14),"audio") }
    }
    @Test fun measured_pulses_are_visible_on_the_first_frame_and_tail_is_opaque() {
        val graph = HeartbeatDirector.build(pool(),"audio")
        assertEquals(1f,LayerCompositorModel.sample(graph.overlays,650).opacity,0f)
        assertEquals(MontageGraph.OverlayKind.BLACK_FADE,
            LayerCompositorModel.sample(graph.overlays,19_650).kind)
        val finaleEcho = LayerCompositorModel.sample(graph.overlays,19_750)
        assertEquals(MontageGraph.OverlayKind.DOUBLE_EXPOSURE, finaleEcho.kind)
        assertTrue(finaleEcho.heartbeatEcho)
        assertEquals(.62f, finaleEcho.opacity, 0f)
        assertEquals(MontageGraph.OverlayKind.FLASH,
            LayerCompositorModel.sample(graph.overlays,19_766).kind)
        assertEquals(1f,LayerCompositorModel.sample(graph.overlays,19_800).opacity,0f)
        assertEquals(MontageGraph.OverlayKind.BLACK_FADE,LayerCompositorModel.sample(graph.overlays,21_000).kind)
        assertEquals(0f,LayerCompositorModel.sample(graph.overlays,21_167).opacity,0f)
    }
    @Test fun every_measured_pulse_covers_exactly_its_reference_frames_at_sixty_fps() {
        val graph = HeartbeatDirector.build(pool(),"audio")
        val frames = HighQualityFramePlan.build(graph,60).frames
        HeartbeatMontageProfile.pulses.forEachIndexed { index,pulse ->
            val overlay = graph.overlays.single { it.id == "heartbeat-pulse-$index" }
            val expected = frames.filter { pulse.contains(it.outputTimeUs) }.map { it.outputTimeUs }
            val actual = frames.filter { LayerCompositorModel.sample(listOf(overlay),it.outputTimeUs/1_000L).opacity > .99f }
                .map { it.outputTimeUs }
            assertTrue(expected.isNotEmpty())
            assertEquals("pulse $index",expected,actual)
        }
    }
}
