package com.veycad.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest

/** Two precharged byte pages, never a borrowed FloatArray. Derived temporary data only;
 * the canonical project/store remains the authority. Every bit is verified before admission. */
internal class PreparedAttachmentPages(directory: File = File(System.getProperty("java.io.tmpdir")),
    private val checkCancelled: () -> Unit = {},
    private val deleteFile: (File) -> Unit = { Files.deleteIfExists(it.toPath()); Unit }
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private var scratch: ByteArray? = ByteArray(PAGE_BYTES)
    private var pages: Array<ByteArray>? = arrayOf(ByteArray(PAGE_BYTES), ByteArray(PAGE_BYTES))
    private val file = File.createTempFile("preview-floats-", ".tmp", directory)
    private var stream: RandomAccessFile? = try { RandomAccessFile(file, "rw") } catch (failure: Throwable) {
        runCatching { Files.deleteIfExists(file.toPath()) }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
    private val pageNumbers = longArrayOf(-1, -1)
    private val recency = longArrayOf(0, 0)
    private var tick = 0L
    private var byteCount = 0L
    private var sealed = false
    private var closing = false
    private var closed = false
    private val writtenDigest = MessageDigest.getInstance("SHA-256")
    private val checksums = ArrayList<ByteArray>()
    val residentPageBytes: Int get() { requireOpen(); return checkNotNull(pages).sumOf { it.size } }

    internal class Values(private val owner: PreparedAttachmentPages, private val offset: Long,
        private val count: Int) {
        private val identity = Any()
        fun identity(): Any = identity
        val size: Int get() { owner.requireOpen(); return count }
        operator fun get(index: Int): Float {
            require(index in 0 until count)
            return owner.read(offset + index * 4L)
        }
        fun copy(): FloatArray = FloatArray(size) { get(it) }
    }

    fun append(count: Int, value: (Int) -> Float): Values {
        requireOpen(); check(!sealed)
        require(count > 0 && count <= MAX_BYTES / 4)
        val bytes = Math.multiplyExact(count.toLong(), 4)
        require(byteCount <= MAX_BYTES - bytes) { "Prepared analysis exceeds the bounded spill size" }
        val start = byteCount
        val output = checkNotNull(stream)
        val scratch = checkNotNull(scratch)
        var index = 0
        while (index < count) {
            checkCancelled()
            val values = minOf(PAGE_BYTES / 4, count - index)
            repeat(values) { local ->
                val sample = value(index + local)
                require(sample.isFinite()) { "Invalid prepared analysis float" }
                val bits = sample.toRawBits()
                val offset = local * 4
                scratch[offset] = bits.toByte(); scratch[offset + 1] = (bits ushr 8).toByte()
                scratch[offset + 2] = (bits ushr 16).toByte(); scratch[offset + 3] = (bits ushr 24).toByte()
            }
            output.write(scratch, 0, values * 4)
            writtenDigest.update(scratch, 0, values * 4)
            index += values
        }
        byteCount += bytes
        return Values(this, start, count)
    }

    fun seal() {
        requireOpen(); check(!sealed)
        val input = checkNotNull(stream)
        val scratch = checkNotNull(scratch)
        input.fd.sync(); input.seek(0)
        check(input.length() == byteCount)
        val readDigest = MessageDigest.getInstance("SHA-256")
        var remaining = byteCount
        while (remaining > 0) {
            checkCancelled()
            val bytes = minOf(PAGE_BYTES.toLong(), remaining).toInt()
            input.readFully(scratch, 0, bytes)
            readDigest.update(scratch, 0, bytes)
            checksums += MessageDigest.getInstance("SHA-256").apply { update(scratch, 0, bytes) }.digest()
            remaining -= bytes
        }
        check(readDigest.digest().contentEquals(writtenDigest.digest())) { "Prepared analysis spill verification failed" }
        sealed = true
    }

    private fun read(offset: Long): Float {
        requireOpen(); check(sealed)
        val pages = checkNotNull(pages)
        val page = offset / PAGE_BYTES
        var slot = pageNumbers.indexOf(page)
        if (slot < 0) {
            checkCancelled()
            check(file.isFile && file.length() == byteCount) { "Prepared analysis spill is missing or truncated" }
            slot = if (recency[0] <= recency[1]) 0 else 1
            val input = checkNotNull(stream)
            input.seek(page * PAGE_BYTES)
            val bytes = minOf(PAGE_BYTES.toLong(), byteCount - page * PAGE_BYTES).toInt()
            input.readFully(pages[slot], 0, bytes)
            val digest = MessageDigest.getInstance("SHA-256").apply { update(pages[slot], 0, bytes) }.digest()
            check(digest.contentEquals(checksums[page.toInt()])) { "Prepared analysis spill checksum changed" }
            pageNumbers[slot] = page
        }
        recency[slot] = ++tick
        val index = (offset % PAGE_BYTES).toInt()
        val bytes = pages[slot]
        val bits = (bytes[index].toInt() and 255) or ((bytes[index + 1].toInt() and 255) shl 8) or
            ((bytes[index + 2].toInt() and 255) shl 16) or ((bytes[index + 3].toInt() and 255) shl 24)
        return Float.fromBits(bits)
    }

    private fun requireOpen() {
        check(Thread.currentThread() === owner) { "Prepared analysis pages belong to the GL owner" }
        check(!closing && !closed) { "Prepared attachment lifetime is closed" }
    }

    override fun close() {
        check(Thread.currentThread() === owner) { "Prepared analysis pages belong to the GL owner" }
        if (closed) return
        closing = true
        stream?.close(); stream = null
        deleteFile(file) // Retain ownership and charged bytes on failure so explicit cleanup retries.
        checksums.clear(); pageNumbers.fill(-1)
        scratch = null; pages = null
        closed = true
    }

    companion object {
        const val PAGE_BYTES = 64 * 1024
        const val MAX_BYTES = 512L * 1024 * 1024
        fun reservationBytes(bytes: Long): Long {
            require(bytes in 0..MAX_BYTES)
            return 3L * PAGE_BYTES + 4096 + ((bytes + PAGE_BYTES - 1) / PAGE_BYTES) * 96
        }
    }
}
