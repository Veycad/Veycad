package com.veycad.app

enum class ProjectAspect {
    PORTRAIT_9_16, LANDSCAPE_16_9, SQUARE_1_1, FEED_4_5;

    fun size(shortEdge: Int): OutputSize {
        require(shortEdge > 0)
        val divisor = when (this) {
            PORTRAIT_9_16, LANDSCAPE_16_9 -> 9
            SQUARE_1_1 -> 1
            FEED_4_5 -> 4
        }
        require(shortEdge % divisor == 0) { "Short edge must preserve the exact aspect ratio" }
        val longEdge = when (this) {
            PORTRAIT_9_16, LANDSCAPE_16_9 -> shortEdge.toLong() * 16 / 9
            SQUARE_1_1 -> shortEdge.toLong()
            FEED_4_5 -> shortEdge.toLong() * 5 / 4
        }
        require(longEdge <= Int.MAX_VALUE)
        return if (this == LANDSCAPE_16_9) OutputSize(longEdge.toInt(), shortEdge)
            else OutputSize(shortEdge, longEdge.toInt())
    }
}

enum class ExportQuality(val shortEdge: Int) { P720(720), P1080(1080), K4(2160) }

data class OutputSize(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
}

data class ProjectFormatSelection(val aspect: ProjectAspect, val explicitlySelected: Boolean)

internal object ProjectFormats {
    fun defaultFor(recipe: MontageStyleCatalog.Recipe): ProjectAspect =
        if (recipe == MontageStyleCatalog.Recipe.FEAR_STROBE) ProjectAspect.SQUARE_1_1
        else ProjectAspect.PORTRAIT_9_16

    fun forRecipe(recipe: MontageStyleCatalog.Recipe, current: ProjectFormatSelection): ProjectFormatSelection =
        if (current.explicitlySelected) current else ProjectFormatSelection(defaultFor(recipe), false)
}
