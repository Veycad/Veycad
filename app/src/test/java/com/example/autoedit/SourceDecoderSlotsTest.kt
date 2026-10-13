package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class SourceDecoderSlotsTest {
    private class Factory {
        var live = 0
        var peak = 0
        val events = mutableListOf<String>()
        fun open(index: Int): AutoCloseable {
            events += "open:$index"
            live++
            peak = maxOf(peak, live)
            return object : AutoCloseable {
                var closed = false
                override fun close() {
                    check(!closed)
                    closed = true
                    live--
                    events += "close:$index"
                }
            }
        }
    }

    @Test fun neverOpensThirdDecoder() {
        val factory = Factory()
        val slots = SourceDecoderSlots(factory::open)
        repeat(20) { index ->
            assertEquals(index, slots.acquire(index, DecoderRole.INCOMING).sourceIndex)
            slots.acquire((index + 19) % 20, DecoderRole.OUTGOING)
            assertTrue(factory.live <= 2)
        }
        assertEquals(2, factory.peak)
        slots.releaseAll()
        assertEquals(0, factory.live)
    }

    @Test fun sameRoleAndIndexReuseLiveLeaseButRolesOwnSeparateDecoders() {
        val factory = Factory()
        val slots = SourceDecoderSlots(factory::open)
        val incoming = slots.acquire(3, DecoderRole.INCOMING)
        assertSame(incoming, slots.acquire(3, DecoderRole.INCOMING))
        assertNotSame(incoming.decoder, slots.acquire(3, DecoderRole.OUTGOING).decoder)
        incoming.close()
        incoming.close()
        assertNotSame(incoming, slots.acquire(3, DecoderRole.INCOMING))
        slots.releaseAll()
        slots.releaseAll()
        assertEquals(0, factory.live)
    }

    @Test fun replacementClosesOldBeforeOpeningAndStaleLeaseCannotCloseReplacement() {
        val factory = Factory()
        val slots = SourceDecoderSlots(factory::open)
        val old = slots.acquire(0, DecoderRole.INCOMING)
        slots.acquire(19, DecoderRole.INCOMING)
        old.close()
        assertEquals(listOf("open:0", "close:0", "open:19"), factory.events)
        assertEquals(1, factory.live)
        slots.releaseAll()
    }

    @Test fun invalidIndexDoesNotEvictValidSlot() {
        val factory = Factory()
        val slots = SourceDecoderSlots(factory::open)
        val live = slots.acquire(0, DecoderRole.INCOMING)
        for (index in listOf(-1, 20)) assertThrows(IllegalArgumentException::class.java) {
            slots.acquire(index, DecoderRole.INCOMING)
        }
        assertSame(live, slots.acquire(0, DecoderRole.INCOMING))
        slots.releaseAll()
    }

    @Test fun openingFailureLeavesNoStaleSlotAndSurvivingRoleCanBeReleased() {
        val factory = Factory()
        val slots = SourceDecoderSlots { index ->
            if (index == 7) error("open failed") else factory.open(index)
        }
        slots.acquire(0, DecoderRole.INCOMING)
        slots.acquire(1, DecoderRole.OUTGOING)
        assertThrows(IllegalStateException::class.java) { slots.acquire(7, DecoderRole.INCOMING) }
        assertEquals(1, factory.live)
        slots.acquire(2, DecoderRole.INCOMING)
        slots.releaseAll()
        assertEquals(0, factory.live)
    }

    @Test fun releaseAllAttemptsBothRolesEvenWhenCloseThrows() {
        val closed = mutableListOf<Int>()
        val slots = SourceDecoderSlots { index -> AutoCloseable {
            closed += index
            if (index == 0) error("close failed")
        } }
        slots.acquire(0, DecoderRole.INCOMING)
        slots.acquire(1, DecoderRole.OUTGOING)
        assertThrows(IllegalStateException::class.java) { slots.releaseAll() }
        assertEquals(listOf(0, 1), closed)
        slots.releaseAll()
    }
}
