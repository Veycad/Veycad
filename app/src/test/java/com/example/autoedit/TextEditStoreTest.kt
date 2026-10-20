package com.veycad.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Properties

class TextEditStoreTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun unreadableDraftNeverFallsBackToAnEmptyReplacement() {
        for (brokenField in listOf("version", "l.0.style.font", "duration", "identity")) {
            val source=folder.newFile().apply { writeText("source") }
            val directory=folder.newFolder()
            val store=TextEditStore(directory)
            val original=TextEditProject(source.path,2_000_000,160,240,
                layers=listOf(TextLayer("saved-title","Мой сохранённый заголовок",0,1_000_000)))
            store.save(original)
            val file=directory.listFiles()!!.single()
            val properties=Properties().apply { file.inputStream().use { load(it) } }
            if (brokenField=="duration") properties.remove(brokenField)
            else properties.setProperty(brokenField,"future-or-damaged")
            file.outputStream().use { properties.store(it,"unreadable draft") }
            val unreadableBytes=file.readBytes()
            var fallbackCreated=false
            // Same load-or-create boundary used when opening/importing a video.
            val failure=assertThrows(IllegalStateException::class.java) {
                val loaded=store.load(source.path) ?: original.copy(layers=emptyList()).also { fallbackCreated=true }
                store.save(loaded)
            }
            assertTrue(failure.message!!.contains("черновик"))
            assertFalse(failure.message!!.contains(source.path))
            assertFalse(fallbackCreated)
            assertArrayEquals(unreadableBytes,file.readBytes())
        }
    }
    @Test fun malformedFlagsNeverSilentlyRemoveReviewOrPlateState() {
        for (brokenField in listOf("edited", "captionStyle.plate", "c.0.reviewed", "c.0.calibrated")) {
            val source=folder.newFile().apply { writeText("source") }
            val directory=folder.newFolder()
            val store=TextEditStore(directory)
            val original=TextEditProject(source.path,2_000_000,160,240,
                captions=listOf(CaptionCue("cue","Проверенная фраза",0,1_000_000,
                    confidence=.8,manuallyReviewed=true,confidenceCalibrated=true)),captionsEdited=true)
            store.save(original)
            val file=directory.listFiles()!!.single()
            val properties=Properties().apply { file.inputStream().use { load(it) } }
            properties.setProperty(brokenField,"not-a-boolean")
            file.outputStream().use { properties.store(it,"invalid flag") }
            val unreadableBytes=file.readBytes()
            assertThrows(IllegalStateException::class.java) { store.load(source.path) }
            assertArrayEquals(unreadableBytes,file.readBytes())
        }
    }
    @Test fun publishedResultLinkSurvivesEditingAndStoreRecreation() {
        val source=folder.newFile("linked.mp4").apply { writeText("source") }
        val result=folder.newFile("result.mp4").apply { writeText("finished") }
        val directory=folder.newFolder("linked-draft")
        val project=TextEditProject(source.path,2_000_000,160,240)
        TextEditStore(directory).save(project,result.path)
        val restored=TextEditStore(directory)
        restored.save(project.copy(language="ru"))
        assertEquals(result.path,restored.resultPath(source.path))
        source.appendText("replaced")
        assertNull(restored.resultPath(source.path))
    }
    @Test fun restoresCorrectionsAndCyrillicNewlines() {
        val source = folder.newFile("source.mp4").apply { writeText("fixture") }
        val store = TextEditStore(folder.newFolder("drafts"))
        val project = TextEditProject(source.path, 5_000_000, 720, 1280,
            layers = listOf(TextLayer("a", "Как сделать X\nПример", 0, 3_000_000)),
            captions = listOf(CaptionCue("b", "Исправленная фраза", 1_200_000, 2_100_000)))
        store.save(project)
        assertEquals(project, store.load(source.path))
        assertNull(store.load(folder.newFile("other.mp4").path))
        source.appendText("changed")
        assertNull(store.load(source.path))
    }
    @Test fun latestSaveReplacesDraftWithoutPartialFile() {
        val source = folder.newFile("v.mp4").apply { writeText("v") }
        val directory = folder.newFolder("draft")
        val store = TextEditStore(directory)
        val p = TextEditProject(source.path, 2_000_000, 720, 1280)
        store.save(p)
        store.save(p.copy(captions = listOf(CaptionCue("c", "Новое", 0, 1_000_000))))
        assertEquals("Новое", store.load(source.path)!!.captions.single().text)
        assertFalse(directory.listFiles()!!.any { it.name.endsWith(".partial") })
    }
}
