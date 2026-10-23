package com.veycad.app

import android.os.Handler
import android.os.Looper

/** One manual editor's consumers at a time, including overlapping Activity lifetimes.
 * A granted lease holds no Activity, Context, surface or worker. Waiting callbacks are
 * removed on stop; release means native stop AND the previous worker cleanup finished.
 */
internal object ManualEditorAdmission {
    private val main = Handler(Looper.getMainLooper())
    private var active: Lease? = null
    private val waiting = linkedSetOf<Request>()

    private val retry = Runnable { dispatch() }
    private var retryScheduled = false
    class Request internal constructor(internal var granted: ((Lease) -> Unit)?, internal val cleanup: Boolean) {
        internal var cancelled = false
        fun cancel() {
            requireMain()
            cancelled = true
            granted = null
            waiting.remove(this)
            if (waiting.isEmpty()) { main.removeCallbacks(retry); retryScheduled = false }
        }
    }
    class Lease internal constructor(private val media: MediaWorkCoordinator.Lease) {
        fun release() {
            requireMain()
            if (active !== this) return
            active = null
            media.close()
            dispatch()
        }
    }
    fun acquire(onGranted: (Lease) -> Unit): Request {
        return enqueue(false, onGranted)
    }
    /** Backup/recovery participates in the same native retirement queue, with exclusive file access. */
    fun acquireForCleanup(onGranted: (Lease) -> Unit): Request = enqueue(true, onGranted)
    private fun enqueue(cleanup: Boolean, onGranted: (Lease) -> Unit): Request {
        requireMain()
        return Request(onGranted, cleanup).also { waiting += it; dispatch() }
    }
    private fun dispatch() {
        main.removeCallbacks(retry); retryScheduled = false
        if (active != null) return
        val request = waiting.firstOrNull() ?: return
        val coordinator = MediaWorkCoordinator.process
        val media = if (request.cleanup) coordinator.tryBeginCleanup() else coordinator.tryBeginWork()
        if (media == null) {
            if (!retryScheduled) { retryScheduled = true; main.postDelayed(retry, 100) }
            return
        }
        waiting.remove(request)
        val lease = Lease(media)
        active = lease
        main.post {
            val callback = request.granted
            request.granted = null
            if (request.cancelled || callback == null) lease.release() else callback(lease)
        }
    }
    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())
}
