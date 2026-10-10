package com.veycad.app

/**
 * Renderer-neutral animation channel. Unlike the legacy transform and speed classes, the same
 * representation can animate any scalar/vector/colour/effect parameter in preview and export.
 */
data class ParameterTrack(
    val id: String,
    /** Stable address, for example `clip/portrait-2/transform.scale`. */
    val target: String,
    val type: ValueType,
    val keyframes: List<Keyframe>
) {
    enum class ValueType(val dimensions: Int) { SCALAR(1), VEC2(2), VEC3(3), VEC4(4) }

    data class Keyframe(
        val timeUs: Long,
        val value: List<Float>,
        val interpolation: Interpolation = Interpolation.CUBIC,
        val curveToNext: MontageGraph.SpeedRamp.CubicBezier = MontageGraph.SpeedRamp.CubicBezier.LINEAR
    )

    enum class Interpolation { HOLD, LINEAR, CUBIC }

    init {
        require(id.isNotBlank() && target.isNotBlank())
        require(keyframes.isNotEmpty())
        require(keyframes.all { it.timeUs >= 0L && it.value.size == type.dimensions })
        require(keyframes.zipWithNext().all { (left, right) -> right.timeUs > left.timeUs })
    }

    fun sample(timeUs: Long): List<Float> {
        val rightIndex = keyframes.indexOfFirst { it.timeUs >= timeUs }.let { if (it < 0) keyframes.lastIndex else it }
        val right = keyframes[rightIndex]
        if (rightIndex == 0 || right.timeUs == timeUs) return right.value
        val left = keyframes[rightIndex - 1]
        if (left.interpolation == Interpolation.HOLD) return left.value
        val progress = ((timeUs - left.timeUs).toFloat() / (right.timeUs - left.timeUs)).coerceIn(0f, 1f)
        val amount = when (left.interpolation) {
            Interpolation.HOLD -> 0f
            Interpolation.LINEAR -> progress
            Interpolation.CUBIC -> left.curveToNext.valueAt(progress)
        }
        return List(type.dimensions) { index -> left.value[index] + (right.value[index] - left.value[index]) * amount }
    }
}

/** Stable target names shared by the director, preview and render backends. */
object ParameterTargets {
    fun clip(clipId: String, parameter: String) = "clip/$clipId/$parameter"
    fun transition(clipId: String, parameter: String) = "transition/$clipId/$parameter"
    fun overlay(overlayId: String, parameter: String) = "overlay/$overlayId/$parameter"
    const val MASTER_GRADE = "project/grade"
    const val MASTER_AUDIO_GAIN = "project/audio/gain"
}
