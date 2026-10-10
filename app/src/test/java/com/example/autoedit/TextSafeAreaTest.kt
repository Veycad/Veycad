package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class TextSafeAreaTest {
    @Test fun mixedPositionsStayInsideSafeAreaAndNeverOverlap() {
        for((w,h) in listOf(720 to 1280,1080 to 1920,1080 to 1080)) {
            val boxes=listOf(TextBoxRequest("caption",TextPosition.BOTTOM,h*.15f),
                TextBoxRequest("title",TextPosition.TOP,h*.25f),
                TextBoxRequest("plate",TextPosition.CENTER,h*.20f),
                TextBoxRequest("second",TextPosition.TOP,h*.1f))
            val placed=TextSafeArea.place(w.toFloat(),h.toFloat(),boxes)!!
            assertEquals(4,placed.size)
            placed.forEach {
                assertEquals(w*.08f,it.left,.01f); assertEquals(w*.92f,it.right,.01f)
                assertTrue(it.top>=h*.10f-.01f); assertTrue(it.bottom<=h*.85f+.01f)
            }
            placed.zipWithNext().forEach { (a,b)->assertTrue(a.bottom<b.top) }
            assertEquals("caption",placed.last().id)
        }
    }
    @Test fun tooDenseLayoutIsRejectedRatherThanOverlappedOrTiny() {
        assertNull(TextSafeArea.place(720f,1280f,List(10){TextBoxRequest("$it",TextPosition.TOP,200f)}))
    }
    @Test fun shortAndLongAnimationsStayWithinIntervalsAnd200ms() {
        for(duration in listOf(10_000L,100_000L,1_000_000L)) {
            val layer=TextLayer("a","Привет",1_000_000,1_000_000+duration)
            assertTrue(TextMotion.windowUs(layer)<=200_000)
            assertTrue(TextMotion.windowUs(layer)*2<=duration)
            assertEquals(0f,TextMotion.entrance(layer,layer.startUs),0f)
            assertEquals(1f,TextMotion.entrance(layer,layer.startUs+duration/2),0f)
            assertEquals(0f,TextMotion.exit(layer,layer.endUs),0f)
            assertTrue(TextMotion.scale(layer,layer.startUs)>=.94f)
            assertEquals(1f,TextMotion.scale(layer,layer.startUs+duration/2),0f)
        }
    }
}
