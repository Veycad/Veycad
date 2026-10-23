package com.veycad.app

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class MediaWorkDispatchTest {
    @Test fun earlyCallbackCannotCollectWorkerSources() {
        val owner = MediaWorkCoordinator()
        val workers = ArrayDeque<Runnable>()
        val dispatch = MediaWorkDispatch(owner, Executor { workers.add(it) }, { it.run(); true })
        var cleanups = 0
        val collect = { owner.tryBeginCleanup()?.use { cleanups++ }; Unit }
        dispatch.executeAfterRelease(collect) {
            dispatch.postAfterRelease(collect) { assertNull(owner.tryBeginCleanup()) }
            assertEquals(0, cleanups)
        }
        workers.removeFirst().run()
        assertEquals(1, cleanups)
        assertEquals(MediaWorkCoordinator.Snapshot(0, false), owner.snapshot())
    }
    @Test fun failingReleaseHookDoesNotHideObserverFailureOrLeakLease() {
        val owner = MediaWorkCoordinator()
        val callbacks = ArrayDeque<Runnable>()
        val dispatch = MediaWorkDispatch(owner, Executor { it.run() }, { callbacks.add(it); true })
        dispatch.postAfterRelease({ error("gc") }) { throw IllegalArgumentException("observer") }
        val failure = assertThrows(IllegalArgumentException::class.java) { callbacks.removeFirst().run() }
        assertEquals("observer", failure.message)
        assertEquals(MediaWorkCoordinator.Snapshot(0, false), owner.snapshot())
    }
    @Test fun queuedWorkAndItsCallbackBothBlockCleanup() {
        val owner = MediaWorkCoordinator()
        val workers = ArrayDeque<Runnable>()
        val callbacks = ArrayDeque<Runnable>()
        val dispatch = MediaWorkDispatch(owner, Executor { workers.add(it) }, { callbacks.add(it); true })
        var completed = false
        dispatch.execute { dispatch.post { completed = true } }
        assertNull(owner.tryBeginCleanup())
        workers.removeFirst().run()
        assertFalse(completed)
        assertNull(owner.tryBeginCleanup())
        callbacks.removeFirst().run()
        assertTrue(completed)
        requireNotNull(owner.tryBeginCleanup()).close()
    }

    @Test fun rejectedExecutorOrHandlerCannotLeakLease() {
        val owner = MediaWorkCoordinator()
        val rejected = MediaWorkDispatch(owner, Executor { throw RejectedExecutionException() }, { false })
        assertThrows(RejectedExecutionException::class.java) { rejected.execute {} }
        assertThrows(RejectedExecutionException::class.java) { rejected.post {} }
        assertEquals(MediaWorkCoordinator.Snapshot(0, false), owner.snapshot())
    }

    @Test fun observerFailureReleasesOnlyItsOwnLease() {
        val owner = MediaWorkCoordinator()
        val callbacks = ArrayDeque<Runnable>()
        val dispatch = MediaWorkDispatch(owner, Executor { it.run() }, { callbacks.add(it); true })
        val other = requireNotNull(owner.tryBeginWork())
        dispatch.post { throw IllegalArgumentException("observer") }
        assertThrows(IllegalArgumentException::class.java) { callbacks.removeFirst().run() }
        assertEquals(MediaWorkCoordinator.Snapshot(1, false), owner.snapshot())
        other.close()
    }

    @Test fun capturePreparationHandoffHasNoIdleGap() {
        val owner = MediaWorkCoordinator()
        val jobs = ArrayDeque<Runnable>()
        val posts = ArrayDeque<Runnable>()
        val dispatch = MediaWorkDispatch(owner, Executor { jobs.add(it) }, { posts.add(it); true })
        dispatch.execute {
            dispatch.post {
                assertNull(owner.tryBeginCleanup())
                dispatch.execute { assertNull(owner.tryBeginCleanup()) }
            }
        }
        jobs.removeFirst().run()
        posts.removeFirst().run()
        assertNull(owner.tryBeginCleanup())
        jobs.removeFirst().run()
        assertEquals(MediaWorkCoordinator.Snapshot(0, false), owner.snapshot())
    }
}
