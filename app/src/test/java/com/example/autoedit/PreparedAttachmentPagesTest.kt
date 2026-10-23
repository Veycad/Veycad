package com.veycad.app

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreparedAttachmentPagesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exactFloatBitsRemainDetachedAcrossPageEvictionAndReverseReads() {
        val pages = PreparedAttachmentPages(temporary.root)
        val values = FloatArray(40_000) { if (it == 3) -0.0f else it / 40_001f }
        val expected = values.copyOf()
        val plane = pages.append(values.size) { values[it] }
        pages.seal()
        values.fill(1f)
        for (index in listOf(3, 39_999, 0, 20_000, 3, 100, 39_999)) assertEquals(expected[index].toRawBits(), plane[index].toRawBits())
        assertEquals(values.size, plane.size)
        assertEquals(2 * PreparedAttachmentPages.PAGE_BYTES, pages.residentPageBytes)
        pages.close(); pages.close()
        assertThrows(IllegalStateException::class.java) { plane[0] }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun corruptedAndMissingSpillFilesNeverBecomeAcceptedPixels() {
        for (missing in listOf(false, true)) {
            val folder = temporary.newFolder()
            val pages = PreparedAttachmentPages(folder)
            val plane = pages.append(40_000) { it / 40_001f }
            pages.seal()
            val file = folder.listFiles()!!.single()
            var removed = false
            if (missing) {
                try { Files.delete(file.toPath()); removed = true }
                catch (_: java.nio.file.FileSystemException) {
                    // Windows protects a live RandomAccessFile from deletion. Verify that actual
                    // protection and intact pixels, then require owned close to invalidate them.
                    assertTrue(file.isFile)
                    assertEquals((1 / 40_001f).toRawBits(), plane[1].toRawBits())
                }
            } else java.io.RandomAccessFile(file, "rw").use { it.seek(4); it.writeInt(0) }
            if (!missing || removed) assertThrows(IllegalStateException::class.java) { plane[1] }
            pages.close()
            assertFalse(file.exists())
            assertThrows(IllegalStateException::class.java) { plane[1] }
        }
    }

    @Test fun disposalFailureRetainsRetryableOwnerAndInvalidatesBorrowedViews() {
        var reject = true
        val pages = PreparedAttachmentPages(temporary.root, deleteFile = { file ->
            if (reject) error("injected delete failure") else Files.deleteIfExists(file.toPath())
        })
        val plane = pages.append(2) { .5f }
        pages.seal()
        val budget = PreviewMemoryBudget()
        val charge = PreparedAttachmentPages.reservationBytes(8)
        assertTrue(budget.reserve("pages", charge, pages::close))
        budget.pin("pages")
        assertThrows(IllegalStateException::class.java) { budget.release("pages") }
        assertEquals(charge, budget.usedBytes)
        assertThrows(IllegalStateException::class.java) { plane[0] }
        reject = false
        budget.release("pages")
        assertEquals(0L, budget.usedBytes)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }
}
