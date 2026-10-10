package com.veycad.app

import android.app.Activity
import android.content.Context
import android.os.PowerManager
import android.view.WindowManager

/** Keeps an explicitly requested local render alive, including when the display times out. */
internal class RenderWakeGuard private constructor(
    private val activity: Activity,
    private val wakeLock: PowerManager.WakeLock
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        if (wakeLock.isHeld) wakeLock.release()
        activity.runOnUiThread {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    companion object {
        fun acquire(activity: Activity): RenderWakeGuard {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val power = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "${activity.packageName}:montage-render"
            ).apply {
                setReferenceCounted(false)
                acquire(MAXIMUM_RENDER_MS)
            }
            return RenderWakeGuard(activity, wakeLock)
        }

        private const val MAXIMUM_RENDER_MS = 15 * 60 * 1_000L
    }
}
