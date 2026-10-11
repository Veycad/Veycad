package com.veycad.app

import java.util.UUID

internal object WhisperResultParser {
    fun cues(rows:Array<String>,offsetFrames:Long,totalFrames:Long,windowFrames:Int):List<CaptionCue> {
        val offsetUs=offsetFrames*1_000_000/16000
        val windowUs=windowFrames*1_000_000L/16000
        val durationUs=totalFrames*1_000_000/16000
        return rows.map { row ->
            val fields=row.split('\t',limit=3)
            val start=fields.getOrNull(0)?.toLongOrNull()
            val end=fields.getOrNull(1)?.toLongOrNull()
            val text=fields.getOrNull(2)?.trim()
            if(start==null || end==null || start<0 || end<=start || start>=windowUs || text.isNullOrBlank() || text.length>2000)
                throw SpeechFailureException(SpeechFailureCode.RESULT_INVALID)
            val clipped=minOf(durationUs,offsetUs+minOf(end,windowUs))
            if(offsetUs+start>=clipped) throw SpeechFailureException(SpeechFailureCode.RESULT_INVALID)
            CaptionCue(UUID.randomUUID().toString(),text,offsetUs+start,clipped)
        }
    }
}
