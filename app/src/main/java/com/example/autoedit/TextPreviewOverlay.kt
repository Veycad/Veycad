package com.veycad.app

import android.content.Context
import android.graphics.Canvas
import android.view.View

internal class TextPreviewOverlay(context:Context):View(context) {
    var project:TextEditProject?=null
    var timeUs:Long=0
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val p=project ?: return
        val scale=minOf(width.toFloat()/p.width,height.toFloat()/p.height)
        canvas.save()
        canvas.translate((width-p.width*scale)/2,(height-p.height*scale)/2)
        canvas.scale(scale,scale)
        // Canvas.width is the view size even after scaling: clip into an output-sized bitmap-free canvas.
        val picture=android.graphics.Picture()
        val recording=picture.beginRecording(p.width,p.height)
        TextLayerLayout.draw(recording,p,timeUs)
        picture.endRecording(); canvas.drawPicture(picture)
        canvas.restore()
    }
}
