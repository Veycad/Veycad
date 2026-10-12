package com.veycad.app

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

internal object TextVideoProbe {
    fun project(file: File, checkCancelled:()->Unit={}): TextEditProject {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val format=(0 until extractor.trackCount).map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }
                ?: error("В файле нет видеодорожки")
            val rotation=if(format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            val (w,h)=TextVideoGeometry.oriented(format.getInteger(MediaFormat.KEY_WIDTH),format.getInteger(MediaFormat.KEY_HEIGHT),rotation)
            val clock=TextVideoClock.read(file,checkCancelled)
            return TextEditProject(file.canonicalPath,clock.durationUs,w,h,sourceOriginUs=clock.originUs)
        } finally { extractor.release() }
    }
}
