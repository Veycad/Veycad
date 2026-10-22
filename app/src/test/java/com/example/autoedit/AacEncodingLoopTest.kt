package com.veycad.app

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AacEncodingLoopTest {
    @Test fun unavailableBuffersFailInsteadOfPollingForever() {
        var now = 0L
        var polls = 0
        assertThrows(IllegalStateException::class.java) {
            AacEncodingLoop.run({}, { false }, {
                if (++polls > 5) throw AssertionError("Encoder kept polling after its stall deadline")
                now += 5_000_000_000L
                AacEncodingLoop.Output.IDLE
            }, { now })
        }
        assertEquals(4, polls)
    }

    @Test fun missingOutputAfterInputEndsStillTimesOut() {
        var now = 0L
        var polls = 0
        var fed = false
        assertThrows(IllegalStateException::class.java) {
            AacEncodingLoop.run({}, {
                if (fed) false else { fed = true; true }
            }, {
                if (++polls > 5) throw AssertionError("Encoder never finished draining")
                now += 5_000_000_000L
                AacEncodingLoop.Output.IDLE
            }, { now })
        }
        assertEquals(4, polls)
    }

    @Test fun eachNewInputExtendsTheDeadlineForALongEncode() {
        var now = 0L
        var queued = 0
        AacEncodingLoop.run({}, { queued++; true }, {
            now += 15_000_000_000L
            if (queued == 6) AacEncodingLoop.Output.END else AacEncodingLoop.Output.IDLE
        }, { now })
        assertEquals(6, queued)
        assertEquals(90_000_000_000L, now)
    }

    @Test fun outputProgressExtendsTheDeadlineAfterInputEnds() {
        var now = 0L
        var drained = 0
        AacEncodingLoop.run({}, { false }, {
            now += 15_000_000_000L
            if (++drained == 6) AacEncodingLoop.Output.END else AacEncodingLoop.Output.PROGRESS
        }, { now })
        assertEquals(6, drained)
        assertEquals(90_000_000_000L, now)
    }

    @Test fun cancellationKeepsItsIdentityEvenAtTheStallDeadline() {
        val cancelled = CancellationException("user cancelled")
        var now = 0L
        var checks = 0
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            AacEncodingLoop.run({ if (++checks == 2) throw cancelled }, { false }, {
                now = 20_000_000_000L
                AacEncodingLoop.Output.IDLE
            }, { now })
        })
        assertEquals(2, checks)
    }
}
