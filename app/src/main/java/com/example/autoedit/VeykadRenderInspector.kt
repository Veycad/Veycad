package com.veycad.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Local proof that authored VME technology reached encoded frames. The collector is fed directly
 * by the GLES draw call; the artifact writer then samples the resulting MP4. Nothing is uploaded.
 */
object VeykadRenderInspector {
    const val TEMPORAL_DECODED_EVIDENCE_METHOD = "decoded-texture-pts-v1"

    /** Authored intervals are diagnostic provenance, not decoded-pixel acceptance. */
    internal data class SelectionEvidence(
        val clipIndex: Int,
        val clipId: String,
        val sourceIndex: Int,
        val sourceStartMs: Long,
        val sourceEndMs: Long,
        val outputStartMs: Long,
        val outputEndMs: Long,
        val role: MontageGraph.ShotRole
    )

    internal fun selectionEvidence(graph: MontageGraph): List<SelectionEvidence> {
        var cursorMs = 0L
        return graph.clips.mapIndexed { index, clip ->
            SelectionEvidence(index, clip.id, clip.sourceIndex, clip.sourceStartMs,
                clip.sourceEndMs, cursorMs, cursorMs + clip.outputDurationMs, clip.role)
                .also { cursorMs = it.outputEndMs }
        }
    }

    private val temporalLayerKinds = setOf(
        MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
        MontageGraph.OverlayKind.MIRROR_SLICE
    )

    /** Declares the existing layer/profile contract; missing evidence is never an exemption. */
    internal fun temporalLayerPolicy(graph: MontageGraph): String = when {
        ReferenceMontageProfile.appliesTo(graph) -> "reference-spatial-v1"
        graph.overlays.any { it.overlayKind in temporalLayerKinds } -> "temporal-distinct-pts-v1"
        else -> "no-temporal-layer-v1"
    }
    private val visibleTransitions = setOf(
        MontageGraph.Transition.WHIP,
        MontageGraph.Transition.OCCLUSION,
        MontageGraph.Transition.FOREGROUND_REENTRY,
        MontageGraph.Transition.BLACKOUT
    )

    data class FrameEvidence(
        val outputTimeUs: Long,
        val sourceTimeUs: Long,
        val clipIndex: Int,
        val transition: MontageGraph.Transition,
        val dualDecoder: Boolean,
        val speed: Float,
        val scale: Float,
        val translateX: Float,
        val translateY: Float,
        val blur: Float,
        val blackout: Float,
        val occlusion: Float,
        val foregroundReentry: Float,
        val glow: Float,
        val glitch: Float,
        val lensBlur: Float,
        val layerOpacity: Float,
        val layerKind: MontageGraph.OverlayKind,
        val maskConfidence: Float,
        val subjectQuality: Float,
        val subjectOcclusion: Float,
        val maskTemporalIou: Float,
        val secondarySourceTimeUs: Long? = null,
        val maskIsOpacity: Boolean = false,
        val decodedSourceTimeUs: Long? = null,
        val decodedSecondarySourceTimeUs: Long? = null,
        val maskSourceTimeUs: Long? = null,
        val defocus: Float = 0f,
        val faceRegion: FrameAttachments.FaceRegion? = null,
        val effectiveSourceSpeed: Float? = null
    )

    data class Summary(
        val plannedFrames: Int,
        val shaderFrames: Int,
        val authoredTransitionEvents: Int,
        val renderedTransitionEvents: Int,
        val renderedTransitionFrames: Int,
        val dualDecoderFrames: Int,
        val speedRampFrames: Int,
        val virtualCameraFrames: Int,
        val blackoutFrames: Int,
        val transitionFramesByType: Map<MontageGraph.Transition, Int>,
        val maxBlur: Float,
        val maxBlackout: Float,
        val maxOcclusion: Float,
        val maxForegroundReentry: Float,
        val missingTransitionClipIds: List<String>,
        val issues: List<String>,
        val layerFrames: Int = 0,
        val temporalLayerFrames: Int = 0,
        val foregroundReentrySourcePts: Int = 0,
        val subjectStageSourcePts: Int = 0
    ) {
        val accepted: Boolean get() = issues.isEmpty()
    }

    class Collector(private val graph: MontageGraph, private val plannedFrames: Int) {
        private val frames = ArrayList<FrameEvidence>(plannedFrames)

        fun record(
            frame: HighQualityFramePlan.Frame,
            blend: GpuTransitionModel.FrameBlend?,
            dualDecoder: Boolean,
            secondarySourceTimeUs: Long? = null,
            decodedSourceTimeUs: Long? = null,
            decodedSecondarySourceTimeUs: Long? = null
        ) {
            val clip = graph.clips[frame.clipIndex]
            val speedTrack = graph.parameterTracks.firstOrNull {
                it.target == ParameterTargets.clip(clip.id, "speed")
            }
            val speed = speedTrack?.sample(frame.outputTimeUs)?.firstOrNull()
                ?: clip.speedRamp.speedAt(frame.clipProgress)
            val previous = frames.lastOrNull()?.takeIf {
                it.clipIndex == frame.clipIndex && frame.outputTimeUs > it.outputTimeUs
            }
            val effectiveSpeed = previous?.let {
                (frame.sourceTimeUs-it.sourceTimeUs).toFloat() / (frame.outputTimeUs-it.outputTimeUs)
            }
            frames += FrameEvidence(
                outputTimeUs = frame.outputTimeUs,
                sourceTimeUs = frame.sourceTimeUs,
                clipIndex = frame.clipIndex,
                transition = frame.transitionIn,
                dualDecoder = dualDecoder,
                speed = speed,
                effectiveSourceSpeed = effectiveSpeed,
                scale = frame.transform.scale,
                translateX = frame.transform.translateX,
                translateY = frame.transform.translateY,
                blur = blend?.directionalBlur ?: 0f,
                blackout = maxOf(
                    blend?.blackout ?: 0f,
                    frame.layer.opacity.takeIf { frame.layer.kind == MontageGraph.OverlayKind.BLACK_FADE } ?: 0f
                ),
                occlusion = blend?.occlusionMask ?: 0f,
                foregroundReentry = blend?.foregroundReentry ?: 0f,
                glow = frame.effects.glow,
                glitch = frame.effects.glitch,
                lensBlur = frame.effects.lensBlur,
                defocus = frame.effects.defocus,
                faceRegion = frame.attachments?.faceRegion,
                layerOpacity = frame.layer.opacity,
                layerKind = frame.layer.kind,
                maskConfidence = frame.attachments?.mask?.confidence ?: 0f,
                subjectQuality = frame.attachments?.subjectQuality ?: 0f,
                subjectOcclusion = frame.attachments?.subjectOcclusion ?: 0f,
                maskTemporalIou = frame.attachments?.maskTemporalIou ?: 0f,
                secondarySourceTimeUs = secondarySourceTimeUs,
                maskIsOpacity = frame.attachments?.maskIsOpacity == true,
                decodedSourceTimeUs = decodedSourceTimeUs,
                decodedSecondarySourceTimeUs = decodedSecondarySourceTimeUs,
                maskSourceTimeUs = frame.attachments?.takeIf { it.mask != null }?.sourceTimeUs
            )
        }

        fun evidence(): List<FrameEvidence> = frames.toList()

        fun summary(): Summary = evaluate(graph, plannedFrames, frames)
    }

    data class Artifacts(
        val report: File,
        val contactSheet: File?,
        val transitionSheet: File?,
        val layerSheet: File?,
        val openingSheet: File?,
        val summary: Summary,
        val container: ContainerEvidence
    ) {
        val accepted: Boolean get() = summary.accepted && container.issues.isEmpty()
    }

    data class ContainerEvidence(
        val videoMime: String,
        val audioMime: String,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val videoSamples: Int,
        val audioSamples: Int,
        val videoFirstPtsUs: Long,
        val audioFirstPtsUs: Long,
        val videoLastPtsUs: Long,
        val audioLastPtsUs: Long,
        val avDeltaUs: Long,
        val issues: List<String>,
        val videoFps: Int? = null,
        val durationMs: Long = 0L
    ) {
        fun toExportProbe() = ExportContract.Probe(width, height, rotation, durationMs,
            audioMime, videoMime, videoFps)
    }

    internal fun evaluate(
        graph: MontageGraph,
        plannedFrames: Int,
        frames: List<FrameEvidence>
    ): Summary {
        val temporalLayerKinds = if (ReferenceMontageProfile.appliesTo(graph))
            emptySet() else temporalLayerKinds
        val authored = graph.clips.mapIndexedNotNull { index, clip ->
            (index to clip).takeIf { clip.transitionIn in visibleTransitions }
        }
        val hasCameraIntent = graph.clips.any { it.motion != MontageGraph.Motion.HOLD ||
            it.transform != MontageGraph.ClipTransform.hold() }
        val configuredTemporalSeparationUs = graph.overlays.asSequence()
            .filter { it.overlayKind in temporalLayerKinds }
            .mapNotNull { it.secondarySourceOffsetMs }
            .filter { it != 0L }
            .minOfOrNull { kotlin.math.abs(it) }
            ?.let { it * 1_000L }
            ?: MINIMUM_VISIBLE_TEMPORAL_SEPARATION_US
        val renderedFrames = frames.filter { it.transition in visibleTransitions &&
            (it.blur > .0001f || it.blackout > .0001f || it.occlusion > .0001f ||
                it.foregroundReentry > .0001f || it.dualDecoder) }
        val renderedEvents = renderedFrames.map { it.clipIndex }.distinct()
        val missing = authored.mapNotNull { (clipIndex, clip) ->
            clip.id.takeIf { clipIndex !in renderedEvents }
        }
        val byType = visibleTransitions.associateWith { type -> renderedFrames.count { it.transition == type } }
        val speedFrames = frames.count { abs(it.speed - 1f) >= .08f }
        val cameraFrames = frames.count {
            abs(it.scale - 1f) >= .025f || abs(it.translateX) + abs(it.translateY) >= .018f
        }
        val blackoutFrames = frames.count { it.blackout >= .05f }
        val foregroundReentrySourcePts = frames.asSequence()
            .filter {
                it.transition == MontageGraph.Transition.FOREGROUND_REENTRY &&
                    it.foregroundReentry > .001f
            }
            .map { it.sourceTimeUs }
            .distinct()
            .count()
        val subjectStageFrames = frames.filter {
            it.layerKind == MontageGraph.OverlayKind.SUBJECT_STAGE && it.layerOpacity > .01f
        }
        val subjectStageSourcePts = subjectStageFrames.map { it.sourceTimeUs }.distinct().size
        val issues = buildList {
            if (frames.size != plannedFrames) add("shader_frame_count_mismatch")
            if (authored.isNotEmpty() && renderedEvents.isEmpty()) add("no_visible_transition_rendered")
            if (missing.isNotEmpty()) add("authored_transition_missing_from_shader")
            val liveOverlapFrames = renderedFrames.filter {
                it.transition in setOf(MontageGraph.Transition.WHIP, MontageGraph.Transition.OCCLUSION)
            }
            if (liveOverlapFrames.any { !it.dualDecoder }) {
                add("transition_without_two_live_decoders")
            }
            if (authored.any { it.second.transitionIn == MontageGraph.Transition.BLACKOUT } && blackoutFrames == 0) {
                add("authored_blackout_not_rendered")
            }
            val authoredSpeedRamp = graph.clips.any { clip ->
                val speeds = clip.speedRamp.keyframes.map { it.speed }
                (speeds.maxOrNull() ?: 1f) - (speeds.minOrNull() ?: 1f) >= .08f
            }
            if (authoredSpeedRamp && speedFrames < (plannedFrames * .02f).toInt().coerceAtLeast(1)) add("authored_speed_ramp_not_visible")
            if (hasCameraIntent && cameraFrames < (plannedFrames * .15f).toInt().coerceAtLeast(1))
                add("virtual_camera_not_visible")
            if (authored.any { it.second.transitionIn == MontageGraph.Transition.WHIP } && (frames.maxOfOrNull { it.blur } ?: 0f) < .015f) {
                add("whip_blur_below_visible_threshold")
            }
            if (authored.any { it.second.transitionIn == MontageGraph.Transition.OCCLUSION } && (frames.maxOfOrNull { it.occlusion } ?: 0f) < .35f) {
                add("occlusion_below_visible_threshold")
            }
            if (authored.any { it.second.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY } &&
                (frames.maxOfOrNull { it.foregroundReentry } ?: 0f) < .65f) {
                add("foreground_reentry_below_visible_threshold")
            }
            if (authored.any { it.second.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY } &&
                foregroundReentrySourcePts < MINIMUM_LIVE_FOREGROUND_SOURCE_PTS) {
                add("foreground_reentry_source_frozen")
            }
            if (authored.any { it.second.transitionIn == MontageGraph.Transition.BLACKOUT } && (frames.maxOfOrNull { it.blackout } ?: 0f) < .55f) {
                add("blackout_below_visible_threshold")
            }
            if (graph.overlays.any {
                    it.overlayKind in setOf(
                        MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
                        MontageGraph.OverlayKind.MIRROR_SLICE,
                        MontageGraph.OverlayKind.BLACK_FADE,
                        MontageGraph.OverlayKind.SUBJECT_STAGE
                    )
                } && frames.none { it.layerOpacity > .01f }) {
                add("authored_reference_layers_missing_from_shader")
            }
            val authoredTemporalLayers = graph.overlays.any {
                it.overlayKind in temporalLayerKinds
            }
            if (authoredTemporalLayers && frames.none {
                    it.layerOpacity > .01f && it.secondarySourceTimeUs != null &&
                        it.secondarySourceTimeUs != it.sourceTimeUs
                }) {
                add("authored_temporal_layer_missing_second_source_pts")
            }
            if (authoredTemporalLayers && frames.any {
                    it.layerKind in temporalLayerKinds && it.layerOpacity >= .25f &&
                        (it.secondarySourceTimeUs == null ||
                            abs(it.secondarySourceTimeUs - it.sourceTimeUs) < configuredTemporalSeparationUs)
                }) {
                add("temporal_layer_sources_too_close_to_read")
            }
            // Requested timestamps cannot prove which decoded textures reached the shader.
            // Keep the authored separation check above; decoded PTS can be quantised to the
            // source cadence, so this gate proves presence and distinction, not readability.
            val visibleTemporalFrames = if (authoredTemporalLayers) frames.filter {
                it.layerKind in temporalLayerKinds && it.layerOpacity >= .25f
            } else emptyList()
            if (visibleTemporalFrames.any {
                    it.decodedSourceTimeUs == null || it.decodedSourceTimeUs < 0L ||
                        it.decodedSecondarySourceTimeUs == null || it.decodedSecondarySourceTimeUs < 0L
                }) {
                add("temporal_layer_decoded_source_evidence_unavailable")
            }
            if (visibleTemporalFrames.any {
                    it.decodedSourceTimeUs != null && it.decodedSourceTimeUs >= 0L &&
                        it.decodedSecondarySourceTimeUs != null && it.decodedSecondarySourceTimeUs >= 0L &&
                        it.decodedSourceTimeUs == it.decodedSecondarySourceTimeUs
                }) {
                add("temporal_layer_identical_decoded_source_pts")
            }
            if (visibleTemporalFrames.any { !it.dualDecoder }) {
                add("temporal_layer_without_two_live_decoders")
            }
            if (graph.overlays.any { it.overlayKind == MontageGraph.OverlayKind.SUBJECT_STAGE } &&
                subjectStageSourcePts < MINIMUM_LIVE_SUBJECT_STAGE_SOURCE_PTS) {
                add("subject_stage_source_frozen")
            }
            if (graph.overlays.any { it.overlayKind == MontageGraph.OverlayKind.SUBJECT_STAGE } &&
                subjectStageFrames.none { it.maskConfidence >= .80f }) {
                add("subject_stage_missing_live_mask")
            }
        }
        return Summary(
            plannedFrames = plannedFrames,
            shaderFrames = frames.size,
            authoredTransitionEvents = authored.size,
            renderedTransitionEvents = renderedEvents.size,
            renderedTransitionFrames = renderedFrames.size,
            dualDecoderFrames = frames.count { it.dualDecoder },
            speedRampFrames = speedFrames,
            virtualCameraFrames = cameraFrames,
            blackoutFrames = blackoutFrames,
            transitionFramesByType = byType,
            maxBlur = frames.maxOfOrNull { it.blur } ?: 0f,
            maxBlackout = frames.maxOfOrNull { it.blackout } ?: 0f,
            maxOcclusion = frames.maxOfOrNull { it.occlusion } ?: 0f,
            maxForegroundReentry = frames.maxOfOrNull { it.foregroundReentry } ?: 0f,
            missingTransitionClipIds = missing,
            issues = issues,
            layerFrames = frames.count { it.layerOpacity > .01f },
            temporalLayerFrames = frames.count {
                it.layerOpacity > .01f && it.secondarySourceTimeUs != null &&
                    it.secondarySourceTimeUs != it.sourceTimeUs
            },
            foregroundReentrySourcePts = foregroundReentrySourcePts,
            subjectStageSourcePts = subjectStageSourcePts
        )
    }

    fun writeArtifacts(
        context: Context,
        mp4: File,
        graph: MontageGraph,
        collector: Collector
    ): Artifacts? = runCatching {
        require(mp4.isFile && mp4.length() > 0L)
        val directory = File(context.filesDir, "render-inspector").apply { mkdirs() }
        val id = "${System.currentTimeMillis()}_${mp4.nameWithoutExtension.take(48)}"
        val evidence = collector.evidence()
        val summary = collector.summary()
        val container = inspectContainer(mp4)
        val contact = File(directory, "${id}_contact.jpg")
            .takeIf { writeContactSheet(mp4, evidence, it) }
        val transitions = File(directory, "${id}_transitions.jpg")
            .takeIf { writeTransitionSheet(mp4, evidence, it) }
        val layers = File(directory, "${id}_layers.jpg")
            .takeIf { writeLayerSheet(mp4, evidence, it) }
        val opening = File(directory, "${id}_opening.jpg")
            .takeIf { writeOpeningSheet(mp4, evidence, it) }
        val report = File(directory, "$id.json")
        report.writeText(
            reportJson(mp4, graph, summary, container, evidence, contact, transitions, layers, opening).toString(2)
        )
        prune(directory)
        LocalDiagnostics.record(context, "render_inspector", mapOf(
            "accepted" to (summary.accepted && container.issues.isEmpty()).toString(),
            "shader_frames" to "${summary.shaderFrames}/${summary.plannedFrames}",
            "transition_events" to "${summary.renderedTransitionEvents}/${summary.authoredTransitionEvents}",
            "transition_frames" to summary.renderedTransitionFrames.toString(),
            "dual_decoder_frames" to summary.dualDecoderFrames.toString(),
            "blackout_frames" to summary.blackoutFrames.toString(),
            "speed_ramp_frames" to summary.speedRampFrames.toString(),
            "camera_frames" to summary.virtualCameraFrames.toString(),
            "audio_samples" to container.audioSamples.toString(),
            "av_delta_us" to container.avDeltaUs.toString(),
            "rotation" to container.rotation.toString(),
            "issues" to (summary.issues + container.issues).joinToString(" | "),
            "report" to report.name
        ))
        Artifacts(report, contact, transitions, layers, opening, summary, container)
    }.onFailure { error ->
        LocalDiagnostics.record(context, "render_inspector_failed", mapOf(
            "detail" to (error.message ?: error.javaClass.simpleName).take(240)
        ))
    }.getOrNull()

    fun decodedSourceClock(artifacts: Artifacts?): Map<Long, Long> {
        if (artifacts == null) return emptyMap()
        val frames = JSONObject(artifacts.report.readText()).getJSONArray("frames")
        return buildMap {
            for (index in 0 until frames.length()) {
                val frame = frames.getJSONObject(index)
                if (!frame.isNull("decoded_source_us")) {
                    put(frame.getLong("output_us"), frame.getLong("decoded_source_us"))
                }
            }
        }
    }

    fun latest(context: Context, expectedMp4: File? = null): Artifacts? {
        val directory = File(context.filesDir, "render-inspector")
        val report = directory.listFiles()?.filter { it.extension == "json" }?.maxByOrNull { it.lastModified() } ?: return null
        val root = runCatching { JSONObject(report.readText()) }.getOrNull() ?: return null
        if (expectedMp4 != null && (root.optString("mp4_name") != expectedMp4.name ||
            root.optLong("mp4_bytes") != expectedMp4.length())) return null
        val summaryJson = root.getJSONObject("summary")
        val transitionCounts = MontageGraph.Transition.entries.associateWith { type ->
            summaryJson.optJSONObject("transition_frames_by_type")?.optInt(type.name, 0) ?: 0
        }.filterValues { it > 0 }
        val issues = summaryJson.optJSONArray("issues")?.let { array -> List(array.length()) { array.getString(it) } }.orEmpty()
        val summary = Summary(
            summaryJson.getInt("planned_frames"), summaryJson.getInt("shader_frames"),
            summaryJson.getInt("authored_transition_events"), summaryJson.getInt("rendered_transition_events"),
            summaryJson.getInt("rendered_transition_frames"), summaryJson.getInt("dual_decoder_frames"),
            summaryJson.getInt("speed_ramp_frames"), summaryJson.getInt("virtual_camera_frames"),
            summaryJson.getInt("blackout_frames"), transitionCounts,
            summaryJson.getDouble("max_blur").toFloat(), summaryJson.getDouble("max_blackout").toFloat(),
            summaryJson.getDouble("max_occlusion").toFloat(),
            summaryJson.optDouble("max_foreground_reentry", 0.0).toFloat(),
            summaryJson.optJSONArray("missing_transition_clip_ids")?.let { array -> List(array.length()) { array.getString(it) } }.orEmpty(),
            issues,
            summaryJson.optInt("layer_frames", 0),
            summaryJson.optInt("temporal_layer_frames", 0),
            summaryJson.optInt("foreground_reentry_source_pts", 0),
            summaryJson.optInt("subject_stage_source_pts", 0)
        )
        fun artifact(key: String) = root.optString(key).takeIf(String::isNotBlank)?.let { File(directory, it) }?.takeIf(File::isFile)
        val containerJson = root.optJSONObject("container")
        val container = if (containerJson != null) ContainerEvidence(
            videoMime = containerJson.optString("video_mime"),
            audioMime = containerJson.optString("audio_mime"),
            width = containerJson.optInt("width"), height = containerJson.optInt("height"),
            rotation = containerJson.optInt("rotation"),
            videoSamples = containerJson.optInt("video_samples"), audioSamples = containerJson.optInt("audio_samples"),
            videoFirstPtsUs = containerJson.optLong("video_first_pts_us"),
            audioFirstPtsUs = containerJson.optLong("audio_first_pts_us"),
            videoLastPtsUs = containerJson.optLong("video_last_pts_us"), audioLastPtsUs = containerJson.optLong("audio_last_pts_us"),
            avDeltaUs = containerJson.optLong("av_delta_us"),
            issues = containerJson.optJSONArray("issues")?.let { array -> List(array.length()) { array.getString(it) } }.orEmpty(),
            videoFps = containerJson.optInt("video_fps").takeIf { it > 0 },
            durationMs = containerJson.optLong("duration_ms")
        ) else ContainerEvidence("", "", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            listOf("container_evidence_missing"))
        return Artifacts(
            report,
            artifact("contact_sheet"),
            artifact("transition_sheet"),
            artifact("layer_sheet"),
            artifact("opening_sheet"),
            summary,
            container
        )
    }

    private fun reportJson(
        mp4: File,
        graph: MontageGraph,
        summary: Summary,
        container: ContainerEvidence,
        evidence: List<FrameEvidence>,
        contact: File?,
        transitions: File?,
        layers: File?,
        opening: File?
    ) = JSONObject().apply {
        put("format", "veykad-render-inspector-v1")
        put("temporal_decoded_evidence_method", TEMPORAL_DECODED_EVIDENCE_METHOD)
        put("local_only", true)
        put("mp4_name", mp4.name)
        put("mp4_bytes", mp4.length())
        put("contact_sheet", contact?.name ?: "")
        put("transition_sheet", transitions?.name ?: "")
        put("layer_sheet", layers?.name ?: "")
        put("opening_sheet", opening?.name ?: "")
        put("graph", JSONObject().apply {
            put("duration_ms", graph.outputDurationMs)
            put("clips", graph.clips.size)
            put("generator", graph.metadata.generator)
            put("temporal_layer_policy", temporalLayerPolicy(graph))
            put("source_windows_method", "authored-clip-intervals-v1")
            put("source_windows", JSONArray(selectionEvidence(graph).map { selected ->
                JSONObject().apply {
                    put("clip_index", selected.clipIndex)
                    put("clip_id", selected.clipId)
                    put("source_index", selected.sourceIndex)
                    put("source_start_ms", selected.sourceStartMs)
                    put("source_end_ms", selected.sourceEndMs)
                    put("output_start_ms", selected.outputStartMs)
                    put("output_end_ms", selected.outputEndMs)
                    put("role", selected.role.name)
                }
            }))
            put("opening_mask_refinements", graph.frameAttachments.maskRefinements.size)
            put("audio", graph.audioTrack?.sourceId ?: "none")
            put("transitions", JSONArray(graph.clips.map { it.transitionIn.name }))
        })
        put("container", containerJson(container))
        put("summary", summaryJson(summary))
        put("frames", JSONArray(evidence.map { frame -> JSONObject().apply {
            put("output_us", frame.outputTimeUs); put("source_us", frame.sourceTimeUs)
            put("decoded_source_us", frame.decodedSourceTimeUs ?: JSONObject.NULL)
            put("mask_source_us", frame.maskSourceTimeUs ?: JSONObject.NULL)
            put("decoded_secondary_source_us", frame.decodedSecondarySourceTimeUs ?: JSONObject.NULL)
            put("source_sampling_error_us", frame.decodedSourceTimeUs?.let {
                it - frame.sourceTimeUs
            } ?: JSONObject.NULL)
            put("clip", frame.clipIndex); put("transition", frame.transition.name)
            put("dual_decoder", frame.dualDecoder); put("speed", rounded(frame.speed))
            put("scale", rounded(frame.scale)); put("x", rounded(frame.translateX)); put("y", rounded(frame.translateY))
            put("blur", rounded(frame.blur)); put("blackout", rounded(frame.blackout)); put("occlusion", rounded(frame.occlusion))
            put("foreground_reentry", rounded(frame.foregroundReentry))
            put("glow", rounded(frame.glow)); put("glitch", rounded(frame.glitch)); put("lens_blur", rounded(frame.lensBlur))
            put("defocus", rounded(frame.defocus))
            put("layer_opacity", rounded(frame.layerOpacity))
            put("layer_kind", frame.layerKind.name)
            put("mask_confidence", rounded(frame.maskConfidence))
            put("face_region", frame.faceRegion?.let { face -> JSONObject().apply {
                put("x",rounded(face.centerX)); put("y",rounded(face.centerY))
                put("width",rounded(face.width)); put("height",rounded(face.height))
                put("confidence",rounded(face.confidence))
            } } ?: JSONObject.NULL)
            put("effective_source_speed",frame.effectiveSourceSpeed?.let(::rounded) ?: JSONObject.NULL)
            put("mask_is_opacity", frame.maskIsOpacity)
            put("subject_quality", rounded(frame.subjectQuality))
            put("subject_occlusion", rounded(frame.subjectOcclusion))
            put("mask_temporal_iou", rounded(frame.maskTemporalIou))
            put("secondary_source_us", frame.secondarySourceTimeUs ?: -1L)
        } }))
    }

    private fun containerJson(container: ContainerEvidence) = JSONObject().apply {
        put("accepted", container.issues.isEmpty())
        put("video_mime", container.videoMime); put("audio_mime", container.audioMime)
        put("width", container.width); put("height", container.height); put("rotation", container.rotation)
        put("video_fps", container.videoFps ?: JSONObject.NULL); put("duration_ms", container.durationMs)
        put("video_samples", container.videoSamples); put("audio_samples", container.audioSamples)
        put("video_first_pts_us", container.videoFirstPtsUs); put("audio_first_pts_us", container.audioFirstPtsUs)
        put("video_last_pts_us", container.videoLastPtsUs); put("audio_last_pts_us", container.audioLastPtsUs)
        put("av_delta_us", container.avDeltaUs)
        put("issues", JSONArray(container.issues))
    }

    private data class TrackScan(val samples: Int, val firstPtsUs: Long, val lastPtsUs: Long)

    /** New project path: use the frozen profile, never infer dimensions from the recipe. */
    internal fun inspectContainer(mp4: File, profile: ExportProfile, targetDurationMs: Long): ContainerEvidence {
        val evidence = inspectContainer(mp4, profile.size.width, profile.size.height)
        val exact = ExportContract.validateExact(evidence.toExportProbe(), profile, targetDurationMs)
        return evidence.copy(issues = (evidence.issues + exact.issues).distinct())
    }

    internal fun inspectContainer(mp4: File, expectedWidth: Int = 0, expectedHeight: Int = 0): ContainerEvidence {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(mp4.absolutePath)
            var videoIndex = -1; var audioIndex = -1
            var videoMime = ""; var audioMime = ""
            var width = 0; var height = 0; var rotation = 0
            var videoFps: Int? = null; var durationMs = 0L
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") && videoIndex < 0 -> {
                        videoIndex = index; videoMime = mime
                        width = format.integerOrZero(MediaFormat.KEY_WIDTH)
                        height = format.integerOrZero(MediaFormat.KEY_HEIGHT)
                        rotation = format.integerOrZero(MediaFormat.KEY_ROTATION)
                        videoFps = format.integerOrZero(MediaFormat.KEY_FRAME_RATE).takeIf { it > 0 }
                        durationMs = if (format.containsKey(MediaFormat.KEY_DURATION))
                            format.getLong(MediaFormat.KEY_DURATION) / 1_000L else 0L
                    }
                    mime.startsWith("audio/") && audioIndex < 0 -> { audioIndex = index; audioMime = mime }
                }
            }
            val video = scanTrack(mp4, videoIndex)
            val audio = scanTrack(mp4, audioIndex)
            val delta = if (video.samples > 0 && audio.samples > 0) abs(video.lastPtsUs - audio.lastPtsUs) else 0L
            val issues = containerIssues(videoMime, audioMime, width, height, rotation,
                video.samples, audio.samples, video.firstPtsUs, delta,
                audio.firstPtsUs, expectedWidth, expectedHeight)
            ContainerEvidence(videoMime, audioMime, width, height, rotation, video.samples, audio.samples,
                video.firstPtsUs, audio.firstPtsUs, video.lastPtsUs, audio.lastPtsUs, delta, issues,
                videoFps, durationMs)
        } finally { extractor.release() }
    }

    private fun scanTrack(mp4: File, trackIndex: Int): TrackScan {
        if (trackIndex < 0) return TrackScan(0, 0L, 0L)
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(mp4.absolutePath); extractor.selectTrack(trackIndex)
            var count = 0; var first = 0L; var last = 0L
            var previous = Long.MIN_VALUE; var monotonic = true
            while (true) {
                val pts = extractor.sampleTime
                if (pts < 0L) break
                if (count == 0) first = pts
                if (count > 0 && pts <= previous) monotonic = false
                previous = pts; last = pts; count++
                if (!extractor.advance()) break
            }
            TrackScan(if (monotonic) count else -count, first, last)
        } finally { extractor.release() }
    }

    private fun MediaFormat.integerOrZero(key: String): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

    internal fun containerIssues(
        videoMime: String,
        audioMime: String,
        width: Int,
        height: Int,
        rotation: Int,
        videoSamples: Int,
        audioSamples: Int,
        videoFirstPtsUs: Long,
        avDeltaUs: Long,
        audioFirstPtsUs: Long = 0L,
        expectedWidth: Int = 0,
        expectedHeight: Int = 0
    ): List<String> = buildList {
        if (videoMime.isBlank() || videoSamples == 0) add("encoded_video_track_missing")
        if (audioMime.isBlank() || audioSamples == 0) add("encoded_aac_track_missing")
        if (videoSamples < 0 || audioSamples < 0) add("non_monotonic_sample_pts")
        if (videoSamples > 0 && videoFirstPtsUs != 0L) add("video_track_does_not_start_at_pts_zero")
        if (audioSamples > 0 && audioFirstPtsUs != 0L) add("audio_track_does_not_start_at_pts_zero")
        if (videoMime.isNotBlank() && videoMime != "video/avc") add("encoded_video_is_not_avc")
        if (audioMime.isNotBlank() && audioMime != "audio/mp4a-latm") add("encoded_audio_is_not_aac")
        if (avDeltaUs > 50_000L) add("av_sample_clock_drift_over_50ms")
        if (width <= 0 || height <= 0) add("encoded_dimensions_missing")
        if (rotation !in setOf(0, 90, 180, 270)) add("invalid_rotation_metadata")
        if (expectedWidth > 0 && expectedHeight > 0) {
            if (width != expectedWidth || height != expectedHeight) add("encoded_dimensions_do_not_match_export")
            // Export burns source orientation into pixels; another rotation would rotate it twice.
            if (rotation != 0) add("encoded_rotation_not_baked")
        }
    }

    private fun summaryJson(summary: Summary) = JSONObject().apply {
        put("accepted", summary.accepted)
        put("planned_frames", summary.plannedFrames); put("shader_frames", summary.shaderFrames)
        put("authored_transition_events", summary.authoredTransitionEvents)
        put("rendered_transition_events", summary.renderedTransitionEvents)
        put("rendered_transition_frames", summary.renderedTransitionFrames)
        put("dual_decoder_frames", summary.dualDecoderFrames)
        put("speed_ramp_frames", summary.speedRampFrames); put("virtual_camera_frames", summary.virtualCameraFrames)
        put("blackout_frames", summary.blackoutFrames)
        put("layer_frames", summary.layerFrames)
        put("temporal_layer_frames", summary.temporalLayerFrames)
        put("foreground_reentry_source_pts", summary.foregroundReentrySourcePts)
        put("subject_stage_source_pts", summary.subjectStageSourcePts)
        put("max_blur", rounded(summary.maxBlur)); put("max_blackout", rounded(summary.maxBlackout)); put("max_occlusion", rounded(summary.maxOcclusion))
        put("max_foreground_reentry", rounded(summary.maxForegroundReentry))
        put("transition_frames_by_type", JSONObject().apply { summary.transitionFramesByType.forEach { (type, count) -> put(type.name, count) } })
        put("missing_transition_clip_ids", JSONArray(summary.missingTransitionClipIds))
        put("issues", JSONArray(summary.issues))
    }

    private fun writeContactSheet(mp4: File, evidence: List<FrameEvidence>, destination: File): Boolean {
        if (evidence.isEmpty()) return false
        val columns = 4; val rows = 5; val cellWidth = 180; val cellHeight = 320
        val bitmap = Bitmap.createBitmap(columns * cellWidth, rows * cellHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.BLACK)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(mp4.absolutePath)
            repeat(columns * rows) { index ->
                val evidenceIndex = if (columns * rows == 1) 0 else index * evidence.lastIndex / (columns * rows - 1)
                drawCell(canvas, retriever, evidence[evidenceIndex], index % columns * cellWidth, index / columns * cellHeight, cellWidth, cellHeight)
            }
            destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        } finally { retriever.release(); bitmap.recycle() }
    }

    private fun writeTransitionSheet(mp4: File, evidence: List<FrameEvidence>, destination: File): Boolean {
        val groups = evidence.filter {
            it.transition in visibleTransitions && it.dualDecoder &&
                (it.blur > .0001f || it.blackout > .0001f || it.occlusion > .0001f ||
                    it.foregroundReentry > .0001f)
        }.groupBy { it.clipIndex }.values.take(8)
        if (groups.isEmpty()) return false
        val cellWidth = 240; val cellHeight = 400
        val bitmap = Bitmap.createBitmap(cellWidth * 3, cellHeight * groups.size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.BLACK)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(mp4.absolutePath)
            groups.forEachIndexed { row, group ->
                val first = group.first()
                val middle = group.maxBy {
                    maxOf(it.blur, it.blackout, it.occlusion, it.foregroundReentry)
                }
                val last = group.last()
                val before = evidence.lastOrNull { it.outputTimeUs < first.outputTimeUs } ?: first
                val after = evidence.firstOrNull { it.outputTimeUs > last.outputTimeUs } ?: last
                listOf(before, middle, after).forEachIndexed { column, frame ->
                    drawCell(canvas, retriever, frame, column * cellWidth, row * cellHeight, cellWidth, cellHeight)
                }
            }
            destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } finally { retriever.release(); bitmap.recycle() }
    }

    /** Exact half-second checkpoints for comparing the authored live entrance with its reference. */
    private fun writeOpeningSheet(mp4: File, evidence: List<FrameEvidence>, destination: File): Boolean {
        val opening = evidence.filter { it.transition == MontageGraph.Transition.FOREGROUND_REENTRY }
        if (opening.isEmpty()) return false
        val startUs = opening.first().outputTimeUs
        val checkpointsUs = listOf(0L, 500_000L, 1_000_000L, 1_500_000L, 2_000_000L, 2_500_000L)
        val cellWidth = 240
        val cellHeight = 400
        val bitmap = Bitmap.createBitmap(cellWidth * 3, cellHeight * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.BLACK) }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(mp4.absolutePath)
            checkpointsUs.forEachIndexed { index, offsetUs ->
                val frame = opening.minBy { abs(it.outputTimeUs - (startUs + offsetUs)) }
                drawCell(
                    canvas,
                    retriever,
                    frame,
                    index % 3 * cellWidth,
                    index / 3 * cellHeight,
                    cellWidth,
                    cellHeight
                )
            }
            destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        } finally {
            retriever.release()
            bitmap.recycle()
        }
    }

    /** One row per contiguous authored layer: frame before, peak opacity, frame after. */
    private fun writeLayerSheet(mp4: File, evidence: List<FrameEvidence>, destination: File): Boolean {
        val active = evidence.filter { it.layerOpacity > .01f }
        if (active.isEmpty()) return false
        val groups = ArrayList<MutableList<FrameEvidence>>()
        active.forEach { frame ->
            val current = groups.lastOrNull()
            val previous = current?.lastOrNull()
            if (current == null || previous == null || frame.layerKind != previous.layerKind ||
                frame.outputTimeUs - previous.outputTimeUs > 70_000L) {
                groups += arrayListOf(frame)
            } else {
                current += frame
            }
        }
        val rows = groups.take(12)
        val cellWidth = 240; val cellHeight = 400
        val bitmap = Bitmap.createBitmap(cellWidth * 3, cellHeight * rows.size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.BLACK)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(mp4.absolutePath)
            rows.forEachIndexed { row, group ->
                val first = group.first()
                val middle = group.maxBy { it.layerOpacity }
                val last = group.last()
                val before = evidence.lastOrNull { it.outputTimeUs < first.outputTimeUs } ?: first
                val after = evidence.firstOrNull { it.outputTimeUs > last.outputTimeUs } ?: last
                listOf(before, middle, after).forEachIndexed { column, frame ->
                    drawCell(
                        canvas,
                        retriever,
                        frame,
                        column * cellWidth,
                        row * cellHeight,
                        cellWidth,
                        cellHeight
                    )
                }
            }
            destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } finally { retriever.release(); bitmap.recycle() }
    }

    private fun drawCell(
        canvas: Canvas,
        retriever: MediaMetadataRetriever,
        frame: FrameEvidence,
        left: Int,
        top: Int,
        width: Int,
        height: Int
    ) {
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(frame.outputTimeUs, MediaMetadataRetriever.OPTION_CLOSEST, width, height)
        } else retriever.getFrameAtTime(frame.outputTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        if (decoded != null) {
            canvas.drawBitmap(decoded, null, Rect(left, top, left + width, top + height), null)
            decoded.recycle()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xdd000000.toInt() }
        canvas.drawRect(left.toFloat(), (top + height - 48).toFloat(), (left + width).toFloat(), (top + height).toFloat(), paint)
        paint.color = Color.WHITE; paint.textSize = 16f
        canvas.drawText(String.format(Locale.US, "%.2fs c%d %s", frame.outputTimeUs / 1_000_000f, frame.clipIndex, frame.transition.name), left + 6f, top + height - 27f, paint)
        paint.textSize = 13f; paint.color = if (frame.dualDecoder) 0xffff4a32.toInt() else 0xffb9c3cb.toInt()
        canvas.drawText(
            String.format(
                Locale.US,
                "2D:%s L:%s b:%.2f k:%.2f",
                frame.dualDecoder,
                frame.layerKind.name.take(4),
                frame.blur,
                frame.blackout
            ),
            left + 6f,
            top + height - 8f,
            paint
        )
    }

    private fun prune(directory: File) {
        val reports = directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending(File::lastModified).orEmpty()
        reports.drop(8).forEach { report ->
            val stem = report.nameWithoutExtension
            report.delete(); File(directory, "${stem}_contact.jpg").delete(); File(directory, "${stem}_transitions.jpg").delete()
            File(directory, "${stem}_layers.jpg").delete(); File(directory, "${stem}_opening.jpg").delete()
        }
    }

    private fun rounded(value: Float): Double = String.format(Locale.US, "%.5f", value).toDouble()

    private const val MINIMUM_VISIBLE_TEMPORAL_SEPARATION_US = 750_000L
    private const val MINIMUM_LIVE_FOREGROUND_SOURCE_PTS = 8
    private const val MINIMUM_LIVE_SUBJECT_STAGE_SOURCE_PTS = 8
}
