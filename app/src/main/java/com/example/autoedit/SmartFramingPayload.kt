package com.veycad.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections

/** Derived analysis identity, independent of selection order, output FPS and project aspect. */
internal class SmartFramingKey(val sourceHash: String, val durationUs: Long,
    val firstPtsUs: Long, val endPtsUs: Long,
    windows: List<VisualEventMap.UsableWindow>, val intervalUs: Long = 250_000L) {
    val windows: List<VisualEventMap.UsableWindow> = Collections.unmodifiableList(windows.toList())
    init {
        require(sourceHash.matches(Regex("[a-f0-9]{64}")))
        require(durationUs > 0 && firstPtsUs >= 0 && endPtsUs > firstPtsUs)
        require(intervalUs in 100_000L..1_000_000L)
        require(this.windows.size in 1..SmartFramingPayload.MAX_WINDOWS)
        require(this.windows.all { it.startUs >= firstPtsUs && it.endUs <= endPtsUs })
        require(this.windows.zipWithNext().all { (a, b) -> a.endUs < b.startUs })
    }
    val fileName: String get() = "smart-${contentHash(SmartFramingPayload.identity(this))}.bin"
}

/** Compact cache only: no project IDs, revisions, images, models, or mutable playback state. */
internal object SmartFramingPayload {
    const val MAX_POINTS = 4_096
    const val MAX_WINDOWS = 256
    const val MAX_BYTES = 128 * 1024
    private const val MAGIC = 0x56534d54
    private const val FORMAT = 1
    private val tags = listOf(SmartFramingStatus.TRACKING, SmartFramingStatus.HOLDING,
        SmartFramingStatus.NO_PERSON, SmartFramingStatus.AMBIGUOUS, SmartFramingStatus.UNKNOWN)

    fun identity(key: SmartFramingKey): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { writeIdentity(it, key) }
    }.toByteArray()

    private fun writeIdentity(out: DataOutputStream, key: SmartFramingKey) = with(out) {
        writeInt(MAGIC); writeInt(FORMAT); writeUTF(key.sourceHash)
        writeInt(MediaFrameAnalysisCache.VERSION); writeUTF(SourceAnalysisProfile.EDITORIAL_SEMANTICS.cacheToken)
        writeInt(SmartFramingTrack.GENERATOR_VERSION); writeLong(key.intervalUs)
        writeLong(key.durationUs); writeLong(key.firstPtsUs); writeLong(key.endPtsUs)
        writeInt(key.windows.size)
        key.windows.forEach { writeLong(it.startUs); writeLong(it.endUs) }
    }

    fun encode(key: SmartFramingKey, track: SmartFramingTrack): ByteArray {
        require(track.sourceWindows == key.windows && track.points.size in 1..MAX_POINTS)
        val payload = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            val identity = identity(key)
            out.writeInt(identity.size); out.write(identity)
            out.writeInt(track.points.size)
            track.points.forEach { point ->
                out.writeLong(point.sourceTimeUs); out.writeFloat(point.centerX); out.writeFloat(point.centerY)
                out.writeByte(tags.indexOf(point.status))
            }
        } }.toByteArray()
        return (payload + MessageDigest.getInstance("SHA-256").digest(payload)).also { require(it.size <= MAX_BYTES) }
    }

    fun decode(key: SmartFramingKey, bytes: ByteArray): SmartFramingTrack {
        require(bytes.size in 33..MAX_BYTES)
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        require(MessageDigest.getInstance("SHA-256").digest(payload).contentEquals(bytes.copyOfRange(payload.size, bytes.size))) {
            "SMART cache checksum changed"
        }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val expected = identity(key)
            require(input.readInt() == expected.size)
            val actual = ByteArray(expected.size).also(input::readFully)
            require(actual.contentEquals(expected)) { "SMART cache identity changed" }
            val count = input.readInt()
            require(count in 1..MAX_POINTS && count.toLong() * 17L == input.available().toLong())
            val points = List(count) {
                val timeUs = input.readLong(); val x = input.readFloat(); val y = input.readFloat()
                val tag = input.readUnsignedByte()
                require(tag in tags.indices)
                SmartFramingTrack.Point(timeUs, x, y, tags[tag])
            }
            require(input.read() == -1)
            SmartFramingTrack.fromPoints(points, key.windows)
        }
    }
}
