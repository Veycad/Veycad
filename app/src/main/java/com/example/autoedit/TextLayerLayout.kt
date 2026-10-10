package com.veycad.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

internal data class TextMeasuredBox(val layer:TextLayer,val bounds:TextPlacedBox,
    val text:StaticLayout,val padding:Float,val slideSpace:Float)

/** The same output-coordinate layout is drawn by preview and the encoder texture. */
object TextLayerLayout {
    internal const val ACCENT_COLOR=0xff5427a4.toInt()
    private const val DENSITY_MESSAGE="Слишком много текста одновременно. Разнесите слои по времени."
    internal fun measure(width:Int,height:Int,project:TextEditProject,timeUs:Long):List<TextMeasuredBox>? {
        val active=project.activeLayers(timeUs)
        if(active.isEmpty()) return emptyList()
        val w=width.toFloat(); val h=height.toFloat(); val pad=w*.025f
        val available=(w*.84f-pad*2).toInt().coerceAtLeast(1)
        var factor=1f
        while(true) {
            val layouts=active.map { layer ->
                val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color=layer.style.color
                    typeface=when(layer.style.font) {
                        TextFont.SANS -> Typeface.create("sans-serif",Typeface.NORMAL)
                        TextFont.BOLD -> Typeface.create("sans-serif",Typeface.BOLD)
                        TextFont.SERIF -> Typeface.create("serif",Typeface.NORMAL)
                        TextFont.MONO -> Typeface.create("monospace",Typeface.NORMAL)
                    }
                    textSize=maxOf(w*.018f,w*layer.style.sizeRatio*factor)
                    setShadowLayer(w*.003f,0f,w*.002f,Color.BLACK)
                }
                fun layout(maxLines:Int)=StaticLayout.Builder.obtain(layer.text,0,layer.text.length,paint,available)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false)
                    .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build()
                while(layout(1000).lineCount>layer.maxLines && paint.textSize>w*.018f)
                    paint.textSize=maxOf(w*.018f,paint.textSize*.92f)
                layout(layer.maxLines)
            }
            val requests=active.mapIndexed { index,layer ->
                val slide=if(layer.style.animation==TextAnimation.SLIDE) h*.035f else 0f
                TextBoxRequest(index.toString(),layer.style.position,layouts[index].height+pad*2+slide)
            }
            val placed=TextSafeArea.place(w,h,requests)
            if(placed!=null) return placed.map { bounds ->
                val index=bounds.id.toInt(); val layer=active[index]
                TextMeasuredBox(layer,bounds,layouts[index],pad,
                    if(layer.style.animation==TextAnimation.SLIDE) h*.035f else 0f)
            }
            if(layouts.all { it.paint.textSize<=w*.018f+.01f }) return null
            factor*=.92f
        }
    }
    fun draw(canvas:Canvas,project:TextEditProject,timeUs:Long,strict:Boolean=false) {
        val width=canvas.width.toFloat(); val height=canvas.height.toFloat()
        val boxes=measure(canvas.width,canvas.height,project,timeUs)
        if(boxes==null) {
            if(strict) throw IllegalArgumentException(DENSITY_MESSAGE)
            val pad=width*.025f
            val left=width*.08f; val top=height*.10f
            val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; textSize=width*.035f }
            val text=StaticLayout.Builder.obtain(DENSITY_MESSAGE,0,DENSITY_MESSAGE.length,paint,
                (width*.84f-pad*2).toInt().coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
            val plate=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xff171717.toInt() }
            canvas.drawRoundRect(left,top,width*.92f,top+text.height+pad*2,pad,pad,plate)
            canvas.save(); canvas.translate(left+pad,top+pad); text.draw(canvas); canvas.restore()
            return
        }
        for(box in boxes) {
            val layer=box.layer; val style=layer.style; val bounds=box.bounds
            val alpha=if(style.animation==TextAnimation.FADE) minOf(TextMotion.entrance(layer,timeUs),TextMotion.exit(layer,timeUs)) else 1f
            val slide=if(style.animation==TextAnimation.SLIDE) (1f-TextMotion.entrance(layer,timeUs))*box.slideSpace else 0f
            val plateBottom=bounds.bottom-box.slideSpace
            canvas.saveLayerAlpha(bounds.left,bounds.top,bounds.right,bounds.bottom,(255*alpha).toInt())
            canvas.translate(0f,slide)
            if(style.animation==TextAnimation.SCALE) {
                val scale=TextMotion.scale(layer,timeUs)
                canvas.scale(scale,scale,(bounds.left+bounds.right)/2,(bounds.top+plateBottom)/2)
            }
            if(style.resolvedPlate!=TextPlate.NONE) {
                val plate=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color=if(style.resolvedPlate==TextPlate.ACCENT) ACCENT_COLOR else 0xc9000000.toInt()
                }
                canvas.drawRoundRect(bounds.left,bounds.top,bounds.right,plateBottom,box.padding,box.padding,plate)
            }
            canvas.translate(bounds.left+box.padding,bounds.top+box.padding)
            box.text.draw(canvas); canvas.restore()
        }
    }
}
