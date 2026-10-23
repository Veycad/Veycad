package com.veycad.app

internal enum class DecoderRole { INCOMING, OUTGOING }

/** A lease remains idempotent even after its role has been rebound to another source. */
internal class SlotLease internal constructor(
    val sourceIndex: Int,
    val decoder: AutoCloseable,
    private val onClose: () -> Unit
) : AutoCloseable {
    private var closed = false
    private var quarantined = false
    fun requireUsable() { check(!closed && !quarantined) { "Decoder lease requires cleanup acknowledgement" } }
    override fun close() {
        if (closed) return
        try { decoder.close() } catch (error: Throwable) { quarantined = true; throw error }
        closed = true
        onClose()
    }
}

/** Export-thread ownership: close a role before replacement, never transiently open a third codec. */
internal class SourceDecoderSlots internal constructor(
    private val openForRole: (Int, DecoderRole) -> AutoCloseable
) {
    constructor(open: (Int) -> AutoCloseable) : this({ index, _ -> open(index) })

    private val slots = mutableMapOf<DecoderRole, SlotLease>()

    fun acquire(sourceIndex: Int, role: DecoderRole): SlotLease {
        require(sourceIndex in 0 until GalleryImportPolicy.MAX_FILES) { "Invalid video source index" }
        slots[role]?.let { previous ->
            if (previous.sourceIndex == sourceIndex) { previous.requireUsable(); return previous }
            previous.close()
        }
        val decoder = openForRole(sourceIndex, role)
        return SlotLease(sourceIndex, decoder) { slots.remove(role) }.also { slots[role] = it }
    }

    fun releaseAll() {
        var failure: Throwable? = null
        slots.values.toList().forEach { slot ->
            try { slot.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
