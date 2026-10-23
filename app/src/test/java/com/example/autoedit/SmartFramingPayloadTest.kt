package com.veycad.app

import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class SmartFramingPayloadTest {
    private val windows = listOf(VisualEventMap.UsableWindow(0, 1_000_000), VisualEventMap.UsableWindow(2_000_000, 3_000_000))
    private val key = SmartFramingKey("a".repeat(64), 3_000_000, 0, 3_000_000, windows)
    private val track = SmartFramingTrack.fromPoints(listOf(
        SmartFramingTrack.Point(0, .2f, .4f, SmartFramingStatus.TRACKING),
        SmartFramingTrack.Point(999_999, .4f, .4f, SmartFramingStatus.HOLDING),
        SmartFramingTrack.Point(2_000_000, .8f, .6f, SmartFramingStatus.AMBIGUOUS)), windows)

    @Test fun independently_proven_raw_tail_is_not_clipped_to_declared_duration() {
        val raw = listOf(VisualEventMap.UsableWindow(400_000, 2_400_000))
        val rawKey = SmartFramingKey("c".repeat(64), 2_000_000, 400_000, 2_400_000, raw)
        val rawTrack = SmartFramingTrack.fromPoints(listOf(
            SmartFramingTrack.Point(400_000, .2f, .5f, SmartFramingStatus.TRACKING),
            SmartFramingTrack.Point(2_399_999, .8f, .5f, SmartFramingStatus.TRACKING)), raw)
        val restored = SmartFramingPayload.decode(rawKey, SmartFramingPayload.encode(rawKey, rawTrack))
        assertEquals(rawTrack.sample(2_200_000), restored.sample(2_200_000))
        assertEquals(SmartFramingStatus.TRACKING, restored.sample(2_200_000).status)
        assertEquals(SmartFramingStatus.UNKNOWN, restored.sample(2_400_000).status)
    }

    @Test fun compact_round_trip_preserves_gap_status_and_seek_order_without_images() {
        val bytes = SmartFramingPayload.encode(key, track)
        val restored = SmartFramingPayload.decode(key, bytes)
        assertEquals(track.points, restored.points); assertEquals(windows, restored.sourceWindows)
        assertTrue(bytes.size < 512)
        for (pts in listOf(2_100_000L, 250_000L, 1_500_000L, 0L, 250_000L)) {
            assertEquals(track.sample(pts), restored.sample(pts))
        }
        assertEquals(SmartFramingStatus.UNKNOWN, restored.sample(1_500_000).status)
        assertThrows(IllegalArgumentException::class.java) {
            SmartFramingPayload.decode(SmartFramingKey("b".repeat(64), 3_000_000, 0, 3_000_000, windows), bytes)
        }
        assertNotEquals(key.fileName, SmartFramingKey(key.sourceHash, key.durationUs, 0, key.endPtsUs, windows, 500_000).fileName)
    }

    @Test fun checksum_counts_tags_coordinates_and_trailing_data_are_validated_before_restore() {
        val bytes = SmartFramingPayload.encode(key, track)
        fun rejected(value: ByteArray) = assertThrows(IllegalArgumentException::class.java) { SmartFramingPayload.decode(key, value) }
        rejected(bytes.copyOf(bytes.size - 1))
        rejected(bytes + 0)
        rejected(bytes.clone().also { it[it.size - 33] = (it[it.size - 33].toInt() xor 1).toByte() })
        fun malformed(edit: (ByteArray, Int) -> Unit): ByteArray {
            val body = bytes.copyOf(bytes.size - 32)
            val countOffset = 4 + ByteBuffer.wrap(body).int
            edit(body, countOffset)
            return body + MessageDigest.getInstance("SHA-256").digest(body)
        }
        rejected(malformed { body, at -> ByteBuffer.wrap(body).putInt(at, Int.MAX_VALUE) })
        rejected(malformed { body, at -> body[at + 4 + 16] = 99 })
        rejected(malformed { body, at -> ByteBuffer.wrap(body).putFloat(at + 4 + 8, Float.NaN) })
        rejected(malformed { body, at -> ByteBuffer.wrap(body).putLong(at + 4, 1_500_000L) })
    }
}
