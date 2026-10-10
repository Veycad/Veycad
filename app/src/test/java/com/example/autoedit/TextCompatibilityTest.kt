package com.veycad.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.Properties

class TextCompatibilityTest {
    @get:Rule val folder=TemporaryFolder()
    @Test fun titleCountsCodePointsRatherThanUtf16OrGraphemes() {
        val project=TextEditProject("/v",3_000_000,720,1280)
        val text="Я".repeat(60)+"😀".repeat(60)+"е\u0301".repeat(30)
        assertEquals(240,text.length)
        assertEquals(180,TextLimits.codePoints(text))
        assertEquals(text,project.hook(text).text)
        assertEquals(TextLayerKind.TITLE,project.hook(text).kind)
        assertEquals(3,project.hook(text).maxLines)
        assertThrows(IllegalArgumentException::class.java) { project.hook(text+"я") }
    }
    @Test fun legacyAndSubtitleRemainTwoLinesAndLegacyLongTextIsRetained() {
        val legacy=TextLayer("l","Я".repeat(2000),0,1_000_000)
        assertEquals(TextLayerKind.LEGACY,legacy.kind)
        assertEquals(2,legacy.maxLines)
        val p=TextEditProject("/v",1_000_000,720,1280,captions=listOf(CaptionCue("c","Речь",0,1_000_000)))
        assertEquals(TextLayerKind.SUBTITLE,p.activeLayers(10).single().kind)
        assertEquals(2,p.activeLayers(10).single().maxLines)
    }
    @Test fun unknownAndLowConfidenceRequireReviewUntilExplicitManualCheck() {
        val unknown=CaptionCue("c","Речь",0,1000)
        assertTrue(unknown.needsReview)
        assertTrue(unknown.copy(confidence=.749).needsReview)
        assertTrue(unknown.copy(confidence=.99).needsReview)
        assertFalse(unknown.copy(confidence=.75,confidenceCalibrated=true).needsReview)
        assertFalse(unknown.copy(manuallyReviewed=true).needsReview)
        assertTrue(SpeechEvidence().needsReview)
        assertTrue(SpeechEvidence(confidence=.99).needsReview) // not calibrated
        assertThrows(IllegalArgumentException::class.java) { unknown.copy(confidence=Double.NaN) }
    }
    @Test fun richStyleAndReviewSurviveStoreAndOldV1HasCompatibleDefaults() {
        val source=folder.newFile("v.mp4").apply { writeText("fixture") }
        val dir=folder.newFolder("drafts")
        val style=TextStyle(font=TextFont.MONO,animation=TextAnimation.SCALE,plate=TextPlate.ACCENT)
        val p=TextEditProject(source.path,3_000_000,720,1280,
            layers=listOf(TextLayer("t","😀 Привет",0,3_000_000,style,TextLayerKind.TITLE)),
            captions=listOf(CaptionCue("c","Речь",0,1000,confidence=.6,manuallyReviewed=true)))
        val store=TextEditStore(dir); store.save(p)
        assertEquals(p,TextEditStore(dir).load(source.path))
        val old=p.copy(layers=listOf(TextLayer("old","Я".repeat(500),0,1000,TextStyle(font=TextFont.BOLD,animation=TextAnimation.SLIDE,darkPlate=false))),
            captions=listOf(CaptionCue("old-c","Речь",0,1000)))
        store.save(old)
        val file=dir.listFiles()!!.single()
        val props=Properties().apply { file.inputStream().use { load(it) } }
        props.keys.toList().filter { it.toString().endsWith(".kind") || it.toString().endsWith(".background") ||
            it.toString().endsWith(".confidence") || it.toString().endsWith(".calibrated") ||
            it.toString().endsWith(".reviewed") }.forEach { props.remove(it) }
        file.outputStream().use { props.store(it,"old v1") }
        assertEquals(old,store.load(source.path))
        assertEquals(TextPlate.NONE,store.load(source.path)!!.layers.single().style.resolvedPlate)
    }
}
