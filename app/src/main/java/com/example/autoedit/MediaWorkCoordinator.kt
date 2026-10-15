package com.veycad.app

/** Admission only: never calls workers, observers or file APIs while holding its monitor. */
internal class MediaWorkCoordinator {
    enum class Kind { WORK, CLEANUP }
    data class Snapshot(val activeWork: Int, val cleaning: Boolean)

    class Lease private constructor(private val owner: MediaWorkCoordinator, val kind: Kind) : AutoCloseable {
        internal fun requireCleanupActive() = owner.requireCleanup(this)
        override fun close() = owner.release(this)

        internal companion object {
            // Only leases recorded by owner are authoritative, including if this factory is misused.
            fun issue(owner: MediaWorkCoordinator, kind: Kind) = Lease(owner, kind)
        }
    }

    private val lock = Any()
    private val workers = mutableSetOf<Lease>()
    private var cleanup: Lease? = null

    fun tryBeginWork(): Lease? = synchronized(lock) {
        if (cleanup != null) null else Lease.issue(this, Kind.WORK).also { workers += it }
    }

    fun tryBeginCleanup(): Lease? = synchronized(lock) {
        if (cleanup != null || workers.isNotEmpty()) null
        else Lease.issue(this, Kind.CLEANUP).also { cleanup = it }
    }

    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(workers.size, cleanup != null) }

    private fun requireCleanup(lease: Lease) = synchronized(lock) {
        check(cleanup === lease && lease.kind == Kind.CLEANUP) { "Cleanup lease is not active" }
    }

    private fun release(lease: Lease) = synchronized(lock) {
        workers.remove(lease)
        if (cleanup === lease) cleanup = null
    }

    companion object {
        val process = MediaWorkCoordinator()
        const val CLEANING_MESSAGE = "Очистка временных файлов выполняется. Повторите после завершения"
        const val BUSY_MESSAGE = "Дождитесь завершения текущей операции"
    }
}
