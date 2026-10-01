package com.example.autoedit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.io.EOFException

class MulticlassMatteClientTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readsQuantizedPtsAlignedPlanes() {
        val cache = temporary.newFile("matte.bin")
        DataOutputStream(FileOutputStream(cache)).use { output ->
            output.writeInt(0x564D4154)
            output.writeInt(1)
            output.writeLong(125_000L)
            output.writeShort(2)
            output.writeShort(2)
            listOf(0, 64, 128, 255).forEach(output::writeByte)
        }

        val planes = MulticlassMatteClient.read(cache)

        assertEquals(setOf(125_000L), planes.keys)
        val plane = requireNotNull(planes[125_000L])
        assertEquals(2, plane.width)
        assertEquals(2, plane.height)
        assertArrayEquals(floatArrayOf(0f, 64f / 255f, 128f / 255f, 1f), plane.values, 0.0001f)
    }

    @Test fun separate_pts_and_rectangular_planes_preserve_unsigned_bytes_and_order() {
        val cache = temporary.newFile()
        DataOutputStream(FileOutputStream(cache)).use { output ->
            output.writeInt(0x564D4154)
            output.writeInt(1)
            for ((time, bytes) in listOf(0L to listOf(0, 128), 333_333L to listOf(254, 255))) {
                output.writeLong(time)
                output.writeShort(2)
                output.writeShort(1)
                bytes.forEach(output::writeByte)
            }
        }
        val planes = MulticlassMatteClient.read(cache)
        assertEquals(listOf(0L, 333_333L), planes.keys.toList())
        assertEquals(2, planes[0L]!!.width)
        assertEquals(1, planes[0L]!!.height)
        assertArrayEquals(floatArrayOf(0f, 128f / 255f), planes[0L]!!.values, 0.00001f)
        assertArrayEquals(floatArrayOf(254f / 255f, 1f), planes[333_333L]!!.values, 0.00001f)
    }

    @Test fun invalid_headers_and_empty_required_cache_are_rejected() {
        for ((magic, version) in listOf(0 to 1, 0x564D4154 to 0, 0x564D4154 to 2)) {
            val cache = temporary.newFile()
            DataOutputStream(FileOutputStream(cache)).use { output ->
                output.writeInt(magic)
                output.writeInt(version)
                output.writeLong(125_000L)
                output.writeShort(1)
                output.writeShort(1)
                output.writeByte(255)
            }
            assertThrows("magic=$magic version=$version", IllegalArgumentException::class.java) {
                MulticlassMatteClient.read(cache)
            }
        }
        val empty = temporary.newFile()
        DataOutputStream(FileOutputStream(empty)).use { output ->
            output.writeInt(0x564D4154)
            output.writeInt(1)
        }
        assertThrows(IllegalArgumentException::class.java) { MulticlassMatteClient.read(empty) }
        assertTrue(MulticlassMatteClient.read(empty, requireFrames = false).isEmpty())
    }

    @Test fun incomplete_plane_payload_is_an_error_instead_of_a_partial_mask() {
        val cache = temporary.newFile()
        DataOutputStream(FileOutputStream(cache)).use { output ->
            output.writeInt(0x564D4154)
            output.writeInt(1)
            output.writeLong(125_000L)
            output.writeShort(2)
            output.writeShort(2)
            output.writeByte(255)
        }
        assertThrows(EOFException::class.java) { MulticlassMatteClient.read(cache) }
    }

    @Test fun incomplete_final_timestamp_is_rejected_after_a_complete_valid_frame() {
        for (trailingBytes in 1..7) {
            val cache = temporary.newFile()
            DataOutputStream(FileOutputStream(cache)).use { output ->
                output.writeInt(0x564D4154)
                output.writeInt(1)
                output.writeLong(125_000L)
                output.writeShort(1)
                output.writeShort(1)
                output.writeByte(255)
                repeat(trailingBytes) { output.writeByte(0) }
            }
            assertThrows("$trailingBytes trailing timestamp bytes", EOFException::class.java) {
                MulticlassMatteClient.read(cache)
            }
        }
    }
}
