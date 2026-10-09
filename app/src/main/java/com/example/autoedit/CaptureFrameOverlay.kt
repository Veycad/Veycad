package com.example.autoedit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Shows the final center crop inside a FIT_CENTER preview without stretching the camera. */
class CaptureFrameOverlay(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var outputAspect = 1f
        set(value) { field = value; invalidate() }
    var sourceAspect = 9f / 16f
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val viewAspect = width.toFloat() / height
        val imageWidth = if (viewAspect > sourceAspect) height * sourceAspect else width.toFloat()
        val imageHeight = imageWidth / sourceAspect
        val cropWidth = minOf(imageWidth, imageHeight * outputAspect)
        val cropHeight = cropWidth / outputAspect
        val left = (width - cropWidth) / 2
        val top = (height - cropHeight) / 2
        paint.style = Paint.Style.FILL
        paint.color = 0x66000000
        canvas.drawRect(0f, 0f, width.toFloat(), top, paint)
        canvas.drawRect(0f, top + cropHeight, width.toFloat(), height.toFloat(), paint)
        canvas.drawRect(0f, top, left, top + cropHeight, paint)
        canvas.drawRect(left + cropWidth, top, width.toFloat(), top + cropHeight, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density
        paint.color = Color.WHITE
        canvas.drawRect(left, top, left + cropWidth, top + cropHeight, paint)
    }
}
