package com.veycad.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import java.io.File
import java.security.MessageDigest

/** Debug-only local runner. Golden files are pushed beside the app and never packaged in APK. */
class GoldenRenderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            setBackgroundColor(Color.rgb(9, 9, 11))
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            text = "Служебная проверка рендера Veycad\n\nАнализируем кадры и GPU-переходы…"
        })
        val wakeGuard = RenderWakeGuard.acquire(this)
        Thread({
            try {
                runGolden()
            } finally {
                wakeGuard.close()
            }
        }, "veycad-golden-render").start()
    }

    private fun runGolden() {
        val source = File(requireNotNull(intent.getStringExtra("source")))
        val secondarySource = intent.getStringExtra("secondary_source")?.let(::File)
        val music = File(requireNotNull(intent.getStringExtra("music")) {
            "Golden beat-driven render requires a separate music file"
        })
        require(source.canonicalPath != music.canonicalPath) {
            "Video source cannot masquerade as the music source"
        }
        require(music.isFile) { "Music file does not exist: ${music.absolutePath}" }
        val output = File(requireNotNull(intent.getStringExtra("output")))
        val marker = File(output.absolutePath + ".result")
        if (intent.getBooleanExtra("semantic_mask_probe", false)) {
            // This new branch claims fresh artifacts before any write. Historical markers and
            // outputs are never deleted, including a refused or failed diagnostic invocation.
            if (output.exists() || marker.exists()) {
                Log.e(TAG, "Semantic mask probe refused: output or result already exists")
                runOnUiThread { finish() }
                return
            }
            output.parentFile?.mkdirs()
            if (!marker.createNewFile()) {
                Log.e(TAG, "Semantic mask probe refused: result claim failed")
                runOnUiThread { finish() }
                return
            }
            runCatching {
                require(output.createNewFile()) { "Semantic mask probe must not overwrite an artifact" }
                val sourceSha = sha256(source)
                val runtimeSha = sha256(File(applicationInfo.sourceDir))
                val report = SemanticMaskEvidenceProbe.run(this, source).apply {
                    require(sha256(source) == sourceSha) { "Source changed during diagnostic" }
                    put("source_sha256", sourceSha); put("runtime_apk_sha256", runtimeSha)
                    put("runtime_split_apk_count", applicationInfo.splitSourceDirs?.size ?: 0)
                    put("runtime_android_api", android.os.Build.VERSION.SDK_INT)
                    put("runtime_device_model", android.os.Build.MODEL)
                }
                output.writeText(report.toString(2))
                marker.writeText(buildString {
                    appendLine("status=ok"); appendLine("probe_only=true"); appendRuntimeIdentity()
                    appendLine("source_sha256=$sourceSha")
                    appendLine("cache_sha256_reference_only=${report.getString("cache_sha256_reference_only")}")
                    appendLine("output_sha256=${sha256(output)}")
                })
            }.onFailure { failure ->
                marker.writeText(buildString {
                    appendLine("status=failed"); appendLine("probe_only=true"); appendRuntimeIdentity()
                    appendLine("source_sha256=${runCatching { sha256(source) }.getOrDefault("unavailable")}")
                    appendLine("type=${failure.javaClass.name}")
                    appendLine("detail=${failure.message.orEmpty().replace('\n', ' ')}")
                })
            }
            runOnUiThread { finish() }
            return
        }
        marker.delete()
        if (intent.getBooleanExtra("motion_probe", false)) {
            runCatching {
                require(!output.exists()) { "Motion probe must not overwrite an existing artifact" }
                output.parentFile?.mkdirs()
                val report = MotionEvidenceProbe.run(this, source).apply {
                    put("source_sha256", sha256(source))
                    put("runtime_apk_sha256", sha256(File(applicationInfo.sourceDir)))
                    put("runtime_android_api", android.os.Build.VERSION.SDK_INT)
                    put("runtime_device_model", android.os.Build.MODEL)
                    val cacheKey = MediaFrameAnalysisCache.key(source, 250_000,
                        SourceAnalysisProfile.EDITORIAL_WITH_CORRESPONDENCE)
                    put("cache_file", cacheKey.fileName)
                    put("cache_sha256", sha256(File(cacheDir, "full-video-analysis/${cacheKey.fileName}")))
                }
                output.writeText(report.toString(2))
                marker.writeText("status=ok\nprobe_only=true\noutput_sha256=${sha256(output)}\n")
            }.onFailure { marker.writeText("status=failed\n${it.stackTraceToString()}") }
            runOnUiThread { finish() }
            return
        }
        if (intent.getBooleanExtra("heartbeat_face_probe", false)) {
            runCatching {
                output.parentFile?.mkdirs()
                val sourceUs = 14_660_911L
                val retriever = android.media.MediaMetadataRetriever()
                val bitmap = try {
                    retriever.setDataSource(source.absolutePath)
                    requireNotNull(retriever.getFrameAtTime(sourceUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST))
                } finally { retriever.release() }
                File(output.path + ".input.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                }
                val semantic = try { LocalSemanticFrameAnalyzer().use { it.analyze(bitmap) } }
                    finally { bitmap.recycle() }
                val face = requireNotNull(semantic.faceRegion) { "No face detected at diagnostic source PTS" }
                val graph = MontageGraph(20_000, 1_000, clips = listOf(MontageGraph.Clip(
                    "face-probe",14_660,14_661,1_000,MontageGraph.ShotRole.CLOSE,
                    MontageGraph.Transition.OPEN,MontageGraph.Motion.HOLD,1f,0)),
                    overlays = listOf(MontageGraph.Overlay("probe-echo",0,1_000,
                        "heartbeat-echo-experimental",overlayKind = MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
                        opacity = .45f,secondarySourceOffsetMs = 0)),
                    frameAttachments = FrameAttachmentTimeline(listOf(FrameAttachments(sourceUs,
                        mask = semantic.mask, depth = semantic.depth, faceRegion = face))))
                MediaCodecSpeedRampRenderer.render(MediaCodecSpeedRampRenderer.Request(
                    masterFile = source, graph = graph, outputFile = output,
                    width = 720,height = 1280,fps = 30,bitrate = 5_000_000,
                    audioFile = music,context = this,
                    debugFaceRegionProbe = !intent.getBooleanExtra("probe_echo",false)))
                marker.writeText("status=ok\nprobe_only=true\nsource_us=$sourceUs\nface=$face\n")
            }.onFailure { marker.writeText("status=failed\n${it.stackTraceToString()}") }
            runOnUiThread { finish() }
            return
        }
        if (intent.getBooleanExtra("heartbeat_texture_probe", false)) {
            runCatching {
                val graph = MontageGraph(20_000, 1_000, clips = listOf(MontageGraph.Clip(
                    "probe", 5_000, 6_000, 1_000, MontageGraph.ShotRole.CLOSE,
                    MontageGraph.Transition.OPEN, MontageGraph.Motion.HOLD, 1f, 0)),
                    overlays = listOf(MontageGraph.Overlay("probe-echo", 0, 1_000,
                        "heartbeat-echo-experimental", overlayKind = MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
                        opacity = .45f, secondarySourceOffsetMs = -1)),
                    metadata = NleProjectMetadata(generator = HeartbeatMontageProfile.ID + ":probe"))
                MediaCodecSpeedRampRenderer.render(MediaCodecSpeedRampRenderer.Request(
                    masterFile = source, graph = graph, outputFile = output,
                    width = 720, height = 1280, fps = 30, bitrate = 5_000_000,
                    audioFile = music, context = this, debugTextureProbe = true,
                    debugProbeIncomingOnBothUnits = intent.getBooleanExtra("probe_same_texture", false)))
                val retriever = android.media.MediaMetadataRetriever()
                val variances = try {
                    retriever.setDataSource(output.absolutePath)
                    val bitmap = requireNotNull(retriever.getFrameAtTime(500_000,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST))
                    try {
                        (0..1).map { half ->
                            val samples = (1..8).flatMap { y -> (1..8).map { x ->
                                val c = bitmap.getPixel((half*bitmap.width/2 + x*bitmap.width/18)
                                    .coerceAtMost(bitmap.width-1), y*bitmap.height/9)
                                (Color.red(c)*.299 + Color.green(c)*.587 + Color.blue(c)*.114)/255.0
                            } }
                            val mean = samples.average()
                            samples.map { (it-mean)*(it-mean) }.average()
                        }
                    } finally { bitmap.recycle() }
                } finally { retriever.release() }
                // Fixture-specific diagnostic, not general visual-quality acceptance.
                check(variances.all { it > .0001 }) { "Flat GPU input: variances=$variances" }
                marker.writeText("status=ok\nprobe_only=true\ninput_variances=$variances\n")
            }.onFailure { marker.writeText("status=failed\n${it.stackTraceToString()}") }
            runOnUiThread { finish() }
            return
        }
        var actualRenderSource = source
        runCatching {
            output.parentFile?.mkdirs()
            val staticMode = intent.getBooleanExtra("static", false)
            val editSource = if (staticMode) createStaticMaster(source, output) else source
            actualRenderSource = editSource
            val styleExtra = intent.getStringExtra("style") ?: EventMatchingDirector.Style.DYNAMIC.name
            val recipe = requestedRecipe()
            val editRequest = VeycadAutomaticEditor.Request(
                context = this,
                sourceFile = editSource,
                secondarySourceFile = secondarySource,
                musicFile = music,
                outputFile = output,
                recipe = recipe,
                heartbeatEchoOffsetMs = intent.getLongExtra("heartbeat_echo_offset_ms", -67L),
                style = styleExtra.takeUnless { it.equals(AUTO_STYLE, ignoreCase = true) }
                    ?.let(EventMatchingDirector.Style::valueOf),
                capabilities = RenderPassPlanner.DeviceCapabilities(3, 4_096, 512, false, true)
            )
            val edit = VeycadAutomaticEditor.render(editRequest)
            val analysis = edit.analysis
            val pipeline = edit.pipeline
            val rendered = edit.candidates
            val winner = edit.winner
            val alternative = winner.alternative
            val passPlan = winner.passPlan
            val executedPasses = winner.executedPasses
            val frames = winner.frames
            val acceptance = winner.acceptance
            val renderedSamples = winner.samples
            val expectedHeight = if (recipe == MontageStyleCatalog.Recipe.FEAR_STROBE)
                editRequest.width else editRequest.height
            val encodedContainer = VeykadRenderInspector.inspectContainer(output,
                editRequest.width, expectedHeight)
            val worstArtifact = renderedSamples.maxByOrNull { it.artifactScore }
            val worstEdgeLeak = renderedSamples.maxByOrNull { it.edgeLeakRatio }
            val flowPlanes = analysis.attachments.frames.mapNotNull { it.flow }
            val debugExportDir = requireNotNull(getExternalFilesDir("golden")).apply { mkdirs() }
            val result = buildString {
                appendLine("status=ok")
                appendRuntimeIdentity()
                if (intent.getBooleanExtra("heartbeat",false)) {
                    val pulseAudit = HeartbeatPulseAudit.evaluate(renderedSamples)
                    appendLine("heartbeat_pulses_measured=${pulseAudit.measured}")
                    appendLine("heartbeat_pulses_matched=${pulseAudit.matched}")
                    appendLine("heartbeat_pulses_missing=${pulseAudit.missing.joinToString(",")}")
                    appendLine("heartbeat_pulses_wrong_luma=${pulseAudit.wrongLuma.joinToString(",")}")
                    val tail = pulseAudit.tail
                    appendLine("heartbeat_tail_method=${HeartbeatPulseAudit.TAIL_METHOD}")
                    appendLine("heartbeat_tail_expected_frames=${tail.expectedFrames}")
                    appendLine("heartbeat_tail_measured_frames=${tail.measuredFrames}")
                    appendLine("heartbeat_tail_black_frames=${tail.blackFrames}")
                    appendLine("heartbeat_tail_boundary_measured_frames=${tail.boundaryMeasuredFrames}")
                    appendLine("heartbeat_tail_matched=${tail.matched}")
                    appendLine("heartbeat_tail_first_black_us=${tail.firstBlackUs ?: ""}")
                    appendLine("heartbeat_tail_start_offset_us=${tail.startOffsetUs ?: ""}")
                    appendLine("heartbeat_tail_missing_us=${tail.missingTimesUs.joinToString(",")}")
                    appendLine("heartbeat_tail_bright_us=${tail.brightTimesUs.joinToString(",")}")
                    appendLine("heartbeat_tail_unexpected_us=${tail.unexpectedTimesUs.joinToString(",")}")
                    appendLine("heartbeat_tail_pre_boundary_luma=${tail.preBoundaryLuma ?: ""}")
                }
                if (intent.getBooleanExtra("fear", false)) {
                    val openingClip = alternative.graph.clips.first()
                    val openingMotion = FearOpeningEvidence.evaluate(edit.analysis.observations.filter {
                        it.sourceTimeUs in openingClip.sourceStartMs * 1_000L until openingClip.sourceEndMs * 1_000L
                    })
                    appendLine("fear_opener_motion_samples=${openingMotion.movingSamples}")
                    appendLine("fear_opener_motion_run=${openingMotion.longestRun}")
                    appendLine("fear_opener_motion_span_us=${openingMotion.longestRunSpanUs}")
                    appendLine("fear_opener_motion_measured_samples=${openingMotion.measuredSamples}")
                    appendLine("fear_opener_motion_unknown_samples=${openingMotion.unknownSamples}")
                    appendLine("fear_opener_subject_measured_samples=${openingMotion.subjectMeasuredSamples}")
                    appendLine("fear_opener_camera_measured_samples=${openingMotion.cameraMeasuredSamples}")
                    appendLine("fear_opener_evidence_method=camera-or-subject-independent-v1")
                    appendLine("fear_opener_motion_conclusive_samples=${openingMotion.conclusiveSamples}")
                    appendLine("fear_opener_motion_inconclusive_samples=${openingMotion.inconclusiveSamples}")
                    appendLine("fear_opener_motion_method=${MotionAnalysisGeometry.METHOD}")
                    val cascade = FearCascadeEvidence.evaluate(alternative.graph, edit.pipeline.visualMap)
                    appendLine("fear_first_cascade_sampled_clips=${cascade.first.sampledClips}")
                    appendLine("fear_second_cascade_sampled_clips=${cascade.second.sampledClips}")
                    appendLine("fear_first_cascade_witnesses=${cascade.first.witnessClipIds.joinToString(",")}")
                    appendLine("fear_second_cascade_witnesses=${cascade.second.witnessClipIds.joinToString(",")}")
                    val pulseAudit = FearStrobeAudit.evaluate(renderedSamples)
                    appendLine("fear_shutter_measured=${pulseAudit.shutterFramesMeasured}")
                    appendLine("fear_shutter_matched=${pulseAudit.shutterFramesMatched}")
                    appendLine("fear_finale_matched=${pulseAudit.finaleMatched}")
                    appendLine("fear_pulses_missing=${pulseAudit.missingPulseIndices.joinToString(",")}")
                    appendLine("fear_pulses_wrong_luma=${pulseAudit.wrongLumaIndices.joinToString(",")}")
                    appendLine("fear_unexpected_black_us=${pulseAudit.unexpectedBlackFramesUs.joinToString(",")}")
                    appendLine("fear_source_windows=${alternative.graph.clips.joinToString(",") {
                        "${it.sourceStartMs}-${it.sourceEndMs}@${it.outputDurationMs}"
                    }}")
                    appendLine("fear_shot_features=${alternative.graph.clips.joinToString(";") { clip ->
                        val selected = edit.analysis.observations.filter {
                            it.sourceTimeUs in clip.sourceStartMs * 1_000L until clip.sourceEndMs * 1_000L
                        }
                        val scale = selected.mapNotNull { it.composition?.subjectScale }.average()
                        val yaw = selected.mapNotNull { it.face?.yawDegrees }.average()
                        val pitch = selected.mapNotNull { it.face?.pitchDegrees }.average()
                        val measuredGestures = selected.filter { it.gestureEvidenceAvailable }
                        val gesture = measuredGestures.map { it.gestureConfidence }.average()
                        val motion = selected.map {
                            it.cameraMotion.magnitude + it.subjectMotion.magnitude
                        }.average()
                        val face = selected.mapNotNull { it.face?.confidence }.average()
                        "${clip.id}:scale=$scale:yaw=$yaw:pitch=$pitch:" +
                            "gesture=$gesture:gesture_samples=${measuredGestures.size}:motion=$motion:face=$face"
                    }}")
                }
                appendLine("frames=$frames")
                appendLine("recipe=${recipe.name}")
                appendLine("graph_generator=${alternative.graph.metadata.generator}")
                appendLine("temporal_decoded_evidence_method=${VeykadRenderInspector.TEMPORAL_DECODED_EVIDENCE_METHOD}")
                appendLine("temporal_layer_policy=${VeykadRenderInspector.temporalLayerPolicy(alternative.graph)}")
                appendLine("source_pool_generator=${edit.productSourcePool?.metadata?.generator ?: "none"}")
                // Planning provenance only; it never substitutes for the execution inspector,
                // independent decoded audio or human acceptance of this exact output.
                edit.heartbeatSelectionTrace?.let { appendHeartbeatSelectionTrace(it) }
                appendLine("style=${alternative.style}")
                appendLine("candidate_count=${rendered.size}")
                appendLine("candidate_scores=${rendered.joinToString { candidate ->
                    val ranked = RenderedCandidateSelector.Candidate(
                        candidate,
                        candidate.alternative.graph,
                        candidate.acceptance
                    )
                    "${candidate.alternative.style.name}:score=${RenderedCandidateSelector.score(ranked)}:" +
                        "grammar=${candidate.acceptance.metrics.referenceGrammarFit}:" +
                        "accepted=${candidate.acceptance.accepted}:" +
                        "issues=${candidate.acceptance.issues.joinToString("+").ifEmpty { "none" }}:" +
                        candidate.file.name
                }}")
                appendLine("duration_ms=${alternative.graph.outputDurationMs}")
                appendLine("duration_error_us=${acceptance.metrics.durationErrorUs}")
                appendLine("video_first_pts_us=${acceptance.metrics.videoStartPtsUs}")
                appendLine("audio_first_pts_us=${acceptance.metrics.audioStartPtsUs}")
                appendLine("container_issues=${encodedContainer.issues.joinToString(",")}")
                appendLine("encoded_width=${encodedContainer.width}")
                appendLine("encoded_height=${encodedContainer.height}")
                appendLine("expected_width=${editRequest.width}")
                appendLine("expected_height=$expectedHeight")
                appendLine("encoded_rotation=${encodedContainer.rotation}")
                appendLine("encoded_video_samples=${encodedContainer.videoSamples}")
                appendLine("encoded_audio_samples=${encodedContainer.audioSamples}")
                appendLine("encoded_video_mime=${encodedContainer.videoMime}")
                appendLine("encoded_audio_mime=${encodedContainer.audioMime}")
                appendLine("audio_decoded_evidence=${acceptance.decodedAudio != null}")
                acceptance.decodedAudio?.let { decoded ->
                    appendLine("audio_decoded_issues=${decoded.issues.joinToString(",")}")
                    appendLine("audio_unclamped_float=${decoded.unclampedFloatEvidence}")
                    appendLine("audio_sample_rate=${decoded.sampleRate}")
                    appendLine("audio_channels=${decoded.channels}")
                    appendLine("audio_pcm_samples=${decoded.sampleCount}")
                    appendLine("audio_decoded_duration_us=${decoded.durationUs}")
                    appendLine("audio_sample_peak=${decoded.peak}")
                    appendLine("audio_rms=${decoded.rms}")
                    appendLine("audio_non_finite_samples=${decoded.nonFiniteSamples}")
                    appendLine("audio_over_full_scale_samples=${decoded.overFullScaleSamples}")
                    appendLine("audio_longest_full_scale_run=${decoded.longestFullScaleRun}")
                }
                appendLine("music_source=${music.name}")
                appendLine("music_sha256=${sha256(music)}")
                appendLine("music_is_separate=true")
                appendLine("source_count=${if (secondarySource == null) 1 else 2}")
                appendLine("source_sha256=${sha256(source)}")
                appendLine("render_source_sha256=${sha256(editSource)}")
                secondarySource?.let { appendLine("secondary_source_sha256=${sha256(it)}") }
                appendLine("source_indices=${alternative.graph.clips.map { it.sourceIndex }.distinct().sorted().joinToString(",")}")
                if (intent.getBooleanExtra("duality", false)) {
                    appendLine("duality_revision=organic-motion-retime-4")
                    appendLine("speed_curves=${alternative.graph.clips.joinToString(";") { clip ->
                        clip.speedRamp.keyframes.joinToString(",") { "${it.at}:${it.speed}" }
                    }}")
                    appendLine("motion_effects=${alternative.graph.effectGraph.nodes.joinToString(";") {
                        "${it.kind}:${it.startUs}-${it.endUs}:${it.amount}"
                    }}")
                    appendLine("shot_roles=${alternative.graph.clips.joinToString(",") { it.role.name }}")
                    appendLine("shot_features=${alternative.graph.clips.joinToString(";") { clip ->
                        val map = if (clip.sourceIndex == 0) edit.analysis else requireNotNull(edit.secondaryAnalysis)
                        val selected = map.observations.filter {
                            it.sourceTimeUs in clip.sourceStartMs * 1_000L until clip.sourceEndMs * 1_000L
                        }
                        val scale = selected.mapNotNull { it.composition?.subjectScale }.average()
                        val yaw = selected.mapNotNull { it.face?.yawDegrees }.map { kotlin.math.abs(it) }.average()
                        val measuredGestures = selected.filter { it.gestureEvidenceAvailable }
                        val gesture = measuredGestures.map { it.gestureConfidence }.average()
                        val human = selected.map { it.humanPresenceConfidence }.average()
                        "${clip.role}:${clip.sourceIndex}:scale=$scale:yaw=$yaw:gesture=$gesture:" +
                            "gesture_samples=${measuredGestures.size}:human=$human"
                    }}")
                    appendLine("source_windows=${alternative.graph.clips.joinToString(",") {
                        "${it.sourceIndex}:${it.sourceStartMs}-${it.sourceEndMs}@${it.outputDurationMs}"
                    }}")
                    appendLine("source_mean_motion=${listOfNotNull(edit.analysis, edit.secondaryAnalysis).joinToString(",") {
                        it.observations.map { observation ->
                            observation.cameraMotion.magnitude + observation.subjectMotion.magnitude
                        }.average().toString()
                    }}")
                }
                appendLine("static_source=$staticMode")
                appendLine("source_analysis_profile_method=explicit-capabilities-v1")
                appendLine("source_analysis_count=${listOfNotNull(analysis, edit.secondaryAnalysis).size}")
                listOfNotNull(analysis, edit.secondaryAnalysis).forEachIndexed { index, sourceAnalysis ->
                    val capabilityPrefix = "source_analysis_${index}_"
                    appendLine("${capabilityPrefix}profile=${sourceAnalysis.profile.cacheToken}")
                    appendLine("${capabilityPrefix}correspondence_state=${sourceAnalysis.profile.correspondenceState}")
                    appendLine("${capabilityPrefix}observations=${sourceAnalysis.observations.size}")
                    appendLine("${capabilityPrefix}correspondence_assessments_completed=${sourceAnalysis.correspondenceAssessmentsCompleted}")
                    appendLine("${capabilityPrefix}subject_measured_samples=${sourceAnalysis.observations.count { it.motionMeasurement != null }}")
                    appendLine("${capabilityPrefix}camera_measured_samples=${sourceAnalysis.observations.count {
                        it.motionMeasurement != null || it.cameraMeasurement != null }}")
                    val diversity = SourceDiversitySummary.evaluate(sourceAnalysis.observations)
                    val prefix = "source_diversity_${index}_"
                    appendLine("${prefix}observations=${diversity.observations}")
                    appendLine("${prefix}camera_motion_samples=${diversity.cameraMotionSamples}")
                    appendLine("${prefix}subject_motion_samples=${diversity.subjectMotionSamples}")
                    appendLine("${prefix}gesture_samples=${diversity.gestureSamples}")
                    appendLine("${prefix}face_samples=${diversity.faceSamples}")
                    appendLine("${prefix}yaw_span_degrees=${diversity.yawSpanDegrees ?: ""}")
                    appendLine("${prefix}pitch_span_degrees=${diversity.pitchSpanDegrees ?: ""}")
                    appendLine("${prefix}roll_span_degrees=${diversity.rollSpanDegrees ?: ""}")
                    appendLine("${prefix}composition_samples=${diversity.compositionSamples}")
                    appendLine("${prefix}subject_scale_span=${diversity.subjectScaleSpan ?: ""}")
                    appendLine("${prefix}scene_change_peak=${diversity.sceneChangePeak ?: ""}")
                }
                appendLine("repeated_source_ratio=${acceptance.metrics.repeatedSourceRatio}")
                appendLine("clips=${alternative.graph.clips.size}")
                appendLine("cut_times_ms=${alternative.graph.clips.dropLast(1)
                    .runningFold(0L) { cursor, clip -> cursor + clip.outputDurationMs }
                    .drop(1).joinToString(",")}")
                appendLine("beats=${pipeline.audioMap.beats.size}")
                appendLine("onsets=${pipeline.audioMap.onsets.size}")
                appendLine("visual_events=${pipeline.visualMap.events.size}")
                appendLine("camera_events=${pipeline.visualMap.events.count { it.type == VisualEventMap.EventType.CAMERA_MOVE }}")
                appendLine("semantic_frames=${analysis.semanticFrames}")
                appendLine("semantic_model_successes=${analysis.semanticModelSuccesses}")
                appendLine("mask_frames=${analysis.maskFrames}")
                appendLine("attachment_coverage=${analysis.attachments.coverage()}")
                appendLine("active_flow_frames=${flowPlanes.count { it.confidence >= .12f }}")
                appendLine("mean_flow_confidence=${flowPlanes.map { it.confidence }.average().takeIf { !it.isNaN() } ?: 0.0}")
                appendLine("foreground_reentries=${alternative.graph.clips.count { it.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY }}")
                appendLine("whips=${alternative.graph.clips.count { it.transitionIn == MontageGraph.Transition.WHIP }}")
                appendLine("matched_events=${alternative.matchedEventCount}")
                appendLine("virtual_camera=${alternative.virtualCameraCount}")
                appendLine("layered_frames=${VeykadRenderInspector.latest(this@GoldenRenderActivity)
                    ?.summary?.layerFrames ?: 0}")
                appendLine("temporal_layer_frames=${VeykadRenderInspector.latest(this@GoldenRenderActivity)
                    ?.summary?.temporalLayerFrames ?: 0}")
                appendLine("foreground_reentry_source_pts=${VeykadRenderInspector.latest(this@GoldenRenderActivity)
                    ?.summary?.foregroundReentrySourcePts ?: 0}")
                appendLine("subject_stage_source_pts=${VeykadRenderInspector.latest(this@GoldenRenderActivity)
                    ?.summary?.subjectStageSourcePts ?: 0}")
                appendLine("passes=${passPlan.passes.joinToString { it.kind.name }}")
                appendLine("executed_passes=${executedPasses.entries.joinToString { "${it.key.name}:${it.value}" }}")
                appendLine("bytes=${output.length()}")
                appendLine("output_sha256=${sha256(output)}")
                appendLine("acceptance=${acceptance.accepted}")
                appendLine("acceptance_issues=${acceptance.issues.joinToString(",")}")
                VeycadAutomaticEditor.executionArtifactFields(winner.artifacts?.summary)
                    .forEach { (key, value) -> appendLine("$key=$value") }
                appendLine("beat_hit_rate=${acceptance.metrics.beatHitRate}")
                appendLine("author_accent_hit_rate=${acceptance.metrics.authorAccentHitRate}")
                appendLine("max_author_accent_offset_us=${acceptance.metrics.maximumAuthorAccentOffsetUs}")
                appendLine("transition_peak=${acceptance.metrics.transitionPeak}")
                appendLine("max_artifact=${acceptance.metrics.maximumArtifactScore}")
                appendLine("max_artifact_us=${worstArtifact?.outputTimeUs ?: -1L}")
                appendLine("max_artifact_luma=${worstArtifact?.luma ?: -1f}")
                appendLine("max_colour_jump=${acceptance.metrics.maximumColourJump}")
                appendLine("max_colour_jump_us=${acceptance.metrics.maximumColourJumpTimeUs}")
                appendLine("face_expected_samples=${renderedSamples.count { it.faceExpected }}")
                appendLine("face_evidence_method=${DecodedFaceEvidence.METHOD}")
                appendLine("face_measured_samples=${renderedSamples.count {
                    it.faceExpected && RenderedMp4Acceptance.hasFreshFaceEvidence(it) }}")
                appendLine("face_unknown_samples=${renderedSamples.count {
                    it.faceExpected && !RenderedMp4Acceptance.hasFreshFaceEvidence(it) }}")
                appendLine("face_unknown_times_us=${RenderedMp4Acceptance.faceUnknownTimestampsUs(renderedSamples).joinToString(",")}")
                appendLine("face_empty_samples=${renderedSamples.count {
                    it.faceExpected && RenderedMp4Acceptance.hasFreshFaceEvidence(it) && it.faceConfidence == 0f }}")
                appendLine("face_loss_samples=${RenderedMp4Acceptance.faceLossTimestampsUs(renderedSamples).size}")
                appendLine("face_loss_rate=${acceptance.metrics.faceLossRate}")
                appendLine("face_loss_times_us=${RenderedMp4Acceptance.faceLossTimestampsUs(renderedSamples).joinToString(",")}")
                appendLine("foreground_mask_samples=${renderedSamples.count { it.maskExpected }}")
                appendLine("foreground_mask_measured_samples=${renderedSamples.count {
                    it.maskEvidence == RenderedMp4Acceptance.MaskEvidence.MEASURED }}")
                appendLine("foreground_mask_source_empty_samples=${renderedSamples.count {
                    it.maskEvidence == RenderedMp4Acceptance.MaskEvidence.SOURCE_MASK_EMPTY }}")
                appendLine("foreground_mask_output_empty_samples=${renderedSamples.count {
                    it.maskEvidence == RenderedMp4Acceptance.MaskEvidence.OUTPUT_MASK_EMPTY }}")
                appendLine("foreground_mask_inference_failed_samples=${renderedSamples.count {
                    it.maskEvidence == RenderedMp4Acceptance.MaskEvidence.INFERENCE_FAILED }}")
                appendLine("foreground_mask_failed_times_us=${renderedSamples.filter {
                    it.maskEvidence in setOf(RenderedMp4Acceptance.MaskEvidence.SOURCE_MASK_EMPTY,
                        RenderedMp4Acceptance.MaskEvidence.OUTPUT_MASK_EMPTY,
                        RenderedMp4Acceptance.MaskEvidence.INFERENCE_FAILED)
                }.joinToString(",") { "${it.outputTimeUs}:${it.maskEvidence}" }}")
                appendLine("max_edge_leak=${acceptance.metrics.maximumEdgeLeakRatio}")
                appendLine("max_edge_leak_us=${worstEdgeLeak?.outputTimeUs ?: -1L}")
                appendLine("min_mask_temporal_iou=${acceptance.metrics.minimumMaskTemporalIou}")
                appendLine("max_black_block=${acceptance.metrics.maximumBlackBlockScore}")
                appendLine("reference_grammar_fit=${acceptance.metrics.referenceGrammarFit}")
                appendLine("reference_timeline_recall=${acceptance.metrics.referenceTimelineRecall}")
                appendLine("decoded_double_exposure_peak=${acceptance.metrics.decodedDoubleExposurePeak}")
                appendLine("decoded_mirror_slice_peak=${acceptance.metrics.decodedMirrorSlicePeak}")
                appendLine("decoded_glitch_peak=${acceptance.metrics.decodedGlitchPeak}")
                appendLine("max_subject_stage_background_luma=${acceptance.metrics.maximumSubjectStageBackgroundLuma}")
                appendLine("max_subject_stage_background_loss=${acceptance.metrics.maximumSubjectStageBackgroundLoss}")
                appendLine("subject_stage_background_samples=${renderedSamples.mapNotNull { sample ->
                    sample.subjectStageBackgroundLuma?.let { luma -> "${sample.outputTimeUs}:$luma" }
                }.joinToString(",")}")
                appendLine("av_drift_us=${acceptance.metrics.avDriftUs}")
                appendLine("debug_export_dir=${debugExportDir.absolutePath}")
            }
            marker.writeText(result)
            val exportedOutput = File(debugExportDir, output.name)
            if (output.canonicalPath != exportedOutput.canonicalPath) {
                output.copyTo(exportedOutput, overwrite = true)
            }
            marker.copyTo(File(debugExportDir, "${output.name}.result.txt"), overwrite = true)
            winner.artifacts?.let { artifacts ->
                artifacts.report.copyTo(
                    File(debugExportDir, "${output.nameWithoutExtension}-inspector.json"),
                    overwrite = true
                )
                artifacts.contactSheet?.copyTo(
                    File(debugExportDir, "${output.nameWithoutExtension}-contact.jpg"),
                    overwrite = true
                )
                artifacts.transitionSheet?.copyTo(
                    File(debugExportDir, "${output.nameWithoutExtension}-transitions.jpg"),
                    overwrite = true
                )
                artifacts.layerSheet?.copyTo(
                    File(debugExportDir, "${output.nameWithoutExtension}-layers.jpg"),
                    overwrite = true
                )
                artifacts.openingSheet?.copyTo(
                    File(debugExportDir, "${output.nameWithoutExtension}-opening.jpg"),
                    overwrite = true
                )
            }
            Log.i(TAG, result.replace('\n', ' '))
            val comparePivot = intent.getBooleanExtra("heartbeat_compare_pivot", false)
            val compareTiming = intent.getBooleanExtra("heartbeat_compare_timing", false)
            val compareHandles = intent.getBooleanExtra("heartbeat_compare_handles",false)
            val compareProfile = intent.getBooleanExtra("heartbeat_compare_profile_handles",false)
            val compareIsolation = intent.getBooleanExtra("heartbeat_compare_isolation", false)
            if (intent.getBooleanExtra("heartbeat_compare_echo", false) || comparePivot || compareTiming ||
                compareHandles || compareProfile || compareIsolation) {
                val suffix = if(compareIsolation) "isolation-control" else if(compareProfile) "strict-face-control" else if(compareHandles) "centred-handles-control" else if (compareTiming) "synchronous-control" else if (comparePivot) "imagepivot-control" else "echo-control"
                val control = File(debugExportDir, "${output.nameWithoutExtension}-$suffix.mp4")
                val controlMarker = File(control.path + ".result")
                runCatching {
                    require(intent.getBooleanExtra("heartbeat", false))
                    val graph = if(compareIsolation) alternative.graph.copy(clips = alternative.graph.clips.map {
                        if (it.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY) it.copy(
                            transitionIn = MontageGraph.Transition.HARD_CUT,
                            transitionDurationMs = null
                        ) else it
                    }) else if(compareProfile) HeartbeatDirector.build(requireNotNull(edit.productSourcePool),
                        music.absolutePath,intent.getLongExtra("heartbeat_echo_offset_ms",-67L),
                        allowProfileGap=false,visualMap=edit.pipeline.visualMap)
                        else if(compareHandles) HeartbeatDirector.build(requireNotNull(edit.productSourcePool),
                        music.absolutePath,intent.getLongExtra("heartbeat_echo_offset_ms",-67L),
                        allowShiftedHandles=false,visualMap=edit.pipeline.visualMap)
                        else if (compareTiming) HeartbeatDirector.synchronousEchoControl(alternative.graph)
                        else if (comparePivot) alternative.graph else HeartbeatDirector.legacyEchoControl(alternative.graph)
                    val count = MediaCodecSpeedRampRenderer.render(MediaCodecSpeedRampRenderer.Request(
                        masterFile = editSource, graph = graph, outputFile = control,
                        width = 720, height = 1280, fps = 60, bitrate = 5_000_000,
                        // Isolation A/B needs only the measured entrance. Re-encoding the full
                        // 21-second score made one visual check as expensive as another export.
                        audioFile = if (compareIsolation) null else music, context = this,
                        outputWindow = if (compareIsolation) MediaCodecSpeedRampRenderer.OutputWindow(
                            6_100_000L, 7_300_000L
                        ) else null,
                        renderPlan = passPlan, debugHeartbeatImagePivot = comparePivot
                    ))
                    val inspection = requireNotNull(VeykadRenderInspector.latest(this, control))
                    inspection.report.copyTo(File(debugExportDir,
                        "${control.nameWithoutExtension}-inspector.json"), overwrite = true)
                    controlMarker.writeText(buildString {
                        appendLine("status=ok")
                        appendLine("frames=$count")
                        if (compareIsolation) appendLine("output_window_us=6100000..7300000")
                        appendLine("quality_acceptance=not_evaluated")
                    })
                }.onFailure { failure ->
                    controlMarker.writeText("status=failed\n${failure.stackTraceToString()}")
                    Log.e(TAG, "Heartbeat echo control failed", failure)
                }
            }
            intent.getStringExtra("external_matte_dir")?.let { directory ->
                val oracleOutput = File(output.parentFile, "${output.nameWithoutExtension}-external-matte.mp4")
                runCatching {
                    ExternalMatteRenderProbe.render(this, editSource, music, oracleOutput,
                        alternative.graph, File(directory))
                }.onFailure { failure ->
                    val oracleMarker = File(oracleOutput.path + ".result")
                    if (!oracleMarker.exists()) {
                        oracleMarker.writeText("status=error\n${failure.stackTraceToString()}")
                    }
                    Log.e(TAG, "External matte probe failed", failure)
                }
            }
        }.onFailure { error ->
            val result = if (error is MaterialRejectedException) buildString {
                appendLine("status=material_rejected")
                appendRuntimeIdentity()
                appendLine("recipe=${requestedRecipe().name}")
                appendLine("rejection_code=${error.code}")
                appendLine("source_sha256=${sha256(source)}")
                appendLine("render_source_sha256=${sha256(actualRenderSource)}")
                appendLine("static_source=${intent.getBooleanExtra("static", false)}")
                secondarySource?.let { appendLine("secondary_source_sha256=${sha256(it)}") }
                appendLine("music_sha256=${sha256(music)}")
                appendLine("detail=${error.message.orEmpty().replace('\n', ' ')}")
                if (error is HeartbeatCandidateDomainExhaustedException) {
                    appendHeartbeatSelectionTrace(error.trace)
                }
            } else if (error is HeartbeatCandidateSearchIncompleteException) buildString {
                // A computational cap is not a negative material case. Retain exact identity
                // and counters without allowing it into the material-rejection release gate.
                appendLine("status=failed")
                appendRuntimeIdentity()
                appendLine("recipe=${requestedRecipe().name}")
                appendLine("type=${error.javaClass.name}")
                appendLine("source_sha256=${sha256(source)}")
                appendLine("render_source_sha256=${sha256(actualRenderSource)}")
                appendLine("static_source=${intent.getBooleanExtra("static", false)}")
                appendLine("music_sha256=${sha256(music)}")
                appendLine("detail=${error.message.orEmpty().replace('\n', ' ')}")
                appendHeartbeatSelectionTrace(error.trace)
            } else "status=failed\ntype=${error.javaClass.name}\ndetail=${error.message.orEmpty()}\n"
            marker.writeText(result)
            Log.e(TAG, "Golden render failed", error)
        }
        runOnUiThread { finish() }
    }

    private fun requestedRecipe(): MontageStyleCatalog.Recipe = when {
        intent.getBooleanExtra("heartbeat", false) -> MontageStyleCatalog.Recipe.HEARTBEAT
        intent.getBooleanExtra("fear", false) -> MontageStyleCatalog.Recipe.FEAR_STROBE
        intent.getBooleanExtra("duality", false) -> MontageStyleCatalog.Recipe.DUALITY_LOOP
        else -> MontageStyleCatalog.Recipe.SIGMA
    }

    private fun StringBuilder.appendHeartbeatSelectionTrace(selection: HeartbeatSourcePool.SelectionTrace) {
        appendLine("heartbeat_selection_method=${selection.method}")
        appendLine("heartbeat_selection_generated_candidates=${selection.generatedCandidates}")
        appendLine("heartbeat_selection_candidate_count=${selection.candidateCount}")
        appendLine("heartbeat_selection_candidate_visits=${selection.candidateVisits}")
        appendLine("heartbeat_selection_director_validations=${selection.directorValidations}")
        appendLine("heartbeat_selection_finite_domain_exhausted=${selection.finiteDomainExhausted}")
        appendLine("heartbeat_selection_max_candidate_visits=${selection.maxCandidateVisits}")
        appendLine("heartbeat_selection_max_director_validations=${selection.maxDirectorValidations}")
    }

    /** Read from the running package/device, never from caller-supplied extras. */
    private fun StringBuilder.appendRuntimeIdentity() {
        appendLine("runtime_apk_sha256=${sha256(File(applicationInfo.sourceDir))}")
        appendLine("runtime_split_apk_count=${applicationInfo.splitSourceDirs?.size ?: 0}")
        appendLine("runtime_device_model=${android.os.Build.MODEL.replace('\n', ' ')}")
        appendLine("runtime_android_api=${android.os.Build.VERSION.SDK_INT}")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun createStaticMaster(source: File, output: File): File {
        val file = File(output.parentFile, "${output.nameWithoutExtension}-static-master.mp4")
        val graph = MontageGraph(
            sourceDurationMs = 100L,
            outputDurationMs = 20_000L,
            clips = listOf(MontageGraph.Clip(
                id = "static-master",
                sourceStartMs = 0L,
                sourceEndMs = 100L,
                outputDurationMs = 20_000L,
                role = MontageGraph.ShotRole.OPENING,
                transitionIn = MontageGraph.Transition.OPEN,
                motion = MontageGraph.Motion.HOLD,
                confidence = 1f,
                beatAnchorMs = 0L
            ))
        )
        MediaCodecSpeedRampRenderer.render(MediaCodecSpeedRampRenderer.Request(
            masterFile = source,
            graph = graph,
            outputFile = file,
            width = 720,
            height = 1_280,
            bitrate = 4_000_000,
            context = this
        ))
        return file
    }

    companion object {
        private const val TAG = "VeycadGolden"
        private const val AUTO_STYLE = "AUTO"
    }
}
