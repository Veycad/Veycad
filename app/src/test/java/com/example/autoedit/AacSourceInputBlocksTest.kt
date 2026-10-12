package com.veycad.app

import java.util.concurrent.CancellationException
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class AacSourceInputBlocksTest {
    private data class Work(val start: Long, val count: Int, val eos: Boolean)
    private fun pts(frame: Long) = frame * 1_000_000 / 48_000

    @Test fun full_final_block_is_copied_and_held_until_nonempty_eos() {
        val work=mutableListOf<Work>()
        val blocks=AacEncoderMuxer.SourceInputBlocks { samples,count,start,eos ->
            assertTrue(samples.take(count*2).all { it==7.toShort() })
            work+=Work(start,count,eos)
        }
        val samples=ShortArray(2048) { 7 }
        blocks.append(samples,1024); samples.fill(99)
        assertTrue(work.isEmpty()); assertEquals(1024,blocks.retainedFrames)
        blocks.finish()
        assertEquals(listOf(Work(0,1024,true)),work)
        assertEquals(1024L,blocks.queuedFrames); assertEquals(0,blocks.retainedFrames)
        assertThrows(IllegalStateException::class.java) { blocks.finish() }
    }

    @Test fun fragmented_full_and_partial_endpoints_queue_each_authored_sample_once() {
        for (length in listOf(1,1023,1024,1025,48000,48128,49600,48800,51200)) {
            val work=mutableListOf<Work>()
            val blocks=AacEncoderMuxer.SourceInputBlocks { samples,count,start,eos ->
                repeat(count*2) { assertEquals("length=$length sample=${start*2+it}",((start*2+it)%30000).toShort(),samples[it]) }
                work+=Work(start,count,eos)
            }
            var accepted=0
            var writes=0
            while (accepted<length) {
                val count=minOf(listOf(17,1024,300,1)[writes++%4],length-accepted)
                blocks.append(ShortArray(count*2) { ((accepted*2+it)%30000).toShort() },count)
                accepted+=count
                assertEquals(accepted.toLong(),blocks.acceptedFrames)
                assertTrue(blocks.retainedFrames in 1..1024)
                assertEquals(blocks.acceptedFrames,blocks.queuedFrames+blocks.retainedFrames)
            }
            blocks.finish()
            assertEquals(length.toLong(),blocks.queuedFrames)
            assertEquals(length,work.sumOf { it.count })
            assertEquals(1,work.count { it.eos }); assertTrue(work.last().eos)
            assertTrue(work.dropLast(1).all { it.count==1024 && !it.eos })
            assertEquals(((length-1)/1024)*1024L,work.last().start)
            assertEquals((length-1)%1024+1,work.last().count)
        }
    }

    @Test fun api36_framework_splits_nonempty_eos_and_clamps_hidden_empty_work_to_pcm_end() {
        // Characterization of TWO primary sources, not an executed codec: CCodecBufferChannel
        // removes EOS from the data work and queues an empty work at the SAME Java timestamp.
        // C2SoftAacEnc clamps that work to the preceding PCM end before advancing its flush PTS.
        val work=mutableListOf<Work>()
        val blocks=AacEncoderMuxer.SourceInputBlocks { _,count,start,eos -> work+=Work(start,count,eos) }
        repeat(46) { blocks.append(ShortArray(2048),1024) }
        blocks.append(ShortArray(1792),896); blocks.finish()
        assertEquals(Work(47_104,896,true),work.last())
        val dataPts=pts(work.last().start)
        val hiddenEosPts=maxOf(dataPts,dataPts+pts(work.last().count.toLong()))
        assertEquals(981_333L,dataPts)
        assertEquals(999_999L,hiddenEosPts)
        val observedFlush=listOf(dataPts,hiddenEosPts+pts(1024))
        assertEquals(listOf(981_333L,1_021_332L),observedFlush) // actual e9f0db71 packets46/47
        assertTrue(abs(observedFlush[1]-1_002_666L)>2)
        // A full final data work already emitted an AU. Its next timestamp equals its end,
        // so splitting EOS does not create this partial-work discontinuity.
        val fullEnd=1_045_333L+pts(1024)
        assertEquals(1_066_666L,fullEnd)
        assertEquals(1_087_999L,fullEnd+pts(1024))
    }

    @Test fun diagnostic_timestamp_audit_retains_first_failure_and_never_accepts_it() {
        val audit=AacEncoderMuxer.SourcePacketTimestampAudit("observed-codec",deferForProbe=true)
        audit.record(45,959_999); audit.record(46,981_333)
        audit.record(47,1_021_332)
        val first=requireNotNull(audit.failure)
        audit.record(48,1_042_665)
        audit.record(49,1_045_333) // Later conforming metadata cannot erase a retained failure.
        assertSame(first,audit.failure); assertEquals(2L,audit.violations)
        assertSame(first,assertThrows(IllegalStateException::class.java) { audit.requireValid() })
        val strict=AacEncoderMuxer.SourcePacketTimestampAudit("observed-codec")
        assertThrows(IllegalStateException::class.java) { strict.record(47,1_021_332) }
        assertEquals(1L,strict.violations)
    }

    @Test fun decoded_probe_observes_sample_ordinal_and_pts_without_filling_or_hiding_a_gap() {
        val probe=AacEncoderMuxer.SourceProbeSamples()
        fun chunk(ptsUs: Long, count: Int, peak: Int)=PcmChunk(PcmFormat(48000,2),ptsUs,
            ShortArray(count*2) { if(it/2==peak) 20_000 else 0 })
        probe.append(chunk(0,10_000,1600))
        probe.append(chunk(208_333,24_000,14_000))
        probe.append(chunk(727_000,14_000,12_400)) // 896-frame PTS gap; PCM has no inserted frames
        assertEquals(48_000L,probe.frames)
        assertEquals(listOf(1600L,24_000L,46_400L),probe.sequentialMarkers.toList())
        assertEquals(listOf(1600L,24_000L,47_296L),probe.ptsMarkers.toList())
        assertEquals(1L,probe.discontinuities)
        assertEquals(896L,probe.maximumGapFrames)
    }

    @Test fun diagnostic_probe_has_hard_chunk_and_sample_limits() {
        val chunks=AacEncoderMuxer.SourceProbeSamples()
        repeat(256) { chunks.append(PcmChunk(PcmFormat(48000,2),it*1_000_000L/48000,shortArrayOf(0,0))) }
        assertThrows(IllegalStateException::class.java) { chunks.append(PcmChunk(PcmFormat(48000,2),5333,shortArrayOf(0,0))) }
        val frames=AacEncoderMuxer.SourceProbeSamples()
        repeat(5) { frames.append(PcmChunk(PcmFormat(48000,2),it*1_000_000L,ShortArray(96_000))) }
        assertThrows(IllegalStateException::class.java) { frames.append(PcmChunk(PcmFormat(48000,2),5_000_000,ShortArray(96_000))) }
    }

    @Test fun failed_queue_preserves_cancellation_identity_and_close_releases_pending_ownership() {
        val cancellation=CancellationException("input queue cancelled")
        val blocks=AacEncoderMuxer.SourceInputBlocks { _,_,_,_ -> throw cancellation }
        blocks.append(ShortArray(2048),1024)
        assertSame(cancellation,assertThrows(CancellationException::class.java) { blocks.append(ShortArray(2),1) })
        assertEquals(0L,blocks.queuedFrames)
        blocks.close(); assertEquals(0,blocks.retainedFrames)
        assertThrows(IllegalStateException::class.java) { blocks.append(ShortArray(2),1) }
        val empty=AacEncoderMuxer.SourceInputBlocks { _,_,_,_ -> fail("No empty EOS work") }
        assertThrows(IllegalStateException::class.java) { empty.finish() }
    }
}
