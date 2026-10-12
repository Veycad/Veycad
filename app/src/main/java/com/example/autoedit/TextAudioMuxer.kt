package com.veycad.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** AAC and MP4 are streamed; no encoded sample list or whole PCM recording lives in RAM. */
internal object TextAudioMuxer {
    /** Keep complete, aligned AAC-LC packets. Delayed/short tracks use padded PCM instead. */
    fun tryMuxOriginalAudio(video: File, source: File, durationUs: Long, output: File, checkCancelled: () -> Unit): Boolean {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(source.path)
            val audio=(0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true
            } ?: return false
            val format=extractor.getTrackFormat(audio)
            if(format.getString(MediaFormat.KEY_MIME)!="audio/mp4a-latm") return false
            if(format.containsKey(MediaFormat.KEY_AAC_PROFILE) &&
                format.getInteger(MediaFormat.KEY_AAC_PROFILE)!=MediaCodecInfo.CodecProfileLevel.AACObjectLC) return false
            val videoTrack=(0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true
            }
            extractor.selectTrack(videoTrack)
            val origin=extractor.sampleTime.coerceAtLeast(0)
            extractor.unselectTrack(videoTrack); extractor.selectTrack(audio)
            // An early packet may contain speech before the first video frame. Decode it to trim exactly.
            val first=extractor.sampleTime
            if(first<origin || first-origin>30_000) return false
            val packetUs=1024L*1_000_000/format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            extractor.seekTo(origin+(durationUs-2*packetUs).coerceAtLeast(0),MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var last=-1L
            while(extractor.sampleTime>=0) { checkCancelled(); last=extractor.sampleTime; extractor.advance() }
            if(last+packetUs-origin<durationUs-30_000) return false
            muxTracks(video,source,audio,origin,durationUs,output,checkCancelled)
            return true
        } finally { extractor.release() }
    }
    fun mux(video: File, pcm: PcmFile, output: File, checkCancelled: () -> Unit) {
        val audio = File(output.path+".audio.m4a")
        try {
            encode(pcm,audio,checkCancelled)
            muxTracks(video,audio,0,0,pcm.frames*1_000_000/pcm.sampleRate,output,checkCancelled)
        } catch(t:Throwable) { output.delete(); throw t }
        finally { audio.delete() }
    }
    private fun muxTracks(video:File, audio:File, audioTrack:Int, audioOriginUs:Long,
        durationUs:Long, output:File, checkCancelled:()->Unit) {
        try {
            val muxer=MediaMuxer(output.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val ve=MediaExtractor(); val ae=MediaExtractor()
            var started=false
            try {
                ve.setDataSource(video.path); ae.setDataSource(audio.path)
                val vt=(0 until ve.trackCount).first { ve.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }
                val outV=muxer.addTrack(ve.getTrackFormat(vt)); val outA=muxer.addTrack(ae.getTrackFormat(audioTrack))
                ve.selectTrack(vt); ae.selectTrack(audioTrack); muxer.start(); started=true
                fun copy(extractor: MediaExtractor, track: Int, origin:Long) {
                    var buffer=ByteBuffer.allocateDirect(4*1024*1024)
                    val info=MediaCodec.BufferInfo()
                    while(true) {
                        checkCancelled()
                        val time=extractor.sampleTime-origin
                        if(extractor.sampleTime<0 || time>=durationUs) break
                        if(time<0) { extractor.advance(); continue }
                        if(android.os.Build.VERSION.SDK_INT>=28 && extractor.sampleSize>buffer.capacity()) buffer=ByteBuffer.allocateDirect(extractor.sampleSize.toInt())
                        buffer.clear(); val n=extractor.readSampleData(buffer,0)
                        if(n<0) break
                        val flags=if(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        info.set(0,n,time,flags)
                        muxer.writeSampleData(track,buffer,info); extractor.advance()
                    }
                }
                copy(ve,outV,0); copy(ae,outA,audioOriginUs)
                muxer.stop(); started=false
            } finally { ve.release(); ae.release(); if(started) runCatching { muxer.stop() }; muxer.release() }
        } catch(t:Throwable) { output.delete(); throw t }
    }
    private fun encode(pcm: PcmFile, target: File, checkCancelled: () -> Unit) {
        val codec=MediaCodec.createEncoderByType("audio/mp4a-latm")
        val muxer=MediaMuxer(target.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started=false
        try {
            codec.configure(MediaFormat.createAudioFormat("audio/mp4a-latm",pcm.sampleRate,pcm.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE,192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384)
            },null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            pcm.file.inputStream().buffered().use { input ->
                val bytes=ByteArray(16384); var frames=0L; var ended=false; var track=-1
                val info=MediaCodec.BufferInfo(); var lastActivity=System.nanoTime()
                while(true) {
                    checkCancelled(); check(System.nanoTime()-lastActivity<30_000_000_000L) { "Аудиокодер не отвечает" }
                    if(!ended) {
                        val index=codec.dequeueInputBuffer(10_000)
                        if(index>=0) {
                            val buffer=requireNotNull(codec.getInputBuffer(index)); buffer.clear()
                            val count=minOf(bytes.size,buffer.capacity())/(pcm.channels*2)*(pcm.channels*2)
                            val n=input.read(bytes,0,count)
                            val pts=frames*1_000_000/pcm.sampleRate
                            if(n<0) { codec.queueInputBuffer(index,0,0,pts,MediaCodec.BUFFER_FLAG_END_OF_STREAM); ended=true }
                            else { buffer.put(bytes,0,n); codec.queueInputBuffer(index,0,n,pts,0); frames+=n/(pcm.channels*2) }
                            lastActivity=System.nanoTime()
                        }
                    }
                    when(val index=codec.dequeueOutputBuffer(info,10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track=muxer.addTrack(codec.outputFormat); muxer.start(); started=true }
                        else -> if(index>=0) {
                            lastActivity=System.nanoTime()
                            if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0 && info.presentationTimeUs>=0) {
                                check(started)
                                muxer.writeSampleData(track,requireNotNull(codec.getOutputBuffer(index)),info)
                            }
                            val eos=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                            codec.releaseOutputBuffer(index,false)
                            if(eos) break
                        }
                    }
                }
            }
            muxer.stop(); started=false
        } finally { runCatching { codec.stop() }; codec.release(); if(started) runCatching { muxer.stop() }; muxer.release() }
    }
}
