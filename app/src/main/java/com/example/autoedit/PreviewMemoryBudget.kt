package com.veycad.app

/**
 * One shared preview resource budget, constructed and used exclusively on the GL worker.
 * A lazy reader must marshal budget operations and disposal to that same thread; create no
 * per-source budgets. All operations, including [usedBytes], enforce construction-thread access.
 *
 * Reserve BEFORE allocating. [reserve] transfers cleanup responsibility only on true; the
 * callback must dispose the owner's resource (including partially allocated state). On allocation
 * failure call [release] in the failure path. Explicit release/clear also invoke this callback:
 * do not separately dispose an owned resource or remove accounting while retaining it. Callers
 * include texture/FBO/thumbnail bytes, semantic chunks, headers and relevant overhead in bytes.
 * Positive sizes prevent zero-byte entries from accumulating outside the limit.
 *
 * Pin before using a resource; pair each pin with unpin in finally. Only unused, healthy entries
 * can be evicted. Pin marks the resource as most recently used; unpin ends use without refreshing
 * recency. Explicit release/clear deliberately dispose even pinned resources, so owners must
 * first stop using them. Keys identify individual allocations; duplicate reserve returns false
 * without replacing anything. Dispose/release the old allocation before reserving its key again.
 *
 * Disposal callbacks must be idempotent across failed attempts. Bytes are removed only AFTER
 * successful disposal; a throwing callback leaves its entry charged and quarantined from pinning
 * and automatic eviction. Explicit release/clear can retry it. Cleanup attempts every selected
 * entry, then throws IllegalStateException with the first failure as cause and others suppressed.
 * Successful disposal is never repeated. A failed eviction installs no new reservation; already
 * successful disposals cannot be rolled back. Ordinary capacity/duplicate rejection changes nothing.
 *
 * Callbacks may read [usedBytes], which still includes their allocation until they succeed, but
 * every mutation re-entry is rejected before changing anything. No callbacks run on another thread.
 */
class PreviewMemoryBudget(private val limitBytes: Long = 32L * 1024 * 1024) {
    private class Entry(val bytes: Long, val dispose: () -> Unit) {
        var pins = 0
        var cleanupFailed = false
    }

    private val ownerThread = Thread.currentThread()
    // Insertion order is LRU order. A successful pin moves its entry to the end.
    private val entries = LinkedHashMap<String, Entry>()
    private var chargedBytes = 0L
    private var cleaningUp = false

    init {
        require(limitBytes > 0) { "Budget limit must be positive" }
    }

    val usedBytes: Long
        get() {
            checkOwnerThread()
            return chargedBytes
        }

    fun reserve(key: String, bytes: Long, evict: () -> Unit): Boolean {
        checkMutation()
        validateKey(key)
        require(bytes > 0) { "Reservation size must be positive" }
        if (key in entries || bytes > limitBytes) return false

        val available = limitBytes - chargedBytes
        if (bytes > available) {
            val needed = bytes - available
            var reclaimable = 0L
            val victims = mutableListOf<Pair<String, Entry>>()
            for ((victimKey, entry) in entries) {
                if (entry.pins == 0 && !entry.cleanupFailed) {
                    victims += victimKey to entry
                    // Sum is bounded by chargedBytes, avoiding Long overflow even at Long.MAX_VALUE.
                    reclaimable += entry.bytes
                    if (reclaimable >= needed) break
                }
            }
            // Preflight before any disposal: an impossible request must preserve every owner.
            if (reclaimable < needed) return false
            disposeEntries(victims)
        }
        entries[key] = Entry(bytes, evict)
        // Preflight established enough room; adding bytes cannot exceed limitBytes or overflow.
        chargedBytes += bytes
        return true
    }

    fun release(key: String) {
        checkMutation()
        validateKey(key)
        val entry = entries[key] ?: return
        disposeEntries(listOf(key to entry))
    }

    fun clear() {
        checkMutation()
        disposeEntries(entries.map { it.key to it.value })
    }

    fun pin(key: String) {
        checkMutation()
        validateKey(key)
        val entry = usableEntry(key)
        check(entry.pins < Int.MAX_VALUE) { "Pin count overflow" }
        entry.pins++
        entries.remove(key)
        entries[key] = entry
    }

    fun unpin(key: String) {
        checkMutation()
        validateKey(key)
        val entry = usableEntry(key)
        check(entry.pins > 0) { "Resource is not pinned: $key" }
        entry.pins--
    }

    private fun usableEntry(key: String): Entry {
        val entry = checkNotNull(entries[key]) { "Resource is not reserved: $key" }
        check(!entry.cleanupFailed) { "Resource disposal failed; explicit cleanup retry required: $key" }
        return entry
    }

    private fun disposeEntries(victims: List<Pair<String, Entry>>) {
        var failure: IllegalStateException? = null
        cleaningUp = true
        try {
            for ((key, entry) in victims) {
                try {
                    entry.dispose()
                    entries.remove(key)
                    chargedBytes -= entry.bytes
                } catch (error: Throwable) {
                    // Resource might still be live. Never drop its accounting on unconfirmed cleanup.
                    entry.cleanupFailed = true
                    val previousFailure = failure
                    if (previousFailure == null) {
                        failure = IllegalStateException("Preview resource disposal failed: $key", error)
                    } else {
                        previousFailure.addSuppressed(error)
                    }
                }
            }
        } finally {
            cleaningUp = false
        }
        failure?.let { throw it }
    }

    private fun checkOwnerThread() {
        check(Thread.currentThread() === ownerThread) { "Preview budget belongs to its construction thread" }
    }

    private fun checkMutation() {
        checkOwnerThread()
        check(!cleaningUp) { "Budget mutation from a disposal callback is forbidden" }
    }

    private fun validateKey(key: String) {
        require(key.isNotBlank() && key.none { Character.isISOControl(it) }) {
            "Resource key must be nonblank and contain no control characters"
        }
    }
}
