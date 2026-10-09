package com.example.autoedit

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Replays motion only: exact freshly decoded PTS + existing current-frame semantic masks.
 * No new semantic inference, no director, no MP4, no acceptance result.
 */
internal object MotionEvidenceProbe {
    fun run(context: Context, source: File): JSONObject {
        val cached = requireNotNull(MediaFrameAnalysisCache.load(context, MediaFrameAnalysisCache.key(source, 250_000,
            SourceAnalysisProfile.EDITORIAL_WITH_CORRESPONDENCE))) {
            "Probe needs the existing production analysis cache; will not silently recompute semantics"
        }
        val semantics = cached.attachments.frames.associateBy { it.sourceTimeUs }
        val rows = JSONArray()
        val stages = mutableMapOf<PersonMotionEvidence.Stage, Int>()
        var previous: LumaMotionEstimator.Plane? = null
        var previousTimeUs: Long? = null
        var agreements = 0
        var cameraAgreements = 0
        var subjectAgreements = 0
        var motionElapsedUs = 0L
        MulticlassMatteClient.analyze(context, source, 250_000).use { decoded ->
            require(decoded.decodedTimesUs.toList() == cached.observations.map { it.sourceTimeUs }) {
                "Fresh decode PTS differs from the cached production observations"
            }
            cached.observations.forEach { observation ->
                val bitmap = requireNotNull(decoded.decodedFrame(observation.sourceTimeUs))
                val plane = try { MediaFrameVisualAnalyzer.correspondencePlane(bitmap) } finally { bitmap.recycle() }
                val attachment = semantics[observation.sourceTimeUs]
                val startedNs = System.nanoTime()
                val audit = PersonMotionEvidence.assess(previous, plane, previousTimeUs, observation.sourceTimeUs,
                    attachment?.sourceTimeUs, attachment?.mask, observation.humanPresenceConfidence)
                val elapsedUs = (System.nanoTime() - startedNs) / 1_000L
                motionElapsedUs += elapsedUs
                val subjectAgrees = audit.measurement == observation.motionMeasurement
                val cameraAgrees = audit.cameraMeasurement == observation.cameraMeasurement
                val agrees = subjectAgrees && cameraAgrees
                if (agrees) agreements++
                if (subjectAgrees) subjectAgreements++
                if (cameraAgrees) cameraAgreements++
                stages[audit.stage] = (stages[audit.stage] ?: 0) + 1
                rows.put(JSONObject().apply {
                    put("pts_us", observation.sourceTimeUs)
                    put("analysis_width", plane.width)
                    put("analysis_height", plane.height)
                    put("stage", audit.stage.name)
                    put("motion_elapsed_us", elapsedUs)
                    put("cache_measurement_agrees", agrees)
                    put("cache_subject_measurement_agrees", subjectAgrees)
                    put("cache_camera_measurement_agrees", cameraAgrees)
                    put("face_inference_succeeded", observation.faceInferenceSucceeded)
                    put("gesture_evidence_available", observation.gestureEvidenceAvailable)
                    put("measured_subject_intensity", audit.measurement?.subjectIntensity ?: JSONObject.NULL)
                    put("measured_camera_x", audit.measurement?.cameraX ?: JSONObject.NULL)
                    put("measured_camera_y", audit.measurement?.cameraY ?: JSONObject.NULL)
                    fun camera(value: VisualEventMap.CameraMeasurement?) = value?.let {
                        JSONObject().apply {
                            put("x", it.x); put("y", it.y); put("intensity", it.intensity)
                            put("confidence", it.confidence); put("cells", it.cells); put("quadrants", it.quadrants)
                            put("previous_pts_us", it.previousTimeUs); put("current_pts_us", it.currentTimeUs)
                            put("semantic_pts_us", it.semanticTimeUs); put("interval_us", it.intervalUs)
                            put("method", it.method)
                        }
                    } ?: JSONObject.NULL
                    put("independent_camera_measurement", camera(audit.cameraMeasurement))
                    put("raw_supported_camera", audit.rawCamera?.let {
                        JSONObject().apply {
                            put("x", it.x); put("y", it.y); put("confidence", it.confidence)
                            put("cells", it.agreeingCells); put("quadrants", it.coveredQuadrants)
                        }
                    } ?: JSONObject.NULL)
                    put("mask_confidence", attachment?.mask?.confidence ?: JSONObject.NULL)
                    put("human_confidence", observation.humanPresenceConfidence)
                    put("cells", audit.cells)
                    put("unique_centers", audit.uniqueCenters)
                    put("textured_cells", audit.texturedCells)
                    put("fitted_cells", audit.fittedCells)
                    put("unique_match_cells", audit.uniqueMatchCells)
                    put("roundtrip_cells", audit.roundtripCells)
                    put("reliable_cells", audit.reliableCells)
                    put("background_cells", audit.backgroundCells)
                    put("background_quadrants", audit.backgroundQuadrants)
                    put("person_cells", audit.personCells)
                    fun support(value: PersonMotionEvidence.SupportCounts) = JSONObject().apply {
                        put("candidates", value.candidates); put("textured", value.textured)
                        put("fitted", value.fitted); put("unique_match", value.uniqueMatch)
                        put("roundtrip", value.roundtrip); put("reliable", value.reliable)
                    }
                    put("center_background_support", support(audit.centerBackground))
                    put("clean_query_background_support", support(audit.cleanQueryBackground))
                    put("person_support", support(audit.personSupport))
                    put("mixed_background_patches", audit.mixedBackgroundPatches)
                    put("local_person_support", audit.localPersonSupport?.let(::support) ?: JSONObject.NULL)
                    put("local_person_grid_cells", audit.localPersonGridCells ?: JSONObject.NULL)
                    put("local_person_witnesses", audit.localPersonWitnesses ?: JSONObject.NULL)
                    put("subject_supported_fraction", audit.raw?.subject?.supportedPersonFraction ?: JSONObject.NULL)
                })
                previous = plane
                previousTimeUs = observation.sourceTimeUs
            }
        }
        return JSONObject().apply {
            put("schema_version", 5)
            put("cache_version", MediaFrameAnalysisCache.VERSION)
            put("probe_only", true)
            put("method", "fresh-exact-pts-pixels-plus-cached-current-frame-semantics")
            put("motion_method", MotionAnalysisGeometry.METHOD)
            put("observations", rows.length())
            put("cached_measurement_agreements", agreements)
            put("cached_subject_measurement_agreements", subjectAgreements)
            put("cached_camera_measurement_agreements", cameraAgreements)
            put("motion_elapsed_total_us", motionElapsedUs)
            put("stages", JSONObject(stages.mapKeys { it.key.name }))
            put("samples", rows)
            put("limitation", "Replayed cached masks/human labels; not new semantic evidence, render, or human acceptance")
        }
    }
}
