package com.veycad.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextLayoutDeviceTest {
    @Test fun denseWarningIsReadableOnWhiteAndBlackVideo() {
        val p=TextEditProject("/fixture",1_000_000,720,1280,
            layers=List(100){TextLayer("$it","Длинная строка\nЕщё строка",0,1_000_000)})
        for(background in listOf(android.graphics.Color.WHITE,android.graphics.Color.BLACK)) {
            val bitmap=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(background)
                TextLayerLayout.draw(Canvas(bitmap),p,100_000)
                val pixels=IntArray(720*1280); bitmap.getPixels(pixels,0,720,0,0,720,1280)
                assertTrue("Warning must contrast with background $background",pixels.count { it!=background }>1000)
                // Sample the first text line inside the plate, excluding the video background.
                val ink=(146 until 182).sumOf { y -> (115 until 605).count { x ->
                    val color=bitmap.getPixel(x,y)
                    android.graphics.Color.red(color)>200 && android.graphics.Color.green(color)>200 &&
                        android.graphics.Color.blue(color)>200
                } }
                assertTrue("Warning text must remain visible inside the dark plate",ink>100)
            } finally { bitmap.recycle() }
        }
    }
    @Test fun realCanvasTitleAndSubtitleLineLimitsAndAnimatedSafeArea() {
        for((w,h) in listOf(720 to 1280,1080 to 1920,1080 to 1080)) {
            val style=TextStyle(font=TextFont.MONO,animation=TextAnimation.SCALE,plate=TextPlate.ACCENT)
            val p=TextEditProject("/fixture",1_000_000,w,h,
                layers=listOf(TextLayer("title","Кириллица 😀\nВторая строка\nТретья строка",0,1_000_000,style,TextLayerKind.TITLE),
                    TextLayer("plate","Описание",0,1_000_000,TextStyle(position=TextPosition.CENTER,animation=TextAnimation.SLIDE),TextLayerKind.PLATE)),
                captions=listOf(CaptionCue("caption","Первая строка\nВторая строка\nТретья строка",0,1_000_000)))
            for(t in listOf(0L,50_000L,200_000L,999_999L)) {
                val boxes=TextLayerLayout.measure(w,h,p,t)!!
                assertEquals(3,boxes.first { it.layer.id=="title" }.text.lineCount)
                assertEquals(2,boxes.first { it.layer.id=="caption" }.text.lineCount)
                boxes.zipWithNext().forEach { (a,b)->assertTrue(a.bounds.bottom<b.bounds.top) }
                val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                try {
                    TextLayerLayout.draw(Canvas(bitmap),p,t,strict=true)
                    val row=IntArray(w)
                    for(y in 0 until h) {
                        bitmap.getPixels(row,0,w,0,y,w,1)
                        for(x in 0 until w) if(android.graphics.Color.alpha(row[x])!=0) {
                            assertTrue("ink x=$x/$w",x>=kotlin.math.floor(w*.08).toInt() && x<kotlin.math.ceil(w*.92).toInt())
                            assertTrue("ink y=$y/$h",y>=kotlin.math.floor(h*.10).toInt() && y<kotlin.math.ceil(h*.85).toInt())
                        }
                    }
                    if(t==200_000L) {
                        val box=boxes.first { it.layer.id=="title" }
                        assertEquals(TextLayerLayout.ACCENT_COLOR,bitmap.getPixel(w/2,(box.bounds.top+box.padding/2).toInt()))
                    }
                } finally { bitmap.recycle() }
            }
        }
    }
    @Test fun overlyDenseTextIsVisibleAsErrorAndStrictExportDrawingFails() {
        val p=TextEditProject("/fixture",1_000_000,720,1280,
            layers=List(100){TextLayer("$it","Длинная строка\nЕщё строка",0,1_000_000)})
        assertNull(TextLayerLayout.measure(720,1280,p,100_000))
        val bitmap=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888)
        try {
            TextLayerLayout.draw(Canvas(bitmap),p,100_000)
            assertThrows(IllegalArgumentException::class.java) { TextLayerLayout.draw(Canvas(bitmap),p,100_000,strict=true) }
        } finally { bitmap.recycle() }
    }
}
