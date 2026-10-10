package com.veycad.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File

internal object TextVideoExporter {
    fun export(context: Context, project: TextEditProject, output: File, checkCancelled: () -> Unit, onProgress: (Int) -> Unit) {
        val source=File(project.sourcePath)
        require(source.canonicalPath != output.canonicalPath)
        val video=File(output.path+".silent.mp4")
        val durationMs=(project.durationUs+999)/1000
        val graph=MontageGraph(durationMs,durationMs,clips=listOf(MontageGraph.Clip(
            "text-pass",0,durationMs,durationMs,MontageGraph.ShotRole.ESTABLISHING,
            MontageGraph.Transition.HARD_CUT,MontageGraph.Motion.HOLD,1f,0)))
        var pcm:PcmFile?=null
        try {
            checkCancelled(); onProgress(1)
            onProgress(5)
            MediaCodecSpeedRampRenderer.render(MediaCodecSpeedRampRenderer.Request(source,graph=graph,outputFile=video,
                width=project.width/2*2,height=project.height/2*2,bitrate=(project.width.toLong()*project.height*6).coerceIn(1_000_000,24_000_000).toInt(),
                fps=30,checkCancelled=checkCancelled,textProject=project,onFrameProgress={ onProgress(10+it*75/100) }))
            onProgress(85)
            if(!TextAudioMuxer.tryMuxOriginalAudio(video,source,project.durationUs,output,checkCancelled)) {
                pcm=TextPcmDecoder.decode(source,File(context.cacheDir,"text-work"),48000,2,checkCancelled)
                onProgress(90); TextAudioMuxer.mux(video,pcm,output,checkCancelled)
            }
            checkCancelled()
            MediaExtractor().let { extractor ->
                try {
                    extractor.setDataSource(output.path)
                    val tracks=(0 until extractor.trackCount).map(extractor::getTrackFormat)
                    check(tracks.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true })
                    check(tracks.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true })
                    val actual=tracks.first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }.getLong(MediaFormat.KEY_DURATION)
                    check(kotlin.math.abs(actual-project.durationUs)<100_000) { "Длительность результата изменилась" }
                } finally { extractor.release() }
            }
            val retriever=MediaMetadataRetriever()
            try {
                retriever.setDataSource(output.path)
                for(time in listOf(0L,(project.durationUs-100_000).coerceAtLeast(0))) {
                    checkCancelled()
                    val frame=if(android.os.Build.VERSION.SDK_INT>=27)
                        retriever.getScaledFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST,160,160)
                        else retriever.getFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST)
                    checkNotNull(frame) { "Не удалось декодировать результат" }
                    frame.recycle()
                }
            } finally { retriever.release() }
            onProgress(100)
        } catch(t:Throwable) { output.delete(); throw t }
        finally { pcm?.file?.delete(); video.delete(); File(video.parentFile,video.nameWithoutExtension+".video.mp4").delete() }
    }
}
