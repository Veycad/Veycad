package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class TextEditProjectTest {
    @Test fun shortHookStopsAtVideoBoundary() {
        val project = TextEditProject("/video.mp4", 800_000, 720, 1280)
        val hook = project.hook("Как сделать X")
        assertEquals(800_000L, hook.endUs)
        val edited = project.copy(layers = listOf(hook))
        assertEquals("Как сделать X", edited.activeLayers(799_999).single().text)
        assertTrue(edited.activeLayers(800_000).isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsCuePastVideoEnd() {
        TextEditProject("/video.mp4", 800_000, 720, 1280,
            captions = listOf(CaptionCue("a", "Речь", 0, 900_000)))
    }
    @Test fun captionStyleAppliesWithoutChangingManualLayer() {
        val p = TextEditProject("/v", 4_000_000, 720, 1280,
            layers = listOf(TextLayer("hook", "Заголовок", 0, 3_000_000)),
            captions = listOf(CaptionCue("c", "Привет", 500_000, 1_500_000)),
            captionStyle = TextStyle(color = 0xffffcc33.toInt()))
        assertEquals(0xffffcc33.toInt(), p.activeLayers(600_000).last().style.color)
        assertEquals(0xffffffff.toInt(), p.activeLayers(600_000).first().style.color)
    }
}
