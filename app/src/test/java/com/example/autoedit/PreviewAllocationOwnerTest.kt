package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class PreviewAllocationOwnerTest {
    @Test fun reserve_precedes_allocation_and_failed_disposal_is_charged_until_retry() {
        val budget = PreviewMemoryBudget(1024)
        val owner = PreviewAllocationOwner(budget) {}
        var allocated = false
        var fail = true
        val reservation = owner.reserve(1024) {
            if (allocated) {
                check(!fail)
                allocated = false
            }
        }
        assertEquals(1024L, budget.usedBytes)
        allocated = true
        assertFalse(budget.reserve("other", 1) {})
        assertThrows(IllegalStateException::class.java) { reservation.close() }
        assertEquals(1024L, budget.usedBytes)
        assertTrue(allocated)
        assertThrows(IllegalStateException::class.java) { owner.requireUsable() }
        fail = false
        owner.close()
        assertFalse(allocated)
        assertEquals(0L, budget.usedBytes)
        owner.close()
    }

    @Test fun denied_reservation_preserves_owned_and_foreign_residents() {
        val budget = PreviewMemoryBudget(2048)
        var disposed = 0
        val a = PreviewAllocationOwner(budget) {}
        val b = PreviewAllocationOwner(budget) {}
        a.reserve(1024) { disposed++ }
        b.reserve(1024) { disposed++ }
        assertThrows(IllegalStateException::class.java) { a.reserve(1) { error("Never admitted") } }
        assertEquals(0, disposed)
        a.close()
        assertEquals(1024L, budget.usedBytes)
        assertEquals(1, disposed)
        b.requireUsable()
        b.close()
        assertEquals(2, disposed)
    }

    @Test fun reservation_restores_context_after_foreign_eviction_callback() {
        val budget = PreviewMemoryBudget(1024)
        var context = "own"
        budget.reserve("foreign", 1024) { context = "foreign" }
        val owner = PreviewAllocationOwner(budget) { context = "own" }
        owner.reserve(1024) {}
        assertEquals("own", context)
        owner.close()
    }

    @Test fun bytes_reject_invalid_or_overflow_dimensions_before_reservation() {
        assertThrows(IllegalArgumentException::class.java) { PreviewAllocationOwner.imageBytes(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { PreviewAllocationOwner.imageBytes(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) {
            PreviewAllocationOwner.imageBytes(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        // Four-byte backing even for alpha/luminance, plus conservative per-resource overhead.
        assertTrue(PreviewAllocationOwner.imageBytes(16, 16) >= 1024L + 512L)
    }

    @Test fun external_explicit_clear_invalidates_even_pinned_owner() {
        val budget = PreviewMemoryBudget()
        val owner = PreviewAllocationOwner(budget) {}
        owner.reserve(1024) {}
        budget.clear()
        assertThrows(IllegalStateException::class.java) { owner.requireUsable() }
        owner.close()
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun operation_failure_unwinds_temporary_pins_and_owner_checks_thread() {
        val budget = PreviewMemoryBudget(1024)
        val owner = PreviewAllocationOwner(budget) {}
        var disposed = 0
        val reservation = owner.reserve(1024) { disposed++ }
        repeat(3) {
            assertThrows(IllegalArgumentException::class.java) {
                owner.using { throw IllegalArgumentException("draw failed") }
            }
        }
        var failure: Throwable? = null
        Thread { try { owner.requireUsable() } catch (error: Throwable) { failure = error } }
            .apply { start(); join() }
        assertTrue(failure is IllegalStateException)
        // Removing exactly the residency pin must make it evictable: leaked operation pins block this.
        budget.unpin(reservation.key)
        assertTrue(budget.reserve("replacement", 1024) {})
        assertEquals(1, disposed)
        owner.close()
        assertEquals(1, disposed)
        budget.release("replacement")
        assertEquals(0L, budget.usedBytes)
    }
}
