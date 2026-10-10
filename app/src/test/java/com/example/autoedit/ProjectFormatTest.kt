package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class ProjectFormatTest {
    @Test fun recipeDefaultsPreserveExplicitAuthorAspect() {
        assertEquals(ProjectAspect.SQUARE_1_1, ProjectFormats.defaultFor(MontageStyleCatalog.Recipe.FEAR_STROBE))
        val explicit = ProjectFormatSelection(ProjectAspect.LANDSCAPE_16_9, true)
        assertEquals(explicit, ProjectFormats.forRecipe(MontageStyleCatalog.Recipe.FEAR_STROBE, explicit))
        assertEquals(ProjectFormatSelection(ProjectAspect.SQUARE_1_1, false), ProjectFormats.forRecipe(
            MontageStyleCatalog.Recipe.FEAR_STROBE, ProjectFormatSelection(ProjectAspect.PORTRAIT_9_16, false)))
    }
    @Test fun allExportDimensions() {
        val expected = listOf(
            listOf(OutputSize(720,1280), OutputSize(1080,1920), OutputSize(2160,3840)),
            listOf(OutputSize(1280,720), OutputSize(1920,1080), OutputSize(3840,2160)),
            listOf(OutputSize(720,720), OutputSize(1080,1080), OutputSize(2160,2160)),
            listOf(OutputSize(720,900), OutputSize(1080,1350), OutputSize(2160,2700))
        )
        ProjectAspect.entries.forEachIndexed { i, aspect ->
            ExportQuality.entries.forEachIndexed { j, quality ->
                assertEquals(expected[i][j], aspect.size(quality.shortEdge))
            }
        }
    }
    @Test fun invalidDimensionsRejected() {
        listOf(0, -1, 361, Int.MAX_VALUE).forEach { edge ->
            assertThrows(IllegalArgumentException::class.java) { ProjectAspect.PORTRAIT_9_16.size(edge) }
        }
    }
    @Test fun previewDimensions() {
        assertEquals(OutputSize(360, 640), ProjectAspect.PORTRAIT_9_16.size(360))
        assertEquals(OutputSize(960, 540), ProjectAspect.LANDSCAPE_16_9.size(540))
        assertEquals(OutputSize(360, 450), ProjectAspect.FEED_4_5.size(360))
        assertThrows(IllegalArgumentException::class.java) { ProjectAspect.FEED_4_5.size(541) }
    }
}
