package com.veycad.app

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class RenderWorkspaceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun source_and_pending_renders_are_never_placed_in_quota_managed_cache() {
        val root = temporary.root
        val files = root.resolve("files").apply { mkdirs() }
        val cache = root.resolve("cache").apply { mkdirs() }

        val source = RenderWorkspace.sourceDraftDirectory(files).resolve("source.mp4")
        val pending = RenderWorkspace.pendingRendersDirectory(files).resolve("candidate.mp4")
        val scratch = RenderWorkspace.transientImportsDirectory(cache).resolve("picker.tmp")
        source.writeBytes(byteArrayOf(1))
        pending.writeBytes(byteArrayOf(2))
        scratch.writeBytes(byteArrayOf(3))

        assertEquals(files.resolve("source-draft/source.mp4"), source)
        assertEquals(files.resolve("pending-renders/candidate.mp4"), pending)
        assertEquals(cache.resolve("imports/picker.tmp"), scratch)
        val unrelated = cache.resolve("other-cache.bin").apply { writeBytes(byteArrayOf(4)) }

        RenderWorkspace.clearTransient(files, cache)

        assertTrue(source.isFile)
        assertFalse(pending.exists())
        assertFalse(scratch.exists())
        assertTrue(unrelated.isFile)
        RenderWorkspace.clearSourceDraft(files)
        assertFalse(source.exists())
        assertTrue(unrelated.isFile)
    }

    @Test fun file_provider_exposes_durable_pending_render_directory() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.File("src/main/res/xml/file_paths.xml"))
        val entries = document.documentElement.childNodes
        for ((name, path) in mapOf("pending_renders" to "pending-renders/",
            "completed_renders" to "completed-renders/")) {
            val matching = (0 until entries.length).map(entries::item)
                .filter { it.attributes?.getNamedItem("name")?.nodeValue == name }
            assertEquals("$name must have exactly one mapping", 1, matching.size)
            assertEquals("files-path", matching.single().nodeName)
            assertEquals(path, matching.single().attributes.getNamedItem("path").nodeValue)
        }
    }
}
