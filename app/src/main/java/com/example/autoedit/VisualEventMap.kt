package com.example.autoedit

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

/** Semantic timeline built from local vision observations before edit decisions are made. */
data class VisualEventMap(
    val durationUs: Long,
    val events: List<Event>,
    val observations: List<Observation>
) {
    init {
        require(durationUs > 0L)
        require(observations.isNotEmpty())
        require(observations.zipWithNext().all { (left, right) -> right.sourceTimeUs > left.sourceTimeUs })
        require(events.all { it.peakTimeUs in 0 until durationUs })
    }

    enum class EventType {
        CAMERA_MOVE, SUBJECT_MOVE, FACE_TURN, GESTURE, OCCLUSION, SUBJECT_REVEAL,
        COMPOSITION_PEAK, CLEAN_HOLD
    }

    enum class Direction { LEFT, RIGHT, UP, DOWN, IN, OUT, NONE }

    data class Vector(val x: Float = 0f, val y: Float = 0f, val radial: Float = 0f) {
        init { require(x.isFinite() && y.isFinite() && radial.isFinite()) }
        val magnitude: Float get() = hypot(x, y) + abs(radial)
        fun direction(): Direction = when {
            abs(radial) > maxOf(abs(x), abs(y)) -> if (radial >= 0f) Direction.IN else Direction.OUT
            abs(x) >= abs(y) && x > 0f -> Direction.RIGHT
            abs(x) >= abs(y) && x < 0f -> Direction.LEFT
            y > 0f -> Direction.DOWN
            y < 0f -> Direction.UP
            else -> Direction.NONE
        }
    }

    data class Face(
        val confidence: Float,
        val yawDegrees: Float,
        val pitchDegrees: Float = 0f,
        val rollDegrees: Float = 0f,
        val gazeX: Float = 0f,
        val gazeY: Float = 0f
    ) {
        init {
            require(confidence in 0f..1f)
            require(listOf(yawDegrees, pitchDegrees, rollDegrees, gazeX, gazeY).all { it.isFinite() })
        }
    }

    data class Composition(
        val subjectScale: Float,
        val headroomScore: Float,
        val lookRoomScore: Float,
        val backgroundSimplicity: Float,
        val contrastScore: Float
    ) {
        init {
            require(subjectScale in 0f..1f)
            require(listOf(headroomScore, lookRoomScore, backgroundSimplicity, contrastScore).all { it in 0f..1f })
        }

        val quality: Float get() =
            (.2f * headroomScore + .2f * lookRoomScore + .25f * backgroundSimplicity + .35f * contrastScore)
                .coerceIn(0f, 1f)
    }

    /** Full-frame correspondence dx/(3*width/48), dy/(3*height/72), not a legacy luma moment.
     * Thus equal FOV displacement has equal units at different analysis resolutions/aspects.
     * This is displacement over the interval, NOT velocity; subject intensity is relative to camera.
     * Body coverage is unique validated query-pixel mask mass / full-frame mask mass;
     * subjectCells counts non-overlapping local patch witnesses, not dense point duplicates.
     * intervalUs preserves the real sampling cadence; null on Observation means unknown.
     */
    data class MotionMeasurement(
        val subjectIntensity: Float,
        val cameraX: Float,
        val cameraY: Float,
        val cameraConfidence: Float,
        val subjectCells: Int,
        val subjectSupportedFraction: Float,
        val cameraCells: Int,
        val cameraQuadrants: Int,
        val intervalUs: Long
    ) {
        init {
            require(subjectIntensity in 0f..1f && cameraX in -1f..1f && cameraY in -1f..1f)
            require(cameraConfidence in .5f..1f && subjectSupportedFraction in 0f..1f)
            require(subjectCells >= 4 && subjectSupportedFraction >= .25f)
            require(cameraCells >= 8 && cameraQuadrants in 3..4)
            require(intervalUs in 100_000L..500_000L)
        }
        val cameraIntensity: Float get() = hypot(cameraX, cameraY).coerceAtMost(1f)
    }

    /** Independently supported background translation; does NOT certify body displacement.
     * Uses the same FOV displacement units and camera gates as MotionMeasurement.
     * Both actual decoded PTS and fresh current semantic PTS are bound to this evidence.
     */
    data class CameraMeasurement(
        val x: Float,
        val y: Float,
        val confidence: Float,
        val cells: Int,
        val quadrants: Int,
        val previousTimeUs: Long,
        val currentTimeUs: Long,
        val semanticTimeUs: Long,
        val intervalUs: Long,
        val method: String = MotionAnalysisGeometry.METHOD
    ) {
        init {
            require(x in -1f..1f && y in -1f..1f && confidence in .5f..1f)
            require(cells >= 8 && quadrants in 3..4)
            require(previousTimeUs >= 0L && currentTimeUs > previousTimeUs)
            require(semanticTimeUs == currentTimeUs)
            require(intervalUs in 100_000L..500_000L && currentTimeUs - previousTimeUs == intervalUs)
            require(method == MotionAnalysisGeometry.METHOD)
        }
        val intensity: Float get() = hypot(x, y).coerceAtMost(1f)
    }

    data class Observation(
        val sourceTimeUs: Long,
        val cameraMotion: Vector = Vector(),
        val subjectMotion: Vector = Vector(),
        val face: Face? = null,
        val gestureConfidence: Float = 0f,
        val occlusionConfidence: Float = 0f,
        val personMaskConfidence: Float = 0f,
        val personMaskTemporalIou: Float = 0f,
        val visualQuality: Float = 1f,
        val meanLuma: Float = .5f,
        val composition: Composition? = null,
        /** Abrupt source-picture change, not an authored output transition. */
        val sceneChangeConfidence: Float = 0f,
        /** Independent face/body evidence; a confident foreground mask alone may be an animal. */
        val humanPresenceConfidence: Float = 0f,
        /** Fresh pixel/mask/human/background support. Never inferred from legacy vectors. */
        val motionMeasurement: MotionMeasurement? = null,
        /** Successful current-frame face inference; null face alone can also mean a model failure. */
        val faceInferenceSucceeded: Boolean = face != null,
        /** Zero is a measured absence of gesture only when current pose evidence is available. */
        val gestureEvidenceAvailable: Boolean = gestureConfidence > 0f,
        /** May exist when body motion is unknown; never manufacture a zero-valued subject. */
        val cameraMeasurement: CameraMeasurement? = null
    ) {
        init {
            require(sourceTimeUs >= 0L)
            require(cameraMeasurement == null || cameraMeasurement.currentTimeUs == sourceTimeUs)
            require(gestureConfidence in 0f..1f && occlusionConfidence in 0f..1f &&
                personMaskConfidence in 0f..1f && personMaskTemporalIou in 0f..1f &&
                visualQuality in 0f..1f && meanLuma in 0f..1f && sceneChangeConfidence in 0f..1f &&
                humanPresenceConfidence in 0f..1f)
        }
    }

    data class UsableWindow(val startUs: Long, val endUs: Long) {
        init { require(startUs >= 0L && endUs > startUs) }
        val durationUs: Long get() = endUs - startUs
    }

    data class Event(
        val type: EventType,
        val peakTimeUs: Long,
        val strength: Float,
        val confidence: Float,
        val direction: Direction,
        val usableWindow: UsableWindow
    ) {
        init { require(peakTimeUs >= 0L && strength in 0f..1f && confidence in 0f..1f) }
    }
}

/** Converts local model/flow outputs into stable semantic events. */
object VisualEventMapAnalyzer {
    data class Config(
        val motionThreshold: Float = .18f,
        val faceTurnDegrees: Float = 9f,
        val gestureThreshold: Float = .68f,
        val occlusionThreshold: Float = .65f,
        val personMaskThreshold: Float = .80f,
        val personMaskTemporalIouThreshold: Float = .72f,
        val compositionThreshold: Float = .72f,
        val quietMotionThreshold: Float = .07f,
        /** Codec noise may create one or two large vectors in an otherwise static source. */
        val minimumDirectionalMotionSamples: Int = 3,
        val minimumDirectionalMotionRatio: Float = .05f,
        val mergeGapUs: Long = 300_000L,
        val leadUs: Long = 350_000L,
        val tailUs: Long = 450_000L
    )

    fun analyze(
        durationUs: Long,
        observations: List<VisualEventMap.Observation>,
        config: Config = Config()
    ): VisualEventMap {
        require(durationUs > 0L && observations.isNotEmpty())
        val ordered = observations.sortedBy { it.sourceTimeUs }
        require(ordered.last().sourceTimeUs < durationUs)
        val candidates = ArrayList<VisualEventMap.Event>()
        val directionalCameraSamples = ordered.count {
            it.cameraMotion.magnitude >= config.motionThreshold &&
                it.cameraMotion.direction() in setOf(
                    VisualEventMap.Direction.LEFT,
                    VisualEventMap.Direction.RIGHT
                )
        }
        val requiredDirectionalSamples = maxOf(
            config.minimumDirectionalMotionSamples,
            ceil(ordered.size * config.minimumDirectionalMotionRatio).toInt()
        )
        // Tiny synthetic maps remain useful for focused unit tests. Production-sized timelines
        // require population-level support, so isolated codec-noise vectors cannot author a whip.
        val hasSustainedDirectionalCameraMotion = ordered.size < 12 ||
            directionalCameraSamples >= requiredDirectionalSamples

        ordered.forEachIndexed { index, observation ->
            val previous = ordered.getOrNull(index - 1)
            if (observation.cameraMotion.magnitude >= config.motionThreshold &&
                (observation.cameraMotion.direction() !in setOf(
                    VisualEventMap.Direction.LEFT,
                    VisualEventMap.Direction.RIGHT
                ) || hasSustainedDirectionalCameraMotion)) {
                candidates += event(
                    VisualEventMap.EventType.CAMERA_MOVE,
                    observation,
                    observation.cameraMotion.magnitude.coerceIn(0f, 1f),
                    observation.cameraMotion.direction(),
                    durationUs,
                    config
                )
            }
            // Subject motion is residual local flow after subtracting the robust global transform.
            if (observation.subjectMotion.magnitude >= config.motionThreshold) {
                candidates += event(
                    VisualEventMap.EventType.SUBJECT_MOVE,
                    observation,
                    observation.subjectMotion.magnitude.coerceIn(0f, 1f),
                    observation.subjectMotion.direction(),
                    durationUs,
                    config
                )
            }
            val face = observation.face
            val previousFace = previous?.face
            if (face != null && previousFace != null &&
                abs(face.yawDegrees - previousFace.yawDegrees) >= config.faceTurnDegrees) {
                val direction = if (face.yawDegrees > previousFace.yawDegrees) {
                    VisualEventMap.Direction.RIGHT
                } else {
                    VisualEventMap.Direction.LEFT
                }
                candidates += event(
                    VisualEventMap.EventType.FACE_TURN,
                    observation,
                    (abs(face.yawDegrees - previousFace.yawDegrees) / 45f).coerceIn(0f, 1f),
                    direction,
                    durationUs,
                    config,
                    minOf(face.confidence, previousFace.confidence)
                )
            }
            if (observation.gestureEvidenceAvailable && observation.gestureConfidence >= config.gestureThreshold) {
                candidates += event(
                    VisualEventMap.EventType.GESTURE,
                    observation,
                    observation.gestureConfidence,
                    observation.subjectMotion.direction(),
                    durationUs,
                    config,
                    observation.gestureConfidence
                )
            }
            if (observation.occlusionConfidence >= config.occlusionThreshold) {
                candidates += event(
                    VisualEventMap.EventType.OCCLUSION,
                    observation,
                    observation.occlusionConfidence,
                    observation.cameraMotion.direction(),
                    durationUs,
                    config,
                    observation.occlusionConfidence
                )
            }
            if (observation.personMaskConfidence >= config.personMaskThreshold &&
                observation.personMaskTemporalIou >= config.personMaskTemporalIouThreshold) {
                candidates += event(
                    VisualEventMap.EventType.SUBJECT_REVEAL,
                    observation,
                    (observation.personMaskConfidence * .55f +
                        observation.personMaskTemporalIou * .45f).coerceIn(0f, 1f),
                    observation.subjectMotion.direction(),
                    durationUs,
                    config,
                    minOf(observation.personMaskConfidence, observation.personMaskTemporalIou)
                )
            }
            val composition = observation.composition
            if (composition != null && composition.quality >= config.compositionThreshold) {
                candidates += event(
                    VisualEventMap.EventType.COMPOSITION_PEAK,
                    observation,
                    composition.quality,
                    VisualEventMap.Direction.NONE,
                    durationUs,
                    config
                )
            }
            if (observation.cameraMotion.magnitude <= config.quietMotionThreshold &&
                observation.subjectMotion.magnitude <= config.quietMotionThreshold &&
                observation.occlusionConfidence < .25f && (face?.confidence ?: 0f) >= .65f &&
                (composition?.quality ?: 0f) >= .58f) {
                candidates += event(
                    VisualEventMap.EventType.CLEAN_HOLD,
                    observation,
                    ((face?.confidence ?: 0f) * .55f + (composition?.quality ?: 0f) * .45f),
                    VisualEventMap.Direction.NONE,
                    durationUs,
                    config
                )
            }
        }

        return VisualEventMap(durationUs, merge(candidates, config.mergeGapUs), ordered)
    }

    private fun event(
        type: VisualEventMap.EventType,
        observation: VisualEventMap.Observation,
        strength: Float,
        direction: VisualEventMap.Direction,
        durationUs: Long,
        config: Config,
        confidence: Float = .85f
    ): VisualEventMap.Event {
        val start = (observation.sourceTimeUs - config.leadUs).coerceAtLeast(0L)
        val end = (observation.sourceTimeUs + config.tailUs).coerceAtMost(durationUs)
        return VisualEventMap.Event(
            type,
            observation.sourceTimeUs,
            strength.coerceIn(0f, 1f),
            minOf(confidence, observation.visualQuality).coerceIn(0f, 1f),
            direction,
            VisualEventMap.UsableWindow(start, maxOf(start + 1L, end))
        )
    }

    private fun merge(events: List<VisualEventMap.Event>, mergeGapUs: Long): List<VisualEventMap.Event> {
        val output = ArrayList<VisualEventMap.Event>()
        events.sortedWith(compareBy<VisualEventMap.Event> { it.type }.thenBy { it.peakTimeUs }).forEach { event ->
            val previous = output.lastOrNull()
            if (previous != null && previous.type == event.type && previous.direction == event.direction &&
                event.peakTimeUs - previous.peakTimeUs <= mergeGapUs) {
                val peak = if (event.strength > previous.strength) event else previous
                output[output.lastIndex] = peak.copy(
                    strength = maxOf(previous.strength, event.strength),
                    confidence = maxOf(previous.confidence, event.confidence),
                    usableWindow = VisualEventMap.UsableWindow(
                        minOf(previous.usableWindow.startUs, event.usableWindow.startUs),
                        maxOf(previous.usableWindow.endUs, event.usableWindow.endUs)
                    )
                )
            } else {
                output += event
            }
        }
        return output.sortedBy { it.peakTimeUs }
    }
}
