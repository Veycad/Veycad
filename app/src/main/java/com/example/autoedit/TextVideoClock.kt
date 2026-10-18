package com.veycad.app

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import kotlin.math.abs

internal object TextVideoClock {
    data class Clock(val originUs:Long,val durationUs:Long)
    fun read(source:File,checkCancelled:()->Unit={}):Clock {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(source.path)
            val track=(0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true
            }
            val format=extractor.getTrackFormat(track)
            val header=format.getLong(MediaFormat.KEY_DURATION)
            extractor.selectTrack(track)
            val origin=extractor.sampleTime.coerceAtLeast(0)
            if(origin==0L) return Clock(0,header)
            // Containers differ in whether duration includes the leading edit-list offset.
            // Compare both interpretations with the actual final GOP, using constant memory.
            extractor.seekTo(origin+header,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var last=-1L; var previous=-1L
            while(extractor.sampleTime>=0) {
                checkCancelled()
                val pts=extractor.sampleTime
                if(pts>last) { previous=last; last=pts }
                else if(pts<last && pts>previous) previous=pts
                extractor.advance()
            }
            val step=if(previous>=0 && last>previous) last-previous else 33_333L
            val span=(last-origin+step).coerceAtLeast(1)
            val endBased=header-origin
            val duration=if(endBased>0 && abs(endBased-span)<abs(header-span)) endBased else header
            return Clock(origin,duration)
        } finally { extractor.release() }
    }
}
