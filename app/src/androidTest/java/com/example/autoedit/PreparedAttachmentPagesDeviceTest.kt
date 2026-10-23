package com.veycad.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreparedAttachmentPagesDeviceTest {
    @Test fun unlinked_live_spill_is_rejected_before_any_pixel_is_accepted() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "missing-preview-pages-${System.nanoTime()}").apply { mkdirs() }
        PreparedAttachmentPages(directory).use { pages ->
            val plane = pages.append(40_000) { it / 40_001f }
            pages.seal()
            val file = directory.listFiles()!!.single()
            Files.delete(file.toPath())
            assertFalse(file.exists())
            assertThrows(IllegalStateException::class.java) { plane[1] }
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
