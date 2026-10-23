package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class HybridRenderTextTest {
    @Test fun legacyPositionsAndFrameFadesSurviveTheDetachedProjection() {
        for (fps in listOf(30, 60)) {
            val item = TextItem("legacy", "Сохранённый текст", FrameSpan(2, 12), .23f, .68f, .41f,
                .08f, 0xffcc99aa.toInt(), TextItem.Appearance.ACCENT, 2)
            val mutable = arrayListOf(item)
            val project = TextEditProject("owned.mp4", 1_000_000, 720, 1280,
                legacyItems = montageSnapshot(mutable), legacyFps = fps)
            mutable.clear()
            assertEquals(listOf(item), project.legacyItems)
            val clock = ProjectClock(fps)
            assertTrue(project.activeLegacy(clock.timeUs(1)).isEmpty())
            assertEquals(listOf(item), project.activeLegacy(clock.timeUs(2)))
            assertTrue(project.activeLegacy(clock.timeUs(12)).isEmpty())
            assertEquals(0f, LegacyTextLayout.opacity(item, clock.timeUs(2), fps), 0f)
            assertEquals(.5f, LegacyTextLayout.opacity(item, clock.timeUs(3), fps), .0001f)
            assertEquals(1f, LegacyTextLayout.opacity(item, clock.timeUs(4), fps), 0f)
            assertEquals(.5f, LegacyTextLayout.opacity(item, clock.timeUs(11), fps), .0001f)
        }
    }

    @Test fun legacyLayerBeyondTheOutputEndpointIsRejectedWithoutClamping() {
        val item = TextItem("tail", "tail", FrameSpan(0, 61), .5f, .5f, .5f, .1f,
            -1, TextItem.Appearance.PLAIN, 0)
        assertThrows(IllegalArgumentException::class.java) {
            TextEditProject("owned.mp4", 1_000_000, 720, 1280, legacyItems = listOf(item), legacyFps = 60)
        }
    }
}
