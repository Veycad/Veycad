package com.veycad.app

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AuthoredSourceMapBudgetTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun map(end: Long = 100) = SourceTimeMap(listOf(SourceTimeMap.Point(0, 0), SourceTimeMap.Point(10, end)))

    @Test fun uniqueBudgetIsCheckedBeforePayloadAllocationAndRejectedHashIsNeverCached() {
        val directory = temporary.newFolder()
        val allocations = mutableListOf<Int>()
        val writer = AuthoredSourceMapStore(directory).writer(71, allocations::add)
        writer.reference(map()) // One canonical two-point map consumes 36 bytes.
        repeat(2) { assertThrows(IllegalArgumentException::class.java) { writer.reference(map(200)) } }
        assertEquals(listOf(36), allocations)
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun equalContentWithDifferentIdentityIsDeduplicatedBeforeAllocationAtExactBudget() {
        val directory = temporary.newFolder()
        val allocations = mutableListOf<Int>()
        val writer = AuthoredSourceMapStore(directory).writer(36, allocations::add)
        val first = map()
        val hash = writer.reference(first)
        assertEquals(hash, writer.reference(first))
        assertEquals(hash, writer.reference(map()))
        assertEquals(listOf(36), allocations)
        assertEquals(1, directory.listFiles()!!.size)
        assertEquals(first, AuthoredSourceMapStore(directory).reader().resolve(hash))
    }

    @Test fun failedVerificationDoesNotCacheHashOrConsumeBudget() {
        val directory = temporary.newFolder()
        val store = AuthoredSourceMapStore(directory)
        val hash = store.writer().reference(map())
        val file = File(directory, "$hash.map")
        val saved = file.readBytes()
        file.writeBytes(saved.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
        val writer = store.writer(36)
        repeat(2) { assertThrows(IllegalArgumentException::class.java) { writer.reference(map()) } }
        file.writeBytes(saved)
        assertEquals(hash, writer.reference(map()))
    }

    @Test fun allocationFailureRollsBackReservationBeforeRetry() {
        val directory = temporary.newFolder()
        var attempts = 0
        val writer = AuthoredSourceMapStore(directory).writer(36) {
            if (attempts++ == 0) throw IOException("injected before payload allocation")
        }
        assertThrows(IOException::class.java) { writer.reference(map()) }
        val hash = writer.reference(map())
        assertEquals(map(), AuthoredSourceMapStore(directory).reader().resolve(hash))
    }
}
