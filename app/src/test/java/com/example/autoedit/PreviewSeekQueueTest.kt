package com.veycad.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PreviewSeekQueueTest {
    @Test fun latest_seek_wins() {
        val queue = PreviewSeekQueue()
        val generations = (0 until 100).map { index ->
            // Alternating direction, deliberately off the canonical frame grid.
            queue.submit(if (index % 2 == 0) index * 1_001L else 1_000_000L - index * 1_003L)
        }
        assertEquals(PreviewGeneration(0, 0, 100) to 900_703L, queue.takeLatest())
        assertNull(queue.takeLatest())
        generations.dropLast(1).forEach { assertFalse(queue.isCurrent(it)) }
        assertTrue(queue.isCurrent(generations.last()))
    }

    @Test fun taken_generation_remains_current_until_replaced() {
        val queue = PreviewSeekQueue()
        val first = queue.submit(17)
        assertEquals(PreviewGeneration(0, 0, 1) to 17L, queue.takeLatest())
        assertTrue(queue.isCurrent(first))
        assertNull(queue.takeLatest())
        assertTrue(queue.isCurrent(first))
        val second = queue.submit(17)
        assertEquals(PreviewGeneration(0, 0, 2), second)
        assertFalse(queue.isCurrent(first))
        assertTrue(queue.isCurrent(second))
    }

    @Test fun old_surface_or_project_generations_never_become_current_again() {
        val queue = PreviewSeekQueue()
        val original = queue.submit(123)
        queue.takeLatest()
        queue.invalidateSurface()
        assertFalse(queue.isCurrent(original))
        val newSurface = queue.submit(123)
        assertEquals(PreviewGeneration(0, 1, 2), newSurface)
        queue.takeLatest()
        queue.invalidateProject()
        assertFalse(queue.isCurrent(newSurface))
        // Undo may restore the same revision/time; epochs still belong to operations.
        val restoredProject = queue.submit(123)
        assertEquals(PreviewGeneration(1, 1, 3), restoredProject)
        queue.invalidateProject()
        queue.invalidateSurface()
        val restoredAgain = queue.submit(123)
        assertEquals(PreviewGeneration(2, 2, 4) to 123L, queue.takeLatest())
        for (old in listOf(original, newSurface, restoredProject)) assertFalse(queue.isCurrent(old))
        assertTrue(queue.isCurrent(restoredAgain))
    }

    @Test fun invalidation_clears_pending() {
        for (invalidate in listOf<PreviewSeekQueue.() -> Unit>(
            { invalidateProject() }, { invalidateSurface() }
        )) {
            val queue = PreviewSeekQueue()
            val pending = queue.submit(71)
            assertTrue("A submitted pending request starts current", queue.isCurrent(pending))
            queue.invalidate()
            assertNull(queue.takeLatest())
            assertFalse(queue.isCurrent(pending))
        }
    }

    @Test fun newly_created_and_invalidated_queues_do_not_authorize_requests() {
        val queue = PreviewSeekQueue()
        assertNull(queue.takeLatest())
        assertFalse(queue.isCurrent(PreviewGeneration(0, 0, 0)))
        assertFalse(queue.isCurrent(PreviewGeneration(0, 0, 1)))
        val first = queue.submit(0)
        assertTrue(queue.isCurrent(first))
        assertFalse(queue.isCurrent(PreviewGeneration(0, 0, 2)))
        queue.invalidateProject()
        assertFalse(queue.isCurrent(PreviewGeneration(1, 0, 1)))
        assertFalse(queue.isCurrent(first))
        queue.invalidateSurface()
        assertFalse(queue.isCurrent(PreviewGeneration(1, 1, 1)))
        assertFalse(queue.isCurrent(PreviewGeneration(1, 1, 2)))
        assertNull(queue.takeLatest())
        val issued = queue.submit(0)
        assertEquals(PreviewGeneration(1, 1, 2) to 0L, queue.takeLatest())
        assertTrue(queue.isCurrent(issued))
    }

    @Test fun negative_submit_rejects_without_mutation() {
        val queue = PreviewSeekQueue()
        val current = queue.submit(23)
        for (invalid in listOf(-1L, Long.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { queue.submit(invalid) }
            assertTrue(queue.isCurrent(current))
        }
        assertEquals(PreviewGeneration(0, 0, 1) to 23L, queue.takeLatest())
        assertEquals(PreviewGeneration(0, 0, 2), queue.submit(24))
    }

    @Test fun raw_output_time_accepts_long_max_without_rounding_or_overflow() {
        val queue = PreviewSeekQueue()
        val generation = queue.submit(Long.MAX_VALUE)
        assertEquals(PreviewGeneration(0, 0, 1) to Long.MAX_VALUE, queue.takeLatest())
        assertTrue(queue.isCurrent(generation))
    }

    @Test fun generation_rejects_negative_epochs() {
        for (invalid in listOf(-1L, Long.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { PreviewGeneration(invalid, 0, 0) }
            assertThrows(IllegalArgumentException::class.java) { PreviewGeneration(0, invalid, 0) }
            assertThrows(IllegalArgumentException::class.java) { PreviewGeneration(0, 0, invalid) }
        }
    }

    @Test fun seek_epoch_overflow_rejects_atomically_and_preserves_pending() {
        val queue = PreviewSeekQueue(PreviewGeneration(7, 11, Long.MAX_VALUE - 1))
        val current = queue.submit(31)
        assertEquals(PreviewGeneration(7, 11, Long.MAX_VALUE), current)
        repeat(2) { assertThrows(IllegalStateException::class.java) { queue.submit(99) } }
        assertTrue(queue.isCurrent(current))
        assertEquals(current to 31L, queue.takeLatest())
        assertNull(queue.takeLatest())
        assertThrows(IllegalStateException::class.java) { queue.submit(100) }
        assertTrue(queue.isCurrent(current))
    }

    @Test fun project_epoch_overflow_rejects_atomically_and_preserves_pending() {
        val queue = PreviewSeekQueue(PreviewGeneration(Long.MAX_VALUE - 1, 11, 7))
        val old = queue.submit(31)
        queue.invalidateProject()
        assertFalse(queue.isCurrent(old))
        assertNull(queue.takeLatest())
        val current = queue.submit(37)
        assertEquals(PreviewGeneration(Long.MAX_VALUE, 11, 9), current)
        repeat(2) { assertThrows(IllegalStateException::class.java) { queue.invalidateProject() } }
        assertTrue(queue.isCurrent(current))
        assertEquals(current to 37L, queue.takeLatest())
        assertEquals(PreviewGeneration(Long.MAX_VALUE, 11, 10), queue.submit(41))
    }

    @Test fun surface_epoch_overflow_rejects_atomically_and_preserves_pending() {
        val queue = PreviewSeekQueue(PreviewGeneration(11, Long.MAX_VALUE - 1, 7))
        val old = queue.submit(31)
        queue.invalidateSurface()
        assertFalse(queue.isCurrent(old))
        assertNull(queue.takeLatest())
        val current = queue.submit(37)
        assertEquals(PreviewGeneration(11, Long.MAX_VALUE, 9), current)
        repeat(2) { assertThrows(IllegalStateException::class.java) { queue.invalidateSurface() } }
        assertTrue(queue.isCurrent(current))
        assertEquals(current to 37L, queue.takeLatest())
        assertEquals(PreviewGeneration(11, Long.MAX_VALUE, 10), queue.submit(41))
    }

    @Test fun seeded_epochs_do_not_authorize_an_unissued_generation() {
        val seed = PreviewGeneration(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
        val queue = PreviewSeekQueue(seed)
        assertFalse(queue.isCurrent(seed))
        assertThrows(IllegalStateException::class.java) { queue.submit(0) }
        assertThrows(IllegalStateException::class.java) { queue.invalidateProject() }
        assertThrows(IllegalStateException::class.java) { queue.invalidateSurface() }
        assertFalse(queue.isCurrent(seed))
        assertNull(queue.takeLatest())
    }

    @Test fun ui_submission_and_invalidations_are_visible_to_gl_worker_in_latch_order() {
        val queue = PreviewSeekQueue()
        val burstReady = CountDownLatch(1)
        val firstTaken = CountDownLatch(1)
        val latestReady = CountDownLatch(1)
        val latestTaken = CountDownLatch(1)
        val replacementReady = CountDownLatch(1)
        runWorkers(
            {
                queue.submit(100)
                queue.submit(200)
                burstReady.countDown()
                await(firstTaken)
                queue.invalidateSurface()
                queue.submit(300)
                queue.invalidateProject()
                queue.submit(400)
                latestReady.countDown()
                await(latestTaken)
                queue.submit(500)
                replacementReady.countDown()
            },
            {
                await(burstReady)
                val first = PreviewGeneration(0, 0, 2)
                assertEquals(first to 200L, queue.takeLatest())
                assertTrue(queue.isCurrent(first))
                firstTaken.countDown()
                await(latestReady)
                assertFalse(queue.isCurrent(first))
                assertFalse(queue.isCurrent(PreviewGeneration(0, 1, 3)))
                val latest = PreviewGeneration(1, 1, 4)
                assertEquals(latest to 400L, queue.takeLatest())
                assertNull(queue.takeLatest())
                assertTrue(queue.isCurrent(latest))
                latestTaken.countDown()
                await(replacementReady)
                assertFalse(queue.isCurrent(latest))
                val replacement = PreviewGeneration(1, 1, 5)
                assertEquals(replacement to 500L, queue.takeLatest())
                assertTrue(queue.isCurrent(replacement))
                assertNull(queue.takeLatest())
            }
        )
    }

    @Test fun barrier_coordinated_submit_invalidate_and_take_match_a_serial_order() {
        val queue = PreviewSeekQueue()
        val old = queue.submit(100)
        val start = CyclicBarrier(3)
        val submitted = AtomicReference<PreviewGeneration>()
        val taken = AtomicReference<Pair<PreviewGeneration, Long>?>()
        runWorkers(
            { start.await(5, TimeUnit.SECONDS); submitted.set(queue.submit(700)) },
            { start.await(5, TimeUnit.SECONDS); queue.invalidateSurface() },
            { start.await(5, TimeUnit.SECONDS); taken.set(queue.takeLatest()) }
        )
        val issued = submitted.get()
        assertTrue(issued == PreviewGeneration(0, 0, 2) || issued == PreviewGeneration(0, 1, 2))
        val result = taken.get()
        assertTrue(result == null || result == (old to 100L) || result == (issued to 700L))
        assertFalse(queue.isCurrent(old))
        val remaining = queue.takeLatest()
        if (issued.surface == 0L) {
            assertFalse(queue.isCurrent(issued))
            assertNull(remaining)
        } else {
            assertTrue(queue.isCurrent(issued))
            // A take cannot consume an issued request twice or silently lose it.
            if (result == (issued to 700L)) assertNull(remaining)
            else assertEquals(issued to 700L, remaining)
        }
        assertNull(queue.takeLatest())
        assertEquals(PreviewGeneration(0, 1, 3), queue.submit(900))
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("Coordinated worker reached the next phase", latch.await(5, TimeUnit.SECONDS))
    }

    private fun runWorkers(vararg operations: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        val workers = operations.map { operation ->
            Thread {
                try { operation() } catch (error: Throwable) { failure.compareAndSet(null, error) }
            }.apply { isDaemon = true }
        }
        try {
            workers.forEach { it.start() }
            workers.forEach { it.join(6_000) }
            failure.get()?.let { throw it }
            workers.forEach { assertFalse("Coordinated worker finished", it.isAlive) }
        } finally {
            workers.filter { it.isAlive }.forEach { it.interrupt() }
            workers.forEach { it.join(1_000) }
        }
    }
}
