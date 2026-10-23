package com.veycad.app

import java.io.Closeable

/** Own each native handle immediately; use() preserves the operation error during rollback. */
internal class NativeResources : Closeable {
    private val releases = mutableListOf<() -> Unit>()

    fun defer(release: () -> Unit) { releases += release }
    fun <T> own(value: T, release: (T) -> Unit): T = value.also { defer { release(it) } }
    fun transfer() { releases.clear() }

    override fun close() {
        var failure: Throwable? = null
        while (releases.isNotEmpty()) {
            try { releases.removeAt(releases.lastIndex).invoke() }
            catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error
                else if (first !== error) first.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
