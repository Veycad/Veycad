package com.veycad.app

internal class NativeCancellation(private val check: () -> Unit) {
    @Suppress("unused") fun isCancelled(): Boolean = runCatching { check() }.isFailure
}

internal object WhisperNative {
    init { System.loadLibrary("veycad_whisper") }
    external fun open(modelPath: String): Long
    external fun recognize(handle: Long, samples: FloatArray, language: String, cancel: NativeCancellation): Array<String>
    external fun close(handle: Long)
}
