package com.veycad.app

internal class NativeCancellation(private val check: () -> Unit) {
    private val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
    @Suppress("unused") fun isCancelled(): Boolean {
        if(failure.get()!=null) return true
        try { check() } catch(caught:Throwable) { failure.compareAndSet(null,caught) }
        return failure.get()!=null
    }
    fun rethrow() { failure.get()?.let { throw it } }
}

internal object WhisperNative {
    init { System.loadLibrary("veycad_whisper") }
    external fun open(modelPath: String): Long
    external fun recognize(handle: Long, samples: FloatArray, language: String, cancel: NativeCancellation): Array<String>
    external fun close(handle: Long)
}
