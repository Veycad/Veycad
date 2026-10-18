package com.veycad.app

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AacSourceInputBlocksTest {
    private data class Work(val start: Long, val count: Int, val eos: Boolean)
    private fun pts(frame: Long) = frame * 1_000_000 / 48_000

    @Test fun full_final_block_is_copied_then_followed_by_empty_eos_at_its_end() {
        val work=mutableListOf<Work>()
        val blocks=AacEncoderMuxer.SourceInputBlocks { samples,count,start,eos ->
            assertTrue(samples.take(count*2).all { it==7.toShort() })
            work+=Work(start,count,eos)
        }
        val samples=ShortArray(2048) { 7 }
        blocks.append(samples,1024); samples.fill(99)
        assertTrue(work.isEmpty()); assertEquals(1024,blocks.retainedFrames)
        blocks.finish()
        assertEquals(listOf(Work(0,1024,false),Work(1024,0,true)),work)
        assertEquals(1024L,blocks.acceptedFrames)
        assertEquals(1024L,blocks.queuedFrames); assertEquals(0,blocks.retainedFrames)
        assertThrows(IllegalStateException::class.java) { blocks.finish() }
    }

    @Test fun fragmented_partial_tail_preserves_authored_pcm_and_zeroes_only_final_padding() {
        for (length in listOf(1,1023,1024,1025,48000,48128,49600,48800,51200)) {
            val work=mutableListOf<Work>()
            val blocks=AacEncoderMuxer.SourceInputBlocks { samples,count,start,eos ->
                if (eos) assertEquals(0,count) else assertEquals(1024,count)
                repeat(count*2) {
                    val ordinal=start*2+it
                    val expected=if(ordinal<length*2) (ordinal%30000).toShort() else 0.toShort()
                    assertEquals("length=$length sample=$ordinal",expected,samples[it])
                }
                work+=Work(start,count,eos)
            }
            var accepted=0
            var writes=0
            while (accepted<length) {
                val count=minOf(listOf(17,1024,300,1)[writes++%4],length-accepted)
                val borrowed=ShortArray(count*2) { ((accepted*2+it)%30000).toShort() }
                blocks.append(borrowed,count); borrowed.fill(-1)
                accepted+=count
                assertEquals(accepted.toLong(),blocks.acceptedFrames)
                assertTrue(blocks.retainedFrames in 1..1024)
                assertEquals(blocks.acceptedFrames,blocks.queuedFrames+blocks.retainedFrames)
            }
            blocks.finish()
            val padded=((length+1023)/1024)*1024L
            assertEquals(length.toLong(),blocks.acceptedFrames)
            assertEquals(padded,blocks.queuedFrames)
            assertEquals(padded,work.sumOf { it.count }.toLong())
            assertEquals(1,work.count { it.eos })
            assertTrue(work.dropLast(1).all { it.count==1024 && !it.eos })
            assertEquals(Work(padded,0,true),work.last())
            assertEquals((0 until padded/1024).map { it*1024 },work.dropLast(1).map { it.start })
        }
    }

    @Test fun partial_48000_frame_input_queues_eos_at_padded_endpoint_without_nonempty_eos() {
        val work=mutableListOf<Work>()
        val blocks=AacEncoderMuxer.SourceInputBlocks { _,count,start,eos -> work+=Work(start,count,eos) }
        repeat(46) { blocks.append(ShortArray(2048),1024) }
        blocks.append(ShortArray(1792),896); blocks.finish()
        assertEquals(Work(47_104,1024,false),work[46])
        assertEquals(Work(48_128,0,true),work[47])
        assertEquals(981_333L,pts(work[46].start))
        assertEquals(1_002_666L,pts(work.last().start))
        assertEquals(48_000L,blocks.acceptedFrames)
        assertEquals(48_128L,blocks.queuedFrames)
    }

    @Test fun failed_empty_eos_keeps_authored_and_padded_counts_and_cannot_be_retried() {
        val cancellation=CancellationException("empty EOS cancelled")
        val work=mutableListOf<Work>()
        val blocks=AacEncoderMuxer.SourceInputBlocks { _,count,start,eos ->
            if(eos) throw cancellation
            work+=Work(start,count,eos)
        }
        blocks.append(shortArrayOf(7,7),1)
        assertSame(cancellation,assertThrows(CancellationException::class.java) { blocks.finish() })
        assertEquals(listOf(Work(0,1024,false)),work)
        assertEquals(1L,blocks.acceptedFrames)
        assertEquals(1024L,blocks.queuedFrames)
        assertEquals(0,blocks.retainedFrames)
        assertThrows(IllegalStateException::class.java) { blocks.finish() }
        assertThrows(IllegalStateException::class.java) { blocks.append(shortArrayOf(7,7),1) }
        blocks.close()
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

    @Test fun diagnostic_decode_budget_expires_during_callbacks_without_a_returned_chunk() {
        var now=9_000_000_000L
        val budget=AacEncoderMuxer.SourceProbeDecodeBudget({},nanoTime={ now })
        val decoderCallback: () -> Unit=budget
        var completedIterations=0
        // Model only calls at the existing cancellation seam during prime/read progress.
        // No codec or PCM output is simulated, and progress must not renew this budget.
        decoderCallback()
        assertThrows(IllegalStateException::class.java) {
            repeat(61) {
                now+=1_000_000_000L
                decoderCallback()
                completedIterations++
            }
        }
        assertEquals(59,completedIterations)
        assertEquals(69_000_000_000L,now)
        now=70_000_000_000L
        assertThrows(IllegalStateException::class.java) { decoderCallback() }
    }

    @Test fun diagnostic_decode_budget_checks_after_read_before_accepting_eof() {
        var now=3_000_000_000L
        val budget=AacEncoderMuxer.SourceProbeDecodeBudget({},nanoTime={ now })
        val chunk=PcmChunk(PcmFormat(48000,2),0,shortArrayOf(1,1))
        var reads=0
        val stream=object : PcmStream {
            override val format=chunk.format
            override fun read(): PcmChunk? {
                reads++
                now=if (reads==1) 62_999_999_999L else 63_000_000_000L
                return if (reads==1) chunk else null
            }
            override fun close() {}
        }
        stream.use {
            assertSame(chunk,budget.read(it)) // One nanosecond before the absolute deadline.
            assertThrows(IllegalStateException::class.java) { budget.read(it) }
            assertEquals(2,reads) // The second read returned EOF, which must still be rejected.
            assertThrows(IllegalStateException::class.java) { budget.read(it) }
            assertEquals(2,reads) // Expiry also stops entry to any later read.
        }
    }

    @Test fun diagnostic_decode_budget_preserves_cancellation_before_consulting_deadline() {
        val cause=IllegalStateException("original cancellation cause")
        val cancellation=CancellationException("diagnostic cancelled").apply { initCause(cause) }
        var now=11_000_000_000L
        var clockReads=0
        val budget=AacEncoderMuxer.SourceProbeDecodeBudget({ throw cancellation },nanoTime={
            clockReads++
            now
        })
        for (time in listOf(12_000_000_000L,71_000_000_000L)) {
            now=time // Cancellation wins both before expiry and at the deadline.
            val readsBeforeCancellation=clockReads
            val thrown=assertThrows(CancellationException::class.java) { budget() }
            assertSame(cancellation,thrown)
            assertSame(cause,thrown.cause)
            assertEquals(readsBeforeCancellation,clockReads)
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
