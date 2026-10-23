package com.veycad.app

/** The renderer's native ownership chain. A failed dependent release keeps its EGL/surface/
 * codec owners alive, while independent owners are still attempted. Retry never replays work. */
internal class RenderNativeCleanup(
    private val stopEncoder: () -> Unit,
    private val releaseDecoders: () -> Unit,
    private val releaseGl: () -> Unit,
    private val releaseTarget: () -> Unit,
    private val releaseInputSurface: () -> Unit,
    private val releaseEncoder: () -> Unit,
    private val releaseMuxer: () -> Unit
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private var stopped = false
    private var decoders = false
    private var gl = false
    private var target = false
    private var surface = false
    private var encoder = false
    private var muxer = false

    override fun close() {
        check(Thread.currentThread() === owner) { "Native rendering cleanup belongs to its GL worker" }
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        if (!stopped && !encoder) attempt { stopEncoder(); stopped = true }
        if (!decoders) attempt { releaseDecoders(); decoders = true }
        if (decoders && !gl) attempt { releaseGl(); gl = true }
        if (gl && !target) attempt { releaseTarget(); target = true }
        if (target && !surface) attempt { releaseInputSurface(); surface = true }
        if (surface && !encoder) attempt { releaseEncoder(); encoder = true }
        if (!muxer) attempt { releaseMuxer(); muxer = true }
        failure?.let { throw it }
    }
}
