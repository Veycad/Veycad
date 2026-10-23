package com.veycad.app

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PreviewMemoryBudgetTest {
    @Test fun ten_thousand_mixed_resources_never_exceed_the_shared_default_limit() {
        val budget = PreviewMemoryBudget()
        val live = mutableMapOf<String, Long>()
        val pinned = mutableListOf<String>()
        val disposed = mutableSetOf<String>()
        repeat(10_000) { index ->
            val key = "${listOf("texture", "fbo", "thumbnail", "semantic", "header")[index % 5]}:$index"
            val bytes = (1 + index % 7) * 262_144L
            val accepted = budget.reserve(key, bytes) {
                assertNotNull("Only live resources are disposed", live.remove(key))
                assertTrue("Successful disposal happens once", disposed.add(key))
            }
            assertTrue("Each bounded request fits after evicting unused resources", accepted)
            if (accepted) {
                live[key] = bytes // Allocation happens only after the reservation.
                if (index % 31 == 0) {
                    budget.pin(key)
                    pinned += key
                }
                if (index % 13 == 0 && key !in pinned) budget.release(key)
            }
            if (pinned.size > 3) budget.unpin(pinned.removeAt(0))
            pinned.forEach { assertTrue("Required resources stay live", it in live) }
            assertEquals(live.values.sum(), budget.usedBytes)
            assertTrue(budget.usedBytes <= 33_554_432L)
        }
        budget.clear()
        assertTrue(live.isEmpty())
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun recent_use_updates_lru_and_pinned_oldest_stays_live() {
        val budget = PreviewMemoryBudget(30)
        val disposed = mutableListOf<String>()
        for (key in listOf("a", "b", "c")) assertTrue(budget.reserve(key, 10) { disposed += key })
        budget.pin("a")
        budget.unpin("a")
        budget.pin("b")
        assertTrue(budget.reserve("d", 10) { disposed += "d" })
        assertEquals(listOf("c"), disposed)
        assertTrue(budget.reserve("e", 10) { disposed += "e" })
        assertEquals(listOf("c", "a"), disposed)
        assertEquals(30L, budget.usedBytes)
    }

    @Test fun nested_pins_require_matching_unpins_before_eviction() {
        val budget = PreviewMemoryBudget(10)
        var disposals = 0
        assertTrue(budget.reserve("active", 10) { disposals++ })
        budget.pin("active")
        budget.pin("active")
        budget.unpin("active")
        assertFalse(budget.reserve("other", 10) {})
        assertEquals(0, disposals)
        budget.unpin("active")
        assertTrue(budget.reserve("other", 10) {})
        assertEquals(1, disposals)
    }

    @Test fun impossible_reservation_does_not_dispose_any_existing_resource() {
        val budget = PreviewMemoryBudget(30)
        var disposals = 0
        assertTrue(budget.reserve("unused", 10) { disposals++ })
        assertTrue(budget.reserve("active", 20) { disposals++ })
        budget.pin("active")
        assertFalse(budget.reserve("too-large", 31) { fail("Rejected request owns nothing") })
        assertFalse(budget.reserve("needs-more-unused", 11) {})
        assertEquals(0, disposals)
        assertEquals(30L, budget.usedBytes)
    }

    @Test fun duplicate_key_never_replaces_or_double_accounts_the_owner() {
        val budget = PreviewMemoryBudget(10)
        var originalDisposals = 0
        var replacementDisposals = 0
        assertTrue(budget.reserve("key", 10) { originalDisposals++ })
        for (size in listOf(10L, 5L, 11L)) {
            assertFalse(budget.reserve("key", size) { replacementDisposals++ })
            assertEquals(10L, budget.usedBytes)
            assertEquals(0, originalDisposals)
        }
        budget.release("key")
        assertEquals(1, originalDisposals)
        assertEquals(0, replacementDisposals)
        assertTrue(budget.reserve("key", 5) { replacementDisposals++ })
        budget.clear()
        assertEquals(1, replacementDisposals)
    }

    @Test fun release_and_clear_dispose_pinned_and_unused_owners_once() {
        val budget = PreviewMemoryBudget(30)
        val disposed = mutableListOf<String>()
        for (key in listOf("a", "b", "c")) assertTrue(budget.reserve(key, 10) { disposed += key })
        budget.pin("a")
        budget.pin("b")
        budget.release("a")
        budget.release("a")
        budget.release("unknown")
        assertEquals(20L, budget.usedBytes)
        budget.clear()
        budget.clear()
        assertEquals(setOf("a", "b", "c"), disposed.toSet())
        assertEquals(3, disposed.size)
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun allocation_failure_releases_only_its_reservation() {
        val budget = PreviewMemoryBudget(30)
        var existingLive = true
        assertTrue(budget.reserve("existing", 10) { existingLive = false })
        budget.pin("existing")
        var allocated: Any? = null
        var cleanups = 0
        assertTrue(budget.reserve("allocation", 20) { allocated = null; cleanups++ })
        try {
            throw IllegalStateException("Allocator failed before producing a resource")
        } catch (_: IllegalStateException) {
            budget.release("allocation")
        }
        assertNull(allocated)
        assertTrue(existingLive)
        assertEquals(1, cleanups)
        assertEquals(10L, budget.usedBytes)
    }

    @Test fun clear_failure_retains_live_accounting_attempts_all_cleanup_and_allows_retry() {
        val budget = PreviewMemoryBudget(30)
        val live = mutableSetOf("a", "b", "c")
        val attempts = mutableListOf<String>()
        var failCleanup = true
        for (key in listOf("a", "b", "c")) assertTrue(budget.reserve(key, 10) {
            attempts += key
            if (key != "c" && failCleanup) throw IllegalStateException("Disposal failed: $key")
            assertTrue(live.remove(key))
        })
        val failure = assertThrows(IllegalStateException::class.java) { budget.clear() }
        assertEquals(1, failure.suppressed.size)
        assertEquals(listOf("a", "b", "c"), attempts)
        assertEquals(setOf("a", "b"), live)
        assertEquals(20L, budget.usedBytes)
        assertThrows(IllegalStateException::class.java) { budget.pin("a") }
        assertFalse(budget.reserve("large", 20) {})
        failCleanup = false
        budget.clear()
        budget.clear()
        assertEquals(listOf("a", "b", "c", "a", "b"), attempts)
        assertTrue(live.isEmpty())
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun eviction_failure_keeps_failed_owner_charged_and_does_not_install_request() {
        val budget = PreviewMemoryBudget(30)
        val attempts = mutableListOf<String>()
        var failCleanup = true
        assertTrue(budget.reserve("a", 10) {
            attempts += "a"
            if (failCleanup) throw IllegalStateException("Still live")
        })
        assertTrue(budget.reserve("b", 10) { attempts += "b" })
        assertTrue(budget.reserve("active", 10) { attempts += "active" })
        budget.pin("active")
        assertThrows(IllegalStateException::class.java) { budget.reserve("new", 20) { attempts += "new" } }
        assertEquals(listOf("a", "b"), attempts)
        assertEquals(20L, budget.usedBytes)
        budget.release("new")
        assertFalse(budget.reserve("another", 20) {})
        assertEquals(listOf("a", "b"), attempts)
        failCleanup = false
        budget.release("a")
        assertEquals(10L, budget.usedBytes)
        assertTrue(budget.reserve("new", 20) { attempts += "new" })
    }

    @Test fun failed_allocation_cleanup_remains_charged_until_idempotent_retry_succeeds() {
        val budget = PreviewMemoryBudget(10)
        var partialResourceLive = true
        var failCleanup = true
        assertTrue(budget.reserve("partial", 10) {
            if (failCleanup) throw IllegalStateException("Cleanup failed")
            partialResourceLive = false
        })
        assertThrows(IllegalStateException::class.java) { budget.release("partial") }
        assertTrue(partialResourceLive)
        assertEquals(10L, budget.usedBytes)
        assertFalse(budget.reserve("new", 1) {})
        failCleanup = false
        budget.release("partial")
        assertFalse(partialResourceLive)
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun callback_can_read_accounting_but_all_mutation_reentry_is_rejected() {
        val budget = PreviewMemoryBudget(10)
        var callbacks = 0
        assertTrue(budget.reserve("a", 10) {
            callbacks++
            assertEquals(10L, budget.usedBytes)
            for (mutation in listOf<() -> Unit>(
                { budget.reserve("b", 1) {} }, { budget.release("a") }, { budget.clear() },
                { budget.pin("a") }, { budget.unpin("a") }
            )) assertThrows(IllegalStateException::class.java) { mutation() }
        })
        budget.release("a")
        assertEquals(1, callbacks)
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun unhandled_callback_reentry_does_not_skip_other_cleanup_or_drop_failed_bytes() {
        val budget = PreviewMemoryBudget(20)
        var reenter = true
        var otherDisposals = 0
        assertTrue(budget.reserve("a", 10) { if (reenter) budget.clear() })
        assertTrue(budget.reserve("b", 10) { otherDisposals++ })
        assertThrows(IllegalStateException::class.java) { budget.clear() }
        assertEquals(1, otherDisposals)
        assertEquals(10L, budget.usedBytes)
        reenter = false
        budget.clear()
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun invalid_inputs_do_not_mutate_live_reservations() {
        for (limit in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { PreviewMemoryBudget(limit) }
        }
        val budget = PreviewMemoryBudget(10)
        var disposals = 0
        assertTrue(budget.reserve("valid", 10) { disposals++ })
        for (key in listOf("", " ", "\t\n", "bad\u0000key")) {
            assertThrows(IllegalArgumentException::class.java) { budget.reserve(key, 1) {} }
            assertThrows(IllegalArgumentException::class.java) { budget.release(key) }
            assertThrows(IllegalArgumentException::class.java) { budget.pin(key) }
            assertThrows(IllegalArgumentException::class.java) { budget.unpin(key) }
        }
        for (bytes in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { budget.reserve("new", bytes) {} }
        }
        assertFalse(budget.reserve("overflow-sized", Long.MAX_VALUE) {})
        assertEquals(10L, budget.usedBytes)
        assertEquals(0, disposals)
    }

    @Test fun long_max_capacity_reservations_cannot_overflow_accounting() {
        val budget = PreviewMemoryBudget(Long.MAX_VALUE)
        var disposed = false
        assertTrue(budget.reserve("huge", Long.MAX_VALUE - 1) { disposed = true })
        budget.pin("huge")
        assertTrue(budget.reserve("last-byte", 1) {})
        assertEquals(Long.MAX_VALUE, budget.usedBytes)
        assertFalse(budget.reserve("overflow", 2) {})
        assertEquals(Long.MAX_VALUE, budget.usedBytes)
        assertFalse(disposed)
        budget.unpin("huge")
        assertTrue(budget.reserve("replacement", Long.MAX_VALUE) {})
        assertTrue(disposed)
        assertEquals(Long.MAX_VALUE, budget.usedBytes)
    }

    @Test fun pinning_unknown_or_unpinning_unused_resource_is_an_owner_error() {
        val budget = PreviewMemoryBudget(10)
        assertThrows(IllegalStateException::class.java) { budget.pin("missing") }
        assertThrows(IllegalStateException::class.java) { budget.unpin("missing") }
        assertTrue(budget.reserve("a", 10) {})
        assertThrows(IllegalStateException::class.java) { budget.unpin("a") }
        assertEquals(10L, budget.usedBytes)
    }

    @Test fun every_operation_is_confined_to_the_constructor_thread() {
        val budget = PreviewMemoryBudget(10)
        var disposals = 0
        assertTrue(budget.reserve("a", 10) { disposals++ })
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                for (operation in listOf<() -> Unit>(
                    { budget.usedBytes }, { budget.reserve("b", 1) {} }, { budget.release("a") },
                    { budget.clear() }, { budget.pin("a") }, { budget.unpin("a") }
                )) assertThrows(IllegalStateException::class.java) { operation() }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        worker.start()
        worker.join(5_000)
        assertFalse("Worker finished", worker.isAlive)
        failure.get()?.let { throw it }
        assertEquals(0, disposals)
        assertEquals(10L, budget.usedBytes)
    }
}
