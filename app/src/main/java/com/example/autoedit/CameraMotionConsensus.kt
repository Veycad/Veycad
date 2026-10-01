package com.example.autoedit

import kotlin.math.abs

/** Translation support from reliable, spatially distributed background correspondences only. */
internal object CameraMotionConsensus {
    data class Cell(val centerX: Float, val centerY: Float, val x: Float, val y: Float,
                    val confidence: Float, val personSupport: Float) {
        init {
            require(centerX in 0f..1f && centerY in 0f..1f && x in -1f..1f && y in -1f..1f)
            require(confidence in 0f..1f && personSupport in 0f..1f)
        }
    }
    data class Measurement(val x: Float, val y: Float, val confidence: Float,
                           val agreeingCells: Int, val coveredQuadrants: Int)

    fun measure(cells: List<Cell>): Measurement? {
        val background = cells.filter { it.personSupport <= .15f && it.confidence >= .5f }
        if (background.size < 8) return null
        // Half a reference pixel in the three-reference-pixel FOV normalization.
        fun agrees(a: Cell, b: Cell) = abs(a.x - b.x) <= 1f / 6f && abs(a.y - b.y) <= 1f / 6f
        val anchor = background.maxBy { a -> background.filter { agrees(a, it) }.sumOf { it.confidence.toDouble() } }
        val agreeing = background.filter { agrees(anchor, it) }
        val mass = background.sumOf { it.confidence.toDouble() }
        val support = agreeing.sumOf { it.confidence.toDouble() }
        val fraction = support / mass
        if (agreeing.size < 8 || fraction < .7) return null
        val quadrants = agreeing.map { (if (it.centerX >= .5f) 1 else 0) +
            (if (it.centerY >= .5f) 2 else 0) }.toSet().size
        if (quadrants < 3) return null
        return Measurement(
            (agreeing.sumOf { (it.x * it.confidence).toDouble() } / support).toFloat(),
            (agreeing.sumOf { (it.y * it.confidence).toDouble() } / support).toFloat(),
            minOf(fraction.toFloat(), (support / agreeing.size).toFloat()), agreeing.size, quadrants)
    }
}
