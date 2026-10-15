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
        val lease = admit()
        try { worker.execute { lease.use { action() } } }
        catch (failure: Throwable) { lease.close(); throw failure }
    }

    fun post(action: () -> Unit) {
        val lease = admit()
        try {
            if (!postToMain(Runnable { lease.use { action() } })) throw RejectedExecutionException("Main queue stopped")
        } catch (failure: Throwable) { lease.close(); throw failure }
    }

    private fun admit() = coordinator.tryBeginWork()
        ?: throw RejectedExecutionException(MediaWorkCoordinator.CLEANING_MESSAGE)
}
