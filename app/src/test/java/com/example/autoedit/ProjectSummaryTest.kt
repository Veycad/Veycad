package com.veycad.app

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectSummaryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun listingThirtyProjectsNeverReadsAnalysisOrReturnsSaveableProjectsOrWritesBytes() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        for (index in 0 until 30) create(store, "saved-%02d".format(index))
        val before = inventory(files)
        var reads = 0
        val summaryStore = HybridProjectStore.observingAnalysisReads(files) {
            reads++; error("Listing must not open an analysis sidecar")
        }
        val summaries = summaryStore.listSummaries()
        assertEquals(30, summaries.size)
        assertEquals(0, reads)
        assertTrue(summaries.all { it.fps == 30 && it.currentRevisionId == 0L && it.recipeId == "recipe" })
        assertEquals("Мой монтаж", summaries.first().displayName)
        assertEquals("My song", summaries.first().musicName)
        assertEquals(before, inventory(files))
        assertThrows(IllegalStateException::class.java) { summaryStore.load("saved-00") }
        assertTrue("The same observer must see a real full-open read", reads > 0)
        assertEquals(before, inventory(files))
    }

    @Test fun corruptLargeAnalysisStillListsButActualOpenReportsRegenerationAndNeverRewritesProject() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        create(store, "project")
        val sidecars = File(store.directory("project"), "analysis").listFiles()!!.filter { it.isFile }
        assertTrue(sidecars.isNotEmpty())
        // Oversized corrupt files must not be opened even transiently by library listing.
        sidecars.forEach { java.io.RandomAccessFile(it, "rw").use { output -> output.setLength(AnalysisSidecarStore.MAX_BYTES + 1L) } }
        val before = inventory(files)
        val summary = HybridProjectStore.observingAnalysisReads(files) { error("No analysis read allowed") }.listSummaries().single()
        assertEquals("project", summary.id)
        assertEquals(0L, summary.currentRevisionId)
        val full = store.loadWithAnalysisStatus("project")
        assertTrue(full.analysisRegenerationRequired)
        assertTrue(full.missingAnalysisHashes.isNotEmpty())
        assertTrue(full.project.current.graph.frameAttachments.frames.isEmpty())
        assertEquals(before, inventory(files))
    }

    private fun create(store: HybridProjectStore, id: String) {
        val project = storageProject().copy(id = id)
        val assets = ProjectAssetStore(store.directory(id))
        project.assets.forEach { assets.resolve(it).apply { parentFile!!.mkdirs(); writeText("owned ${it.id}") } }
        store.create(project)
    }

    private fun inventory(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate { file ->
        file.relativeTo(root).invariantSeparatorsPath to file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(64 * 1024)
            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
