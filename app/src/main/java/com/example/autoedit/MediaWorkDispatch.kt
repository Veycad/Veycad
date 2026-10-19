package com.veycad.app

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Borrows existing worker/main queues; each queued continuation owns a separate admission. */
internal class MediaWorkDispatch(
    private val coordinator: MediaWorkCoordinator,
    private val worker: Executor,
    private val postToMain: (Runnable) -> Boolean
) {
    fun execute(action: () -> Unit) {
        executeAfterRelease({}, action)
    }

    fun executeAfterRelease(afterRelease: () -> Unit, action: () -> Unit) {
        val lease = admit()
        try { worker.execute { try { lease.use { action() } } finally { runCatching(afterRelease) } } }
        catch (failure: Throwable) { lease.close(); runCatching(afterRelease); throw failure }
    }

    fun post(action: () -> Unit) {
        postAfterRelease({}, action)
    }

    fun postAfterRelease(afterRelease: () -> Unit, action: () -> Unit) {
        val lease = admit()
        try {
            if (!postToMain(Runnable { try { lease.use { action() } } finally { runCatching(afterRelease) } }))
                throw RejectedExecutionException("Main queue stopped")
        } catch (failure: Throwable) { lease.close(); runCatching(afterRelease); throw failure }
    }

    private fun admit() = coordinator.tryBeginWork()
        ?: throw RejectedExecutionException(MediaWorkCoordinator.CLEANING_MESSAGE)
}
