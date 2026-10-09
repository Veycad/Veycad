package com.example.autoedit

import kotlin.math.hypot

/** Availability/identity for current normalized wrist landmarks, not physical-motion truth. */
internal object PoseWristEvidence {
    data class Assessment(val complete: Boolean, val maximumDisplacement: Float)

    fun assess(requiredIds: Set<Int>, current: Map<Int, Pair<Float, Float>>,
               previous: Map<Int, Pair<Float, Float>>?): Assessment {
        require(requiredIds.size == 2 && current.keys.all { it in requiredIds })
        require((current.values + previous.orEmpty().values).all { it.first.isFinite() && it.second.isFinite() })
        val displacement = current.entries.maxOfOrNull { (id, point) ->
            previous?.get(id)?.let { prior -> hypot(point.first - prior.first, point.second - prior.second) } ?: 0f
        } ?: 0f
        return Assessment(current.keys == requiredIds, displacement)
    }
}
