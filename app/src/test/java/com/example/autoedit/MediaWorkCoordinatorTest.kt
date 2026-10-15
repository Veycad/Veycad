package com.veycad.app

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MediaWorkCoordinatorTest {
    @Test fun workAndCleanupNeverOverlap() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val owner = MediaWorkCoordinator()
                val barrier = CyclicBarrier(2)
                val work = pool.submit<MediaWorkCoordinator.Lease?> {
                    barrier.await(5, TimeUnit.SECONDS); owner.tryBeginWork()
                }
                val clean = pool.submit<MediaWorkCoordinator.Lease?> {
                    barrier.await(5, TimeUnit.SECONDS); owner.tryBeginCleanup()
                }
                val a = work.get(5, TimeUnit.SECONDS)
                val b = clean.get(5, TimeUnit.SECONDS)
                try { assertTrue("Exactly one competing admission succeeds", (a == null) xor (b == null)) }
                finally { a?.close(); b?.close() }
                assertEquals(MediaWorkCoordinator.Snapshot(0, false), owner.snapshot())
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun twoWorkersBlockUntilBothClose() {
        val owner = MediaWorkCoordinator()
        val a = requireNotNull(owner.tryBeginWork())
        val b = requireNotNull(owner.tryBeginWork())
        a.close()
        assertNull(owner.tryBeginCleanup())
        b.close()
        requireNotNull(owner.tryBeginCleanup()).use {
            it.requireCleanupActive()
            assertNull(owner.tryBeginWork())
            assertNull(owner.tryBeginCleanup())
        }
    }

    @Test fun duplicateCloseCannotReleaseAnotherLease() {
        val owner = MediaWorkCoordinator()
        val a = requireNotNull(owner.tryBeginWork())
        val b = requireNotNull(owner.tryBeginWork())
        a.close(); a.close()
        assertEquals(MediaWorkCoordinator.Snapshot(1, false), owner.snapshot())
        assertNull(owner.tryBeginCleanup())
        b.close()
        val cleaning = requireNotNull(owner.tryBeginCleanup())
        a.close()
        assertNull(owner.tryBeginWork())
        cleaning.close(); cleaning.close()
        assertEquals(MediaWorkCoordinator.Snapshot(0, false), owner.snapshot())
    }

    @Test fun closedCleanupCannotAuthorizeDeletion() {
        val owner = MediaWorkCoordinator()
        val cleanup = requireNotNull(owner.tryBeginCleanup())
        cleanup.close()
        assertThrows(IllegalStateException::class.java) { cleanup.requireCleanupActive() }
        requireNotNull(owner.tryBeginWork()).use { work ->
            assertThrows(IllegalStateException::class.java) { work.requireCleanupActive() }
        }
    }

    @Test fun freshProcessHasNoInheritedWork() {
        val old = MediaWorkCoordinator()
        requireNotNull(old.tryBeginWork()).use {
            val fresh = MediaWorkCoordinator()
            requireNotNull(fresh.tryBeginCleanup()).use { it.requireCleanupActive() }
            assertNull(old.tryBeginCleanup())
        }
    }
}
