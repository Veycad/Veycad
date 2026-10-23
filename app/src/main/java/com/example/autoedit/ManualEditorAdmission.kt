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

    class Request internal constructor(internal var granted: ((Lease) -> Unit)?) {
        internal var cancelled = false
        fun cancel() {
            requireMain()
            cancelled = true
            granted = null
            waiting.remove(this)
        }
    }
    class Lease internal constructor() {
        fun release() {
            requireMain()
            if (active !== this) return
            active = null
            dispatch()
        }
    }
    fun acquire(onGranted: (Lease) -> Unit): Request {
        requireMain()
        return Request(onGranted).also { waiting += it; dispatch() }
    }
    private fun dispatch() {
        if (active != null) return
        val request = waiting.firstOrNull() ?: return
        waiting.remove(request)
        val lease = Lease()
        active = lease
        main.post {
            val callback = request.granted
            request.granted = null
            if (request.cancelled || callback == null) lease.release() else callback(lease)
        }
    }
    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())
}
