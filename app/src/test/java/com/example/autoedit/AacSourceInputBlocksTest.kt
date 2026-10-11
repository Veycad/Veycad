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
        for (length in listOf(1,1023,1024,1025,48000,48128,49600,48800)) {
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

    @Test fun api36_flush_recurrence_reproduces_empty_eos_gap_and_nonempty_eos_keeps_au_spacing() {
        // Independent transcription of C2SoftAacEnc API36: emit old nextPTS, then set nextPTS
        // to current work inputPTS + cumulative emitted AUs in that work. This is not a codec.
        fun flush(previousNext: Long, workStart: Long, count: Int): List<Long> =
            listOf(pts(previousNext)) + (1 until count).map { pts(workStart)+it*1024L*1_000_000/48_000 }
        val old=flush(47104,48000,3)
        assertEquals(1_021_333L,old[1]) // exact preserved packet47 native RED
        assertEquals(1_002_666L,pts(48_128))
        assertTrue(abs(old[1]-pts(48_128))>2)
        for (length in listOf(1,1023,1024,1025,48000,48128,49600,48800)) {
            val work=mutableListOf<Work>()
            val blocks=AacEncoderMuxer.SourceInputBlocks { _,count,start,eos -> work+=Work(start,count,eos) }
            var frames=0
            while (frames<length) { val count=minOf(1024,length-frames); blocks.append(ShortArray(count*2),count); frames+=count }
            blocks.finish()
            val last=work.last()
            for (tailPackets in 1..4) flush(last.start,last.start,tailPackets).forEachIndexed { i,time ->
                assertTrue("length=$length packet=$i",abs(time-pts(last.start+i*1024))<=2)
            }
        }
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
