package com.veycad.app

import java.util.concurrent.atomic.AtomicLong

/** The future runtime must retain this capability until its failed constructor is fully unwound. */
internal class PreviewAllocationFailure(
    cause: Throwable,
    cleanupFailure: Throwable,
    private val cleanup: AutoCloseable
) : IllegalStateException("Preview allocation failed; owner-thread cleanup retry is required", cause) {
    private val worker = Thread.currentThread()
    private var disposed = false
    init { addSuppressed(cleanupFailure) }

    fun retryClose() {
        check(Thread.currentThread() === worker) { "Preview cleanup belongs to its GL worker" }
        if (disposed) return
        cleanup.close()
        disposed = true
    }
}

/** Allocation ownership only. The injected manager remains the sole admission/LRU authority. */
internal class PreviewAllocationOwner(
    private val budget: PreviewMemoryBudget,
    private val makeCurrent: () -> Unit
) : AutoCloseable {
    private val worker = Thread.currentThread()
    private val ownerId = nextOwner.incrementAndGet()
    private var sequence = 0L
    private val reservations = linkedSetOf<Reservation>()
    private var closing = false
    private var failed = false

    internal inner class Reservation internal constructor(val key: String) : AutoCloseable {
        internal var disposed = false
        internal var quarantined = false
        override fun close() {
            checkWorker()
            try {
                budget.release(key)
                check(disposed) { "Reservation cleanup did not complete" }
                reservations.remove(this)
            } catch (error: Throwable) {
                quarantined = true
                failed = true
                throw error
            }
        }
    }

    fun reserve(bytes: Long, dispose: () -> Unit): Reservation {
        requireUsable()
        require(bytes > 0)
        check(sequence < Long.MAX_VALUE)
        val reservation = Reservation("gles-$ownerId-${++sequence}")
        check(budget.reserve(reservation.key, bytes) {
            try {
                makeCurrent()
                dispose()
                reservation.disposed = true
            } catch (error: Throwable) {
                reservation.quarantined = true
                failed = true
                throw error
            }
        }) { "Preview memory admission denied ($bytes bytes)" }
        reservations += reservation
        try {
            // Residency pin survives between frames. No compositor-owned cache is introduced.
            budget.pin(reservation.key)
            // Eviction callbacks may have selected another owner's EGL context.
            makeCurrent()
            return reservation
        } catch (error: Throwable) {
            runCatching { reservation.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    fun requireUsable() {
        checkWorker()
        check(!closing && !failed && reservations.none { it.disposed }) { "Preview resources are closed or quarantined" }
    }

    fun <T> using(block: () -> T): T {
        requireUsable()
        val pins = mutableListOf<Reservation>()
        try {
            for (reservation in reservations) {
                budget.pin(reservation.key)
                pins += reservation
            }
            makeCurrent()
            return block()
        } finally {
            for (reservation in pins.asReversed()) {
                // Explicit shared-manager cleanup may invalidate a resident during a callback.
                if (!reservation.disposed && !reservation.quarantined) budget.unpin(reservation.key)
            }
        }
    }

    override fun close() {
        checkWorker()
        closing = true
        var failure: Throwable? = null
        for (reservation in reservations.toList()) {
            try { reservation.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun checkWorker() = check(Thread.currentThread() === worker) { "Preview allocations belong to their GL worker" }

    companion object {
        private val nextOwner = AtomicLong()
        // Covers per-resource Kotlin entry/closure/key, JNI map node/views and GL object metadata.
        private const val OVERHEAD = 1024L
        private const val PAGE = 16_384L
        fun storageBytes(capacity: Int): Long {
            require(capacity > 0)
            return ((capacity.toLong() + PAGE - 1) / PAGE) * PAGE + OVERHEAD
        }
        fun imageBytes(width: Int, height: Int): Long {
            require(width > 0 && height > 0)
            val pixels = width.toLong() * height.toLong()
            require(pixels <= (Long.MAX_VALUE - OVERHEAD - PAGE) / 4) { "Texture size overflows accounting" }
            // GLES may expand alpha/luminance to RGBA. Row rounding covers common GPU tile padding.
            val rows = ((width.toLong() + 63) / 64) * 64
            val columns = ((height.toLong() + 63) / 64) * 64
            require(rows <= (Long.MAX_VALUE - OVERHEAD - PAGE) / 4 / columns)
            return ((rows * columns * 4 + PAGE - 1) / PAGE) * PAGE + OVERHEAD
        }
    }
}
