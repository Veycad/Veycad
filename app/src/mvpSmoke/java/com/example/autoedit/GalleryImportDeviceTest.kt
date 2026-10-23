package com.veycad.app

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Real gallery admission, canonical pointer and Neon Drift in the existing gallery-enabled APK. */
class GalleryImportDeviceTest {
    @Test fun twentyOrderedSourcesAndMusicRestoreAndTwentyOnePreservesTheCommittedBatch() {
        assertTrue(BuildConfig.GALLERY_MVP)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val style = MontageStyleCatalog.galleryMontage
        val originals = File(context.filesDir, "completed-renders/gallery-input-${UUID.randomUUID()}").apply { mkdirs() }
        val seed = SyntheticVideo.create(File(originals, "seed.mp4"), 1_000)
        val files = (0..20).map { index ->
            File(originals, "chosen-$index.mp4").also { target ->
                seed.copyTo(target)
                DataOutputStream(FileOutputStream(target, true)).use { output ->
                    val label = "gallery-$index".toByteArray()
                    output.writeInt(8 + label.size); output.writeBytes("free"); output.write(label)
                }
            }
        }
        val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
        val bytes = files.map(File::readBytes)
        val store = SourceDraftStore(context.filesDir)
        lateinit var first: EditSession
        var restored: EditSession? = null
        fun awaitIdle(session: EditSession) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 120_000
            while (true) {
                var busy = true
                instrumentation.runOnMainSync { busy = session.state.value?.busy == true }
                if (!busy && MediaWorkCoordinator.process.snapshot() == MediaWorkCoordinator.Snapshot(0, false)) return
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "Gallery import did not release its work lease" }
                android.os.SystemClock.sleep(25)
            }
        }
        instrumentation.runOnMainSync {
            first = EditSession(context)
            first.restoreDraft(style)
            first.importVideos(uris.take(20), style)
        }
        try {
            awaitIdle(first)
            val current = requireNotNull(store.read())
            assertEquals(20, current.entries.size)
            assertEquals(files.take(20).map { it.name }, current.entries.map { it.displayName })
            current.entries.forEachIndexed { index, entry ->
                assertEquals(bytes[index].size.toLong(), entry.length)
                assertArrayEquals(bytes[index], store.child(entry.fileName).readBytes())
            }
            instrumentation.runOnMainSync {
                val state = requireNotNull(first.state.value)
                assertTrue(state.sourceSelectionConfirmed); assertNull(state.error)
                val music = requireNotNull(state.draft?.music)
                assertEquals("neon_drift", music.track.id)
                assertTrue(music.file.isFile && music.file.length() > 1_000_000)
                first.close()
                restored = EditSession(context).also { it.restoreDraft(style) }
            }
            val second = requireNotNull(restored)
            awaitIdle(second)
            instrumentation.runOnMainSync {
                assertTrue(second.state.value!!.sourceSelectionConfirmed)
                assertEquals(current.entries.map { store.child(it.fileName) }, second.state.value!!.draft!!.files)
                second.importVideos(uris, style)
            }
            awaitIdle(second)
            assertEquals(current, store.read())
            assertNotNull(second.state.value!!.error)
            current.entries.forEachIndexed { index, entry -> assertArrayEquals(bytes[index], store.child(entry.fileName).readBytes()) }
            files.forEachIndexed { index, file -> assertArrayEquals(bytes[index], file.readBytes()) }
        } finally {
            instrumentation.runOnMainSync { first.cancelRender(); restored?.cancelRender() }
            awaitIdle(restored ?: first)
            instrumentation.runOnMainSync {
                if (restored != null) restored!!.close() else if (first.state.value?.busy != true) first.close()
            }
            files.forEach { it.delete() }; seed.delete(); originals.delete()
        }
    }
}
