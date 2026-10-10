package com.veycad.app

/** Immutable, Android-free edit contract shared by preview and export. */
data class MontageGraph(
    val sourceDurationMs: Long,
    val outputDurationMs: Long,
    val profile: Profile = Profile.AUTOMATIC_PORTRAIT,
    val clips: List<Clip>,
    val audioTrack: AudioTrack? = null,
    val overlays: List<Overlay> = emptyList(),
    val metadata: NleProjectMetadata = NleProjectMetadata(),
    val parameterTracks: List<ParameterTrack> = emptyList(),
    val frameAttachments: FrameAttachmentTimeline = FrameAttachmentTimeline(),
    val effectGraph: GpuEffectGraph = GpuEffectGraph(),
    val version: Int = CURRENT_VERSION,
    val editableTiming: EditableFrameTiming? = null,
    val manualMontageState: ManualMontageState? = null
) {
    init {
        require(sourceDurationMs > 0L && outputDurationMs > 0L && clips.isNotEmpty())
        require(clips.sumOf { it.outputDurationMs } == outputDurationMs)
        require(version in 1..CURRENT_VERSION)
        require(metadata.schemaVersion == version || version == 1)
        require(parameterTracks.map { it.id }.distinct().size == parameterTracks.size)
        require(clips.map { it.id }.distinct().size == clips.size)
        require(overlays.map { it.id }.distinct().size == overlays.size)
        editableTiming?.let { timing ->
            require(version == 3)
            require(timing.clips.map { it.clipId } == clips.map { it.id })
        }
    }

    enum class Profile { AUTOMATIC_PORTRAIT }
    enum class Transition {
        OPEN, HARD_CUT, WHIP, OCCLUSION, FOREGROUND_REENTRY, BLACKOUT, FINAL_HOLD
    }
    enum class Motion { HOLD, PUSH_IN, PUSH_OUT, WHIP_LEFT, WHIP_RIGHT }
    enum class BlendMode { SCREEN, MULTIPLY, OVERLAY, SOFT_LIGHT }
    enum class OverlayKind {
        FLASH, GLOW, VIGNETTE, DOUBLE_EXPOSURE, MIRROR_SLICE, BLACK_FADE, SUBJECT_STAGE
    }
    enum class ShotRole { OPENING, ESTABLISHING, CLOSE, DETAIL, ACTION, FINALE }

    data class AudioTrack(val sourceId: String, val gain: Float = 1f) {
        init { require(sourceId.isNotBlank() && gain in 0f..2f) }
    }

    data class Overlay(
        val id: String,
        val startMs: Long,
        val endMs: Long,
        val kind: String,
        val blendMode: BlendMode = BlendMode.SCREEN,
        val overlayKind: OverlayKind = OverlayKind.FLASH,
        val opacity: Float = .12f,
        val red: Float = 1f,
        val green: Float = 1f,
        val blue: Float = 1f,
        /** Deliberate temporal separation for generic second decoder-backed source layers. */
        val secondarySourceOffsetMs: Long = 0L,
        /** Start of a different, already-directed output moment used as the secondary role. */
        val secondaryTimelineStartMs: Long? = null
    ) {
        init {
            require(id.isNotBlank() && endMs > startMs && opacity in 0f..1f)
            require(secondaryTimelineStartMs == null || secondaryTimelineStartMs >= 0L)
        }
    }

    data class Clip(
        val id: String,
        val sourceStartMs: Long,
        val sourceEndMs: Long,
        val outputDurationMs: Long,
        val role: ShotRole,
        val transitionIn: Transition,
        val motion: Motion,
        val confidence: Float,
        val beatAnchorMs: Long,
        val transform: ClipTransform = ClipTransform.hold(),
        val speedRamp: SpeedRamp = SpeedRamp.constant(),
        val exposureBias: Float = 0f,
        val redBias: Float = 0f,
        val greenBias: Float = 0f,
        val blueBias: Float = 0f,
        val flowStrength: Float = 0f,
        /** Optional authored window when a measured transition is shorter than its preset. */
        val transitionDurationMs: Long? = null,
        /** Index into the renderer request's source-file list. Single-video styles keep zero. */
        val sourceIndex: Int = 0
    ) {
        init {
            require(id.isNotBlank())
            require(sourceStartMs >= 0L && sourceEndMs > sourceStartMs && outputDurationMs > 0L)
            require(confidence in 0f..1f && flowStrength in 0f..1f)
            require(transitionDurationMs == null || transitionDurationMs in 1..outputDurationMs)
            require(sourceIndex >= 0)
        }
    }

    data class SpeedRamp(val keyframes: List<Keyframe>) {
        data class CubicBezier(
            val x1: Float = .25f,
            val y1: Float = .1f,
            val x2: Float = .25f,
            val y2: Float = 1f
        ) {
            init { require(x1 in 0f..1f && x2 in 0f..1f && y1 in -1f..2f && y2 in -1f..2f) }

            fun valueAt(x: Float): Float {
                val target = x.coerceIn(0f, 1f)
                var low = 0f
                var high = 1f
                repeat(14) {
                    val t = (low + high) * .5f
                    if (coordinate(t, x1, x2) < target) low = t else high = t
                }
                return coordinate((low + high) * .5f, y1, y2).coerceIn(0f, 1f)
            }

            private fun coordinate(t: Float, p1: Float, p2: Float): Float {
                val inverse = 1f - t
                return 3f * inverse * inverse * t * p1 + 3f * inverse * t * t * p2 + t * t * t
            }

            companion object { val LINEAR = CubicBezier(0f, 0f, 1f, 1f) }
        }

        data class Keyframe(val at: Float, val speed: Float, val curveToNext: CubicBezier = CubicBezier.LINEAR)

        init {
            require(keyframes.size >= 2)
            require(keyframes.first().at == 0f && keyframes.last().at == 1f)
            require(keyframes.all { it.speed in .35f..2.5f })
            require(keyframes.zipWithNext().all { (left, right) -> right.at > left.at })
        }

        fun speedAt(progress: Float): Float {
            val p = progress.coerceIn(0f, 1f)
            val right = keyframes.firstOrNull { it.at >= p } ?: keyframes.last()
            val left = keyframes.lastOrNull { it.at <= p } ?: keyframes.first()
            if (left === right) return left.speed
            val amount = left.curveToNext.valueAt((p - left.at) / (right.at - left.at))
            return left.speed + (right.speed - left.speed) * amount
        }

        fun sourceFractionAt(progress: Float): Float {
            val p = progress.coerceIn(0f, 1f)
            fun integral(until: Float): Float {
                val steps = 80
                return (0 until steps).sumOf { index ->
                    val left = until * index / steps
                    val right = until * (index + 1) / steps
                    (speedAt((left + right) * .5f) * (right - left)).toDouble()
                }.toFloat()
            }
            return (integral(p) / integral(1f).coerceAtLeast(.001f)).coerceIn(0f, 1f)
        }

        companion object {
            fun constant(speed: Float = 1f) = SpeedRamp(listOf(Keyframe(0f, speed), Keyframe(1f, speed)))
        }
    }

    data class ClipTransform(val keyframes: List<Keyframe>) {
        data class Keyframe(
            val at: Float,
            val scale: Float,
            val translateX: Float = 0f,
            val translateY: Float = 0f,
            val rotationDegrees: Float = 0f
        )

        init {
            require(keyframes.size >= 2)
            require(keyframes.first().at == 0f && keyframes.last().at == 1f)
            require(keyframes.zipWithNext().all { (left, right) -> right.at > left.at })
        }

        fun sample(progress: Float): Keyframe {
            val p = progress.coerceIn(0f, 1f)
            val right = keyframes.firstOrNull { it.at >= p } ?: keyframes.last()
            val left = keyframes.lastOrNull { it.at <= p } ?: keyframes.first()
            if (left === right) return left
            val local = ((p - left.at) / (right.at - left.at)).coerceIn(0f, 1f)
            val eased = local * local * (3f - 2f * local)
            return Keyframe(
                p,
                left.scale + (right.scale - left.scale) * eased,
                left.translateX + (right.translateX - left.translateX) * eased,
                left.translateY + (right.translateY - left.translateY) * eased,
                left.rotationDegrees + (right.rotationDegrees - left.rotationDegrees) * eased
            )
        }

        companion object {
            fun hold(scale: Float = 1f) = ClipTransform(listOf(Keyframe(0f, scale), Keyframe(1f, scale)))
        }
    }

    companion object { const val CURRENT_VERSION = 3 }
}
