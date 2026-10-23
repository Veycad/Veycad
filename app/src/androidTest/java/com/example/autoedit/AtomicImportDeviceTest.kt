package com.veycad.app

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtomicImportDeviceTest {
    @get:Rule val ui = UiTestFixtureRule()
    private val app get() = ui.context.applicationContext as UiTestApplication
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun snapshot() = requireNotNull(SourceDraftStore(ui.context.filesDir).read())
    private fun names() = snapshot().entries.map { it.displayName }
    private fun assertIdle() = assertEquals(MediaWorkCoordinator.Snapshot(0, false), MediaWorkCoordinator.process.snapshot())
    private fun corruptSecondUri() = ui.videoUri("bad-second.mp4").buildUpon().appendQueryParameter("failAfter", "64").build()

    @Test fun successfulRestoreKeepsInterruptedWorkWarning() {
        ui.launch(); ui.importVideos("preserved.mp4")
        val old = snapshot(); val sources = ui.selectedSources().associateWith(File::readBytes)
        main {
            app.resetSession()
            check(ui.context.getSharedPreferences("edit_session", 0).edit().putBoolean("running", true).commit())
        }
        ui.recreate()
        val message = "Работа была прервана системой. Исходники и предыдущий монтаж сохранены; можно повторить."
        ui.awaitText(R.id.renderHint, message)
        assertEquals(message, app.editSession.state.value!!.error)
        assertEquals(old, snapshot())
        sources.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        onView(withId(R.id.renderButton)).check(matches(isEnabled())); assertIdle()
    }

    @Test fun malformedSecondVideoCannotReplaceConfirmedPair() {
        ui.launch(); ui.selectStyle("DUALITY"); ui.importVideos("old0.mp4", "old1.mp4")
        val old = snapshot(); val sources = ui.selectedSources().associateWith(File::readBytes)
        File(ui.context.getDir("ui-imports", 0), "not-video.mp4").writeText("not a video")
        ui.returnVideos(listOf(ui.videoUri("valid.mp4"), ui.videoUri("not-video.mp4"))); ui.awaitIdle()
        assertEquals(old, snapshot())
        assertNotNull(app.editSession.state.value!!.error)
        assertTrue(app.editSession.state.value!!.sourceSelectionConfirmed)
        sources.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        assertEquals(listOf("old0.mp4", "old1.mp4"), app.editSession.state.value!!.draft!!.names)
        onView(withId(R.id.renderButton)).check(matches(isEnabled())); assertIdle()
    }

    @Test fun rejectedImportRetainsOwnerDraft() {
        ui.launch(); ui.importVideos("old.mp4")
        val old = app.editSession.state.value!!.draft!!
        ui.returnVideos(listOf(ui.videoUri("bad.mp4").buildUpon().appendPath("absent.mp4").build())); ui.awaitIdle()
        assertEquals(old.files, app.editSession.state.value!!.draft?.files)
        assertEquals(old.names, app.editSession.state.value!!.draft?.names)
    }

    @Test fun secondInputFailurePreservesReadyDraftAndEntry() {
        ui.launch(); ui.selectStyle("DUALITY"); ui.importVideos("old0.mp4", "old1.mp4")
        val previous = snapshot(); val files = ui.selectedSources(); val bytes = files.map(File::readBytes)
        val entry = app.editSession.state.value!!.entry
        ui.returnVideos(listOf(ui.videoUri("new0.mp4"), corruptSecondUri())); ui.awaitIdle()
        assertEquals(previous, snapshot()); assertEquals(entry, app.editSession.state.value!!.entry)
        files.forEachIndexed { i, f -> assertArrayEquals(bytes[i], f.readBytes()) }
        assertEquals(listOf("old0.mp4", "old1.mp4"), app.editSession.state.value!!.draft!!.names)
        onView(withId(R.id.renderButton)).check(matches(isEnabled())); assertIdle()
    }
    @Test fun failedReplacementAfterRenderRestoresConfirmedDraft() {
        ui.launch(); ui.importVideos("old.mp4"); val previous = snapshot()
        val sources = ui.selectedSources().associateWith(File::readBytes)
        ui.startRender(); ui.renderer.complete(); ui.awaitIdle(); ui.awaitDisplayed(R.id.resultContent)
        val retained = app.editSession.state.value!!
        assertEquals(sources.keys.map { it.canonicalFile }, retained.draft!!.files.map { it.canonicalFile })
        assertEquals(listOf("old.mp4"), retained.draft.names)
        assertTrue(retained.sourceSelectionConfirmed)
        assertEquals(previous, snapshot())
        sources.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        val entry = app.editSession.state.value!!.entry
        pressBack(); ui.awaitDisplayed(R.id.directorContent)
        val missing = ui.videoUri("missing.mp4").buildUpon().appendPath("absent.mp4").build()
        ui.returnVideos(listOf(missing)); ui.awaitIdle()
        assertEquals(previous, snapshot()); assertEquals(entry, app.editSession.state.value!!.entry)
        assertEquals(listOf("old.mp4"), app.editSession.state.value!!.draft!!.names)
        sources.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        onView(withId(R.id.renderButton)).check(matches(isEnabled())); assertIdle()
    }
    @Test fun recreationDuringReplacementShowsOldSelection() = recreationBarrier(false)
    @Test fun firstImportRecreationNeverSelectsStaging() = recreationBarrier(true)
    private fun recreationBarrier(first: Boolean) {
        ui.launch(); ui.selectStyle("DUALITY")
        if (!first) ui.importVideos("old0.mp4", "old1.mp4")
        val previous = snapshot(); val reached = CountDownLatch(1); val release = CountDownLatch(1)
        var copies = 0
        app.sourceIo = object : SourceDraftStore.Io by SourceDraftStore.DiskIo {
            override fun copyAndSync(input: InputStream, target: File) {
                if (++copies == 2) { reached.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
                SourceDraftStore.DiskIo.copyAndSync(input, target)
            }
        }
        try {
            ui.returnVideos(listOf(ui.videoUri("new0.mp4"), ui.videoUri("new1.mp4")))
            assertTrue(reached.await(10, TimeUnit.SECONDS)); ui.recreate()
            assertEquals(previous, snapshot()); assertTrue(app.editSession.state.value!!.busy)
            onView(withId(R.id.renderButton)).check(matches(not(isEnabled())))
            if (!first) onView(withId(R.id.videoSelection)).check(matches(withText("1 · old0.mp4\n2 · old1.mp4")))
            main { assertFalse(app.editSession.clearDraft()) }
        } finally { release.countDown() }
        ui.awaitIdle(); assertEquals(listOf("new0.mp4", "new1.mp4"), names())
        ui.recreate(); onView(withId(R.id.renderButton)).check(matches(isEnabled())); assertIdle()
    }
    @Test fun unreadableCommitStatusBlocksRender() {
        ui.launch(); ui.importVideos("old.mp4"); val old = ui.selectedSources().single()
        val oldBytes = old.readBytes()
        val selection = requireNotNull(app.editSession.state.value!!.draft!!.music)
        val music = selection.file
        val style = MontageStyleCatalog.restore(selection.styleId)
        assertTrue(music.isFile && music.length() > 0)
        // The immutable overload receives inspected sources; inspection stays off the main thread.
        val inspected = MediaSourceSet(listOf(MediaInputInspector().inspect("old", old, "old.mp4")))
        var committed = false
        app.sourceIo = object : SourceDraftStore.Io by SourceDraftStore.DiskIo {
            override fun replaceManifest(target: File, bytes: ByteArray) { atomicWrite(target, bytes); committed = true; throw IOException("after") }
            override fun readManifest(target: File): ByteArray {
                if (committed) throw IOException("read failed")
                return SourceDraftStore.DiskIo.readManifest(target)
            }
        }
        ui.returnVideos(listOf(ui.videoUri("new.mp4"))); ui.awaitIdle()
        assertTrue(old.exists()); assertEquals(listOf("new.mp4"), names())
        assertFalse(app.editSession.state.value!!.sourceSelectionConfirmed)
        onView(withId(R.id.renderButton)).check(matches(not(isEnabled())))
        val requestCount = ui.renderer.requestCount
        val requests = listOf<() -> Unit>(
            { app.editSession.render(old, null, music, style) },
            { app.editSession.render(listOf(old), music, style) },
            { app.editSession.render(inspected, music, style) }
        )
        requests.forEach { request ->
            main(request); ui.awaitIdle()
            assertFalse(app.editSession.state.value!!.busy)
            assertFalse(app.editSession.state.value!!.sourceSelectionConfirmed)
            assertTrue(app.editSession.state.value!!.error.orEmpty().contains("Выбор исходников не подтверждён"))
            assertEquals(requestCount, ui.renderer.requestCount)
            assertNull(ui.renderer.currentRequest)
            assertArrayEquals(oldBytes, old.readBytes())
            onView(withId(R.id.renderButton)).check(matches(not(isEnabled())))
            assertIdle()
        }
        app.sourceIo = SourceDraftStore.DiskIo
        main { app.editSession.restoreDraft(MontageStyleCatalog.restore(null)) }; ui.awaitIdle()
        assertTrue(app.editSession.state.value!!.sourceSelectionConfirmed)
        onView(withId(R.id.renderButton)).check(matches(isEnabled())); assertIdle()
    }
    @Test fun staleMarkerDoesNotHideCommittedPairOrLoseSecondSourceOnStyleSwitch() {
        ui.launch(); ui.selectStyle("DUALITY"); ui.importVideos("one.mp4", "two.mp4")
        ui.context.getSharedPreferences("source_draft", 0).edit().putBoolean("cleanup_incomplete", true).commit()
        ui.selectStyle("FEAR"); ui.recreate(); ui.awaitIdle()
        assertEquals(2, snapshot().entries.size)
        ui.selectStyle("DUALITY"); ui.awaitEnabled(R.id.renderButton)
        onView(withId(R.id.videoSelection)).check(matches(withText("1 · one.mp4\n2 · two.mp4")))
    }
    @Test fun invalidationFailureDoesNotDeleteSources() {
        ui.launch(); ui.importVideos("old.mp4"); val previous = snapshot()
        app.sourceIo = object : SourceDraftStore.Io by SourceDraftStore.DiskIo {
            override fun replaceManifest(target: File, bytes: ByteArray) { throw IOException("invalidate failed") }
        }
        main { assertTrue(app.editSession.clearDraft()) }; ui.awaitIdle()
        assertEquals(previous, snapshot()); assertNotNull(app.editSession.state.value!!.error)
        assertIdle()
    }
    @Test fun markerFailureAfterInvalidationRetainsTombstoneAndFiles() {
        ui.launch(); ui.importVideos("old.mp4")
        val old = ui.selectedSources().single(); val generation = app.editSession.state.value!!.draftGeneration
        app.failSourcePreferenceCommit = true
        main { assertTrue(app.editSession.clearDraft()) }; ui.awaitIdle()
        assertTrue(snapshot().entries.isEmpty()); assertTrue(old.exists())
        assertTrue(app.editSession.state.value!!.draftGeneration > generation)
        assertNotNull(app.editSession.state.value!!.error)
        assertNull(app.editSession.state.value!!.cleanupReport)
        app.failSourcePreferenceCommit = false
        ui.recreate(); assertTrue(old.exists())
        onView(withId(R.id.renderButton)).check(matches(not(isEnabled()))); assertIdle()
    }
    @Test fun markerFailureWithPendingGcDoesNotResumeDeletionOnRecovery() {
        ui.launch()
        val root = File(ui.context.filesDir, "source-draft")
        val outside = File(ui.context.filesDir, "keep.bin").apply { writeText("keep") }
        val orphan = File(root, "source-0-${java.util.UUID.randomUUID()}.mp4")
        Files.createSymbolicLink(orphan.toPath(), outside.toPath())
        ui.importVideos("old.mp4"); val old = ui.selectedSources().single()
        app.failSourcePreferenceCommit = true
        main { assertTrue(app.editSession.clearDraft()) }; ui.awaitIdle()
        assertTrue(old.exists()); app.failSourcePreferenceCommit = false
        ui.recreate(); ui.awaitIdle(); assertTrue(old.exists()); assertEquals("keep", outside.readText())
        orphan.delete()
    }
    @Test fun uncertainInvalidationPreservesBytesAndBlocksRender() {
        ui.launch(); ui.importVideos("old.mp4"); val old = ui.selectedSources().single()
        var attempted = false
        app.sourceIo = object : SourceDraftStore.Io by SourceDraftStore.DiskIo {
            override fun replaceManifest(target: File, bytes: ByteArray) { atomicWrite(target, bytes); attempted = true; throw IOException("after invalidation") }
            override fun readManifest(target: File): ByteArray {
                if (attempted) throw IOException("read failed")
                return SourceDraftStore.DiskIo.readManifest(target)
            }
        }
        main { assertTrue(app.editSession.clearDraft()) }; ui.awaitIdle()
        assertTrue(old.exists()); assertTrue(snapshot().entries.isEmpty())
        assertFalse(app.editSession.state.value!!.sourceSelectionConfirmed)
        onView(withId(R.id.renderButton)).check(matches(not(isEnabled()))); assertIdle()
    }
    @Test fun mirrorAndCollectionFailureDoNotRollbackCommit() {
        ui.launch(); ui.importVideos("old.mp4")
        val old = ui.selectedSources().single(); val outside = File(ui.context.filesDir, "outside.bin").apply { writeText("keep") }
        val orphan = File(old.parentFile, "source-0-${java.util.UUID.randomUUID()}.mp4")
        Files.createSymbolicLink(orphan.toPath(), outside.toPath())
        app.failSourcePreferenceCommit = true
        ui.returnVideos(listOf(ui.videoUri("new.mp4"))); ui.awaitIdle()
        assertEquals(listOf("new.mp4"), names()); assertTrue(ui.selectedSources().single().exists())
        assertTrue(app.editSession.state.value!!.sourceSelectionConfirmed)
        assertNotNull(app.editSession.state.value!!.error)
        assertEquals("keep", outside.readText()); onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        app.failSourcePreferenceCommit = false; orphan.delete(); assertIdle()
    }
    @Test fun observerFailureAfterCommitPreservesSelection() {
        ui.launch(); ui.importVideos("old.mp4")
        var hit = false
        val delivered = mutableListOf<List<String>>()
        val observer = androidx.lifecycle.Observer<EditSession.State> { value ->
            if (!value.busy && value.draft?.names == listOf("new.mp4")) { hit = true; throw IllegalArgumentException("observer") }
        }
        val healthy = androidx.lifecycle.Observer<EditSession.State> { value ->
            if (!value.busy) value.draft?.names?.let { delivered += it }
        }
        main {
            app.editSession.state.observeForever(observer)
            app.editSession.state.observeForever(healthy)
        }
        try {
            ui.returnVideos(listOf(ui.videoUri("new.mp4"))); ui.awaitIdle()
            assertTrue(hit); assertEquals(listOf("new.mp4"), names())
            assertTrue(delivered.contains(listOf("new.mp4")))
            assertTrue(app.editSession.state.value!!.sourceSelectionConfirmed); assertIdle()
            ui.returnVideos(listOf(ui.videoUri("later.mp4"))); ui.awaitIdle()
            assertTrue(delivered.contains(listOf("later.mp4")))
            onView(withId(R.id.videoSelection)).check(matches(withText("later.mp4")))
            onView(withId(R.id.renderButton)).check(matches(isEnabled()))
            ui.recreate()
            onView(withId(R.id.videoSelection)).check(matches(withText("later.mp4")))
            onView(withId(R.id.renderButton)).check(matches(isEnabled())); assertIdle()
        } finally { main {
            app.editSession.state.removeObserver(observer)
            app.editSession.state.removeObserver(healthy)
        } }
    }
    @Test fun observerIsolationKeepsLifecycleAndRemovalSemantics() {
        main {
            class Owner : androidx.lifecycle.LifecycleOwner {
                val registry = androidx.lifecycle.LifecycleRegistry(this)
                override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
            }
            var failures = 0
            val live = ObserverSafeLiveData(0) { failures++; throw IOException("diagnostics failure") }
            val delivered = mutableListOf<Int>()
            val bad = androidx.lifecycle.Observer<Int> { if (it == 1) throw IllegalArgumentException("observer") }
            val healthy = androidx.lifecycle.Observer<Int> { delivered += it }
            val owner = Owner().apply { registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
            live.observe(owner, bad)
            live.observe(owner, bad) // The same registration is idempotent.
            live.observeForever(healthy)
            assertThrows(IllegalArgumentException::class.java) { live.observeForever(bad) }
            owner.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED
            live.value = 1
            assertEquals(1, failures); assertEquals(listOf(0, 1), delivered)
            owner.registry.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED
            // Destruction must release the original registration, including the wrapper map.
            live.observe(owner, bad) // A destroyed owner is ignored.
            live.observeForever(bad)
            assertEquals(2, failures)
            live.removeObserver(bad)
            live.value = 2
            assertEquals(listOf(0, 1, 2), delivered)
            live.removeObserver(healthy)
            assertFalse(live.hasObservers())
            val nextOwner = Owner().apply { registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED }
            live.observe(nextOwner, bad)
            live.removeObservers(nextOwner)
            assertFalse(live.hasObservers())
            live.observeForever(bad)
            live.removeObserver(bad)
            assertFalse(live.hasObservers())
        }
    }
    @Test fun ambiguousLegacyRemainsOnDiskUntilReselection() {
        val root = File(ui.context.filesDir, "source-draft").apply { mkdirs() }
        val old = File(root, "source-0-${java.util.UUID.randomUUID()}.mp4").apply { writeText("old") }
        val other = File(root, "source-1-${java.util.UUID.randomUUID()}.mp4").apply { writeText("other") }
        ui.launch(); ui.awaitIdle()
        assertTrue(snapshot().entries.isEmpty()); assertTrue(old.exists()); assertTrue(other.exists())
        ui.recreate(); assertTrue(old.exists()); assertTrue(other.exists())
        ui.importVideos("new.mp4"); assertFalse(old.exists()); assertFalse(other.exists())
    }
    @Test fun musicFailureAndBusyCleanupPreserveSelection() {
        ui.launch(); ui.importVideos("old.mp4"); val old = snapshot()
        app.musicSelector = { throw IOException("music preparation") }
        ui.returnVideos(listOf(ui.videoUri("new.mp4"))); ui.awaitIdle()
        assertEquals(old, snapshot()); onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        MediaWorkCoordinator.process.tryBeginCleanup()!!.use {
            main { app.editSession.importVideos(listOf(ui.videoUri("later.mp4")), MontageStyleCatalog.heartbeat) }
            assertEquals(old, snapshot()); assertFalse(app.editSession.state.value!!.busy)
        }
        app.musicSelector = null; assertIdle()
    }
    @Test fun sourceAndRootSymlinksAreRejectedWithoutTouchingOutsideBytes() {
        ui.launch(); ui.importVideos("old.mp4")
        val file = ui.selectedSources().single(); val outside = File(ui.context.filesDir, "outside.mp4").apply { writeBytes(file.readBytes()) }
        val bytes = outside.readBytes(); file.delete(); Files.createSymbolicLink(file.toPath(), outside.toPath())
        assertThrows(IOException::class.java) { SourceDraftStore(ui.context.filesDir).read() }
        MediaWorkCoordinator.process.tryBeginCleanup()!!.use { assertFalse(SourceDraftStore(ui.context.filesDir).collectObsolete(it).isEmpty()) }
        assertArrayEquals(bytes, outside.readBytes())
        file.delete()
        val root = file.parentFile!!; root.listFiles()!!.forEach { it.delete() }; root.delete()
        val elsewhere = File(ui.context.filesDir, "outside-root").apply { mkdir() }
        Files.createSymbolicLink(root.toPath(), elsewhere.toPath())
        assertThrows(IOException::class.java) { SourceDraftStore(ui.context.filesDir).read() }
        root.delete(); assertTrue(elsewhere.isDirectory)
    }
}
