package com.veycad.app

import kotlin.math.hypot

/** Direction-free aggregation of independently supported local displacement, not luma change. */
internal object SubjectMotionIntensity {
    data class Cell(val x: Float, val y: Float, val matchingConfidence: Float, val personSupport: Float,
                    val sampleArea: Float = 1f) {
        init {
            require(x.isFinite() && y.isFinite() && x in -1f..1f && y in -1f..1f)
            require(matchingConfidence in 0f..1f && personSupport in 0f..1f)
            require(sampleArea.isFinite() && sampleArea > 0f)
        }
    }

    data class Measurement(val intensity: Float, val supportedCells: Int, val supportedPersonFraction: Float)

    /** All displacements share the same search-radius normalization. null means unknown, not static. */
    fun measure(cells: List<Cell>, cameraX: Float, cameraY: Float,
                cameraConfidence: Float, humanConfidence: Float): Measurement? {
        require(cameraX.isFinite() && cameraY.isFinite() && cameraX in -1f..1f && cameraY in -1f..1f)
        require(cameraConfidence in 0f..1f && humanConfidence in 0f..1f)
        if (cameraConfidence < .5f || humanConfidence < .55f) return null
        val personMass = cells.sumOf { (it.personSupport * it.sampleArea).toDouble() }
        if (personMass <= 0.0) return null
        val supported = cells.filter { it.personSupport >= .5f && it.matchingConfidence >= .5f }
        val supportMass = supported.sumOf { (it.personSupport * it.sampleArea).toDouble() }
        val weight = supported.sumOf { (it.personSupport * it.matchingConfidence * it.sampleArea).toDouble() }
        if (weight <= 0.0) return null
        val magnitude = supported.sumOf {
            // Compute modulus before averaging: opposite gestures must not cancel.
            (hypot(it.x - cameraX, it.y - cameraY).coerceAtMost(1f) *
                it.personSupport * it.matchingConfidence * it.sampleArea).toDouble()
        } / weight
        return Measurement(magnitude.toFloat(), supported.size, (supportMass / personMass).toFloat())
    }
}
