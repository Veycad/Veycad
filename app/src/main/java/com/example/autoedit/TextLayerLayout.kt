package com.veycad.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/** The same output-coordinate layout is drawn by preview and the encoder texture. */
object TextLayerLayout {
    fun draw(canvas: Canvas, project: TextEditProject, timeUs: Long) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        val occupied = mutableMapOf<TextPosition, Float>()
        for (layer in project.activeLayers(timeUs)) {
            val style = layer.style
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = style.color
                typeface = when(style.font) {
                    TextFont.SANS -> Typeface.create("sans-serif", Typeface.NORMAL)
                    TextFont.BOLD -> Typeface.create("sans-serif", Typeface.BOLD)
                    TextFont.SERIF -> Typeface.create("serif", Typeface.NORMAL)
                    TextFont.MONO -> Typeface.create("monospace", Typeface.NORMAL)
                }
                textSize = width * style.sizeRatio
                setShadowLayer(width*.003f, 0f, width*.002f, Color.BLACK)
            }
            val available = (width*.84f).toInt()
            fun layout(maxLines: Int) = StaticLayout.Builder.obtain(layer.text,0,layer.text.length,paint,available)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false)
                .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build()
            while (layout(1000).lineCount > 2 && paint.textSize > width*.018f) paint.textSize *= .92f
            val text = layout(2)
            val pad = width*.025f
            val total = text.height + pad*2
            val offset = occupied[style.position] ?: 0f
            val top = when(style.position) {
                TextPosition.TOP -> height*.12f + offset
                TextPosition.CENTER -> height*.46f + offset - total/2
                TextPosition.BOTTOM -> height*.78f - total - offset
            }.coerceIn(height*.04f, (height*.92f-total).coerceAtLeast(height*.04f))
            occupied[style.position] = offset + total + pad
            val entrance = ((timeUs-layer.startUs)/250_000f).coerceIn(0f,1f)
            val exit = ((layer.endUs-timeUs)/200_000f).coerceIn(0f,1f)
            val alpha = if(style.animation == TextAnimation.FADE) minOf(entrance,exit) else 1f
            val slide = if(style.animation == TextAnimation.SLIDE) (1f-entrance)*height*.035f else 0f
            canvas.saveLayerAlpha(0f,0f,width,height,(255*alpha).toInt())
            canvas.translate(0f,slide)
            if(style.darkPlate) {
                val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xc9000000.toInt() }
                canvas.drawRoundRect(width*.08f-pad,top,width*.92f+pad,top+total,pad,pad,plate)
            }
            canvas.translate(width*.08f,top+pad)
            text.draw(canvas)
            canvas.restore()
        }
    }
}
