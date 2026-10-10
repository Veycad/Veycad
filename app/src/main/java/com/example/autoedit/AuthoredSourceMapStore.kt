package com.veycad.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.IdentityHashMap

/** Immutable author data. Neither analysis repair nor analysis eviction owns this directory. */
class AuthoredSourceMapStore(private val directory: File) {
    internal fun writer() = Writer()
    internal fun reader() = Reader()

    internal inner class Writer {
        private val identities = IdentityHashMap<SourceTimeMap, String>()
        private val verified = hashSetOf<String>()
        private var writtenBytes = 0L

        fun reference(map: SourceTimeMap): String = identities.getOrPut(map) {
            val count = map.points.size
            require(count in 2..HybridProjectCodec.MAX_SOURCE_POINTS) { "Authored source map count exceeds limit" }
            val bytes = ByteArrayOutputStream(12 + count * 12)
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC); output.writeInt(1); output.writeInt(count)
                map.points.forEach { output.writeInt(it.localFrame); output.writeLong(it.sourceTimeUs) }
            }
            val payload = bytes.toByteArray()
            val hash = contentHash(payload)
            if (verified.add(hash)) {
                require(payload.size <= MAX_SNAPSHOT_MAP_BYTES - writtenBytes) { "Authored source map snapshot exceeds allocation limit" }
                writtenBytes += payload.size
                val file = ownedChild(directory, "$hash.map")
                if (file.exists()) {
                    require(readPayload(hash).contentEquals(payload)) { "Published authored map is corrupt: $hash" }
                } else {
                    atomicWrite(file, payload)
                }
            }
            hash
        }
    }

    /** One cache/allocation reservation per load, shared by original/current/all history. */
    internal inner class Reader {
        private val maps = hashMapOf<String, SourceTimeMap>()
        private var allocatedBytes = 0L

        fun resolve(hash: String): SourceTimeMap = maps.getOrPut(hash) {
            val file = mapFile(hash)
            val size = file.length()
            require(size in 36..MAX_BYTES && size <= MAX_SNAPSHOT_MAP_BYTES - allocatedBytes) {
                "Authored source map allocation limit exceeded or map missing: $hash"
            }
            allocatedBytes += size
            val payload = readPayload(hash)
            require(payload.size.toLong() == size) { "Authored source map changed while reading" }
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                input.readInt(); input.readInt()
                val count = input.readInt()
                SourceTimeMap(List(count) { SourceTimeMap.Point(input.readInt(), input.readLong()) })
            }
        }
    }

    private fun mapFile(hash: String): File {
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "Invalid authored source map reference" }
        return ownedChild(directory, "$hash.map")
    }

    private fun readPayload(hash: String): ByteArray {
        val file = mapFile(hash)
        require(file.isFile && file.length() in 36..MAX_BYTES) { "Authored source map is missing or oversized: $hash" }
        // Validate the fixed header/count/length BEFORE allocating the payload or its points.
        DataInputStream(file.inputStream()).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == 1) { "Unsupported authored source map" }
            val count = input.readInt()
            require(count in 2..HybridProjectCodec.MAX_SOURCE_POINTS && 12L + count * 12L == file.length()) {
                "Invalid authored source map count/length: $hash"
            }
        }
        val bytes = file.inputStream().use { it.readBytesBounded(MAX_BYTES.toInt()) }
        require(contentHash(bytes) == hash) { "Authored source map hash mismatch: $hash" }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == 1)
            val count = input.readInt()
            require(count in 2..HybridProjectCodec.MAX_SOURCE_POINTS && 12L + count * 12L == bytes.size.toLong()) {
                "Authored source map changed while reading"
            }
        }
        return bytes
    }

    companion object {
        private const val MAGIC = 0x56534d50
        internal const val MAX_BYTES = 12L + HybridProjectCodec.MAX_SOURCE_POINTS * 12L
        // Bound unique author payloads, not repeated references. No precision/history truncation.
        internal const val MAX_SNAPSHOT_MAP_BYTES = 128L * 1024 * 1024
    }
}
