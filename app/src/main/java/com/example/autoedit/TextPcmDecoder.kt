package com.veycad.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteOrder
import java.util.UUID

internal data class PcmFile(val file: File, val sampleRate: Int, val channels: Int, val frames: Long, val hasAudio: Boolean)

/** Bounded streaming decode. Disk PCM is signed little endian 16-bit, with the video clock at zero. */
internal object TextPcmDecoder {
    fun decode(source: File, directory: File, sampleRate: Int, channels: Int, checkCancelled: () -> Unit): PcmFile {
        require(sampleRate > 0 && channels in 1..2)
        directory.mkdirs()
        val extractor = MediaExtractor()
        val file = File(directory,"pcm-${UUID.randomUUID()}.raw")
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.path)
            val clock=TextVideoClock.read(source,checkCancelled)
            val durationUs=clock.durationUs
            val videoOrigin=clock.originUs
            val audio = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            val timeline = TextPcmTimeline(sampleRate,durationUs)
            var written = 0L
            BufferedOutputStream(file.outputStream(),64*1024).use { output ->
                val zero = ByteArray(8192)
                fun silence(count: Long) {
                    var bytes = count * channels * 2
                    while(bytes > 0) { checkCancelled(); val n=minOf(bytes,zero.size.toLong()).toInt(); output.write(zero,0,n); bytes-=n }
                    written += count
                }
                if (audio != null) {
                    val inputFormat = extractor.getTrackFormat(audio)
                    extractor.selectTrack(audio)
                    val decoder = MediaCodec.createDecoderByType(requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))).also { codec=it }
                    decoder.configure(inputFormat,null,null,0); decoder.start()
                    var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    var encoding = AudioFormat.ENCODING_PCM_16BIT
                    var inputEnded = false
                    var lastActivity = System.nanoTime()
                    val info = MediaCodec.BufferInfo()
                    while (true) {
                        checkCancelled()
                        check(System.nanoTime()-lastActivity < 30_000_000_000L) { "Аудиодекодер не отвечает" }
                        if(!inputEnded) {
                            val index=decoder.dequeueInputBuffer(10_000)
                            if(index>=0) {
                                val buffer=requireNotNull(decoder.getInputBuffer(index)); buffer.clear()
                                val size=extractor.readSampleData(buffer,0)
                                if(size<0) { decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded=true }
                                else { decoder.queueInputBuffer(index,0,size,extractor.sampleTime,0); extractor.advance() }
                                lastActivity=System.nanoTime()
                            }
                        }
                        when(val index=decoder.dequeueOutputBuffer(info,10_000)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val f=decoder.outputFormat
                                rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channelCount=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                encoding=if(f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                                require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "Неподдерживаемый PCM" }
                            }
                            else -> if(index>=0) {
                                lastActivity=System.nanoTime()
                                if(info.size>0) {
                                    val data=requireNotNull(decoder.getOutputBuffer(index)).duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                    data.position(info.offset); data.limit(info.offset+info.size)
                                    val bytesPerSample=if(encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                    val inputFrames=info.size/(bytesPerSample*channelCount)
                                    val start=timeline.frameAt(info.presentationTimeUs-videoOrigin)
                                    val end=minOf(timeline.frames,start+inputFrames.toLong()*sampleRate/rate)
                                    if(start>written) silence(minOf(start,timeline.frames)-written)
                                    val packed=ByteArray(8192); var used=0
                                    while(written<end) {
                                        val src=((written-start)*rate/sampleRate).toInt().coerceIn(0,(inputFrames-1).coerceAtLeast(0))
                                        fun sample(c:Int): Float {
                                            val at=info.offset+(src*channelCount+c)*bytesPerSample
                                            return if(bytesPerSample==4) data.getFloat(at).coerceIn(-1f,1f) else data.getShort(at)/32768f
                                        }
                                        for(c in 0 until channels) {
                                            val value = TextPcmMix.sample(channelCount,channels,c,::sample)
                                            val s=(value*32767).toInt().coerceIn(-32768,32767)
                                            packed[used++]=s.toByte(); packed[used++]=(s shr 8).toByte()
                                        }
                                        written++
                                        if(used==packed.size) { output.write(packed); used=0; checkCancelled() }
                                    }
                                    if(used>0) output.write(packed,0,used)
                                }
                                val eos=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                decoder.releaseOutputBuffer(index,false)
                                if(eos || written>=timeline.frames) break
                            }
                        }
                    }
                }
                silence(timeline.remainingAfter(written))
            }
            return PcmFile(file,sampleRate,channels,timeline.frames,audio!=null)
        } catch(t:Throwable) { file.delete(); throw t }
        finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
    }
}
