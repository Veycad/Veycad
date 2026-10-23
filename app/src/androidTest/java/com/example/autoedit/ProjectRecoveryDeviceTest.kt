package com.veycad.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.hamcrest.Description
import org.hamcrest.Matchers.`is`
import org.hamcrest.TypeSafeMatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.json.JSONObject

/** Real native editor/export and provider streams. Picker selection alone is controlled. */
class ProjectRecoveryDeviceTest {
    @get:Rule val fixture = UiTestFixtureRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val store get() = HybridProjectStore(fixture.context.filesDir)

    @After fun stopOwnersBeforeFixtureCleanup() {
        instrumentation.runOnMainSync {
            listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED)
                .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
                .distinct().filter { it is ManualMvpActivity || it is BackupRecoveryActivity }.forEach { it.finish() }
        }
        awaitOwnersStopped()
    }

    @Test fun portableBackupSharesVerifiedArchiveAndRestoresSeparatePlayableProject() {
        openImported()
        val originalId = store.list().single().id
        export(originalId)
        val original = store.load(originalId)
        val originalFiles = inventory(store.directory(originalId))
        val movie = File(fixture.context.filesDir, "completed-renders/${original.exports.single().fileName}")
        val movieHash = hash(movie)
        val archive = File(fixture.context.filesDir, "completed-renders/recovery-fixture.zip")
        val uri = FileProvider.getUriForFile(fixture.context, "${fixture.context.packageName}.files", archive)
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().apply { data = uri }))
        tap("backup-project")
        fixture.waitUntil("backup admission after native stop", 30_000) { view<Button>("backup-save")?.isEnabled == true }
        tap("backup-save")
        fixture.waitUntil("portable archive saved", 60_000) {
            view<TextView>("backup-status")?.text?.startsWith("Копия сохранена:") == true
        }
        ZipFile(archive).use { zip ->
            val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
            assertEquals(originalId, manifest.getString("projectId"))
            val files = manifest.getJSONArray("files")
            val names = (0 until files.length()).map { files.getJSONObject(it).getString("path") }
            assertEquals(names.size, names.distinct().size)
            assertEquals(names.toSet() + "manifest.json", zip.entries().asSequence().map { it.name }.toSet())
            for (index in 0 until files.length()) {
                val entry = files.getJSONObject(index)
                val bytes = zip.getInputStream(zip.getEntry(entry.getString("path"))).use { it.readBytes() }
                assertEquals(entry.getLong("bytes"), bytes.size.toLong())
                assertEquals(entry.getString("sha256"), digest(bytes))
            }
        }
        assertEquals(originalFiles, inventory(store.directory(originalId)))
        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        tap("backup-share")
        val chooser = Intents.getIntents().last { it.action == Intent.ACTION_CHOOSER }
        @Suppress("DEPRECATION") val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        @Suppress("DEPRECATION") val shared = requireNotNull(send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        assertEquals("application/zip", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertArrayEquals(archive.readBytes(), fixture.context.contentResolver.openInputStream(shared)!!.use { it.readBytes() })
        intending(documentTypes("application/zip", "application/octet-stream")).respondWith(
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().apply { data = uri }))
        tap("backup-restore")
        fixture.waitUntil("fresh project restored", 60_000) {
            view<TextView>("backup-status")?.text?.startsWith("Новый проект восстановлен:") == true
        }
        val restored = store.list().single { it.id != originalId }
        assertEquals(originalFiles, inventory(store.directory(originalId)))
        assertEquals(original.assets.map { it.contentHash }, restored.assets.map { it.contentHash })
        assertEquals(original.current, restored.current)
        restored.assets.forEach { asset ->
            assertEquals(asset.contentHash, hash(ProjectAssetStore(store.directory(restored.id)).resolve(asset)))
        }
        val recoveredMovie = File(fixture.context.filesDir, "completed-renders/${restored.exports.single().fileName}")
        assertNotEquals(movie.name, recoveredMovie.name)
        assertEquals(movieHash, hash(recoveredMovie))
        MediaMetadataRetriever().use { metadata ->
            metadata.setDataSource(recoveredMovie.path)
            assertTrue(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 0)
            assertEquals(restored.exports.single().settings.width.toString(), metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
        }
        tap("backup-done")
        fixture.waitUntil("recovered project real preview", 45_000) { ready() }
        assertEquals(restored.id, field<String>(currentOnMain(), "projectId"))
        assertEquals(movieHash, hash(movie))
    }

    @Test fun explicitPressureReleasesActualPreviewAndRetryPreservesProjectAndClock() {
        openImported()
        val id = store.list().single().id
        tap("play")
        fixture.waitUntil("actual playback") { view<Button>("play")?.text == "Пауза" }
        val before = inventory(store.directory(id))
        val released = CountDownLatch(1)
        var shown = 0L
        lateinit var activity: ManualMvpActivity
        instrumentation.runOnMainSync {
            activity = current<ManualMvpActivity>()!!
            activity.releasePreviewForMemoryPressure { released.countDown() }
        }
        assertTrue("Actual native release ack", released.await(30, TimeUnit.SECONDS))
        awaitPreviewStopped()
        instrumentation.runOnMainSync {
            shown = field<Long>(activity, "lastShownUs")!!
            assertEquals(shown, activity.getSharedPreferences("manual_mvp", 0).getLong("position", -1))
            assertNull(field<Any>(activity, "controller"))
            assertNull(field<Any>(activity, "binding"))
            assertTrue(field<List<*>>(activity, "surfaces")!!.isEmpty())
            assertFalse(view<Button>("export")!!.isEnabled)
            assertTrue(view<Button>("retry")!!.isEnabled)
        }
        assertEquals(before, inventory(store.directory(id)))
        tap("retry")
        fixture.waitUntil("same saved frame after explicit pressure", 45_000) { ready() }
        assertEquals(shown, field<Long>(activity, "lastShownUs"))
        assertEquals(before, inventory(store.directory(id)))
        export(id)
    }

    @Test fun pressureDuringProtectedExportDoesNotInvalidateOrReleaseItsOwner() {
        openImported()
        val id = store.list().single().id
        val activity = currentOnMain()
        val controller = field<DraftPreviewController>(activity, "controller")!!
        val worker = field<java.util.concurrent.ExecutorService>(controller, "worker")!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker.execute { entered.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            tap("export")
            val generation = field<Int>(activity, "generation")
            val acknowledged = CountDownLatch(1)
            instrumentation.runOnMainSync {
                assertTrue(field<Boolean>(activity, "exporting")!!)
                val owner = field<Any>(activity, "operationLease")
                assertNotNull(owner)
                activity.releasePreviewForMemoryPressure { acknowledged.countDown() }
                assertEquals(generation, field<Int>(activity, "generation"))
                assertSame(owner, field<Any>(activity, "operationLease"))
                assertSame(controller, field<Any>(activity, "controller"))
                assertTrue(field<Boolean>(activity, "busy")!!)
            }
            assertEquals("Busy pressure handled as NOOP, not native release", 0L, acknowledged.count)
        } finally { release.countDown() }
        fixture.waitUntil("protected export completes", 180_000) { store.load(id).exports.isNotEmpty() && ready() }
    }

    @Test fun nativeAdmissionKeepsCleanupExcludedUntilActualReleaseAndCancelledWaiterNeverRuns() {
        fixture.launch(); fixture.awaitIdle()
        var owner: ManualEditorAdmission.Lease? = null
        val admitted = CountDownLatch(1)
        instrumentation.runOnMainSync { ManualEditorAdmission.acquire { owner = it; admitted.countDown() } }
        assertTrue(admitted.await(5, TimeUnit.SECONDS))
        var cancelled: ManualEditorAdmission.Request? = null
        val forbidden = CountDownLatch(1)
        val next = CountDownLatch(1)
        var cleanup: ManualEditorAdmission.Lease? = null
        try {
            assertNull(MediaWorkCoordinator.process.tryBeginCleanup())
            instrumentation.runOnMainSync {
                cancelled = ManualEditorAdmission.acquireForCleanup { it.release(); forbidden.countDown() }
                cancelled!!.cancel()
                ManualEditorAdmission.acquireForCleanup { cleanup = it; next.countDown() }
            }
            assertEquals(1L, next.count)
            assertEquals(1L, forbidden.count)
            instrumentation.runOnMainSync { owner!!.release(); owner = null }
            assertTrue(next.await(5, TimeUnit.SECONDS))
            assertNull("Exclusive backup admission blocks new native work", MediaWorkCoordinator.process.tryBeginWork())
        } finally {
            instrumentation.runOnMainSync { cancelled?.cancel(); owner?.release(); cleanup?.release() }
        }
        requireNotNull(MediaWorkCoordinator.process.tryBeginCleanup()).close()
        val foreignCleanup = requireNotNull(MediaWorkCoordinator.process.tryBeginCleanup())
        val retried = CountDownLatch(1)
        var retriedLease: ManualEditorAdmission.Lease? = null
        var request: ManualEditorAdmission.Request? = null
        try {
            instrumentation.runOnMainSync { request = ManualEditorAdmission.acquire { retriedLease = it; retried.countDown() } }
            assertEquals(1L, retried.count)
            foreignCleanup.close()
            assertTrue("A waiting editor retries after foreign cleanup completes", retried.await(5, TimeUnit.SECONDS))
        } finally {
            foreignCleanup.close()
            instrumentation.runOnMainSync { request?.cancel(); retriedLease?.release() }
        }
    }

    private fun openImported() {
        fixture.launch(); fixture.click(R.id.manualMvpButton)
        fixture.waitUntil("manual import ready") { view<Button>("import")?.isEnabled == true }
        val video = SyntheticVideo.create(File(fixture.context.filesDir, "pending-renders/recovery-input.mp4"))
        val uri = FileProvider.getUriForFile(fixture.context, "${fixture.context.packageName}.files", video)
        intending(documentTypes("video/*")).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().apply { data = uri }))
        tap("import")
        fixture.waitUntil("real imported preview", 45_000) { ready() }
    }
    private fun export(id: String) {
        val count = store.load(id).exports.size
        tap("export")
        fixture.waitUntil("real native export published", 180_000) { store.load(id).exports.size == count + 1 && ready() }
    }
    private fun documentTypes(vararg types: String) = object : TypeSafeMatcher<Intent>() {
        override fun describeTo(description: Description) { description.appendText("OPEN_DOCUMENT ${types.toList()}") }
        override fun matchesSafely(intent: Intent) = intent.action == Intent.ACTION_OPEN_DOCUMENT &&
            intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList() == types.toList()
    }
    private inline fun <reified T : Activity> current(): T? = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<T>().singleOrNull()
    private fun currentOnMain(): ManualMvpActivity {
        var result: ManualMvpActivity? = null
        instrumentation.runOnMainSync { result = current() }; return requireNotNull(result)
    }
    private fun <T : View> view(tag: String): T? {
        var result: T? = null
        fun read() { result = current<Activity>()?.window?.decorView?.findViewWithTag(tag) }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) read() else instrumentation.runOnMainSync { read() }
        return result
    }
    private fun ready() = view<TextView>("preview-state")?.text == "Черновик · пауза" && view<Button>("play")?.isEnabled == true
    private fun tap(tag: String) = onView(withTagValue(`is`(tag))).perform(scrollTo(), click())
    @Suppress("UNCHECKED_CAST") private fun <T> field(owner: Any, name: String): T? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner) as T?
    private fun inventory(root: File) = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to hash(it) }
    private fun hash(file: File) = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(64 * 1024)
        while (true) { val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun awaitPreviewStopped() = awaitWorkers { it.startsWith("Veycad-preview-") }
    private fun awaitOwnersStopped() = awaitWorkers { it.startsWith("Veycad-preview-") || it == "Veycad-manual-jobs" || it == "Veycad-project-backup" }
    private fun awaitWorkers(matches: (String) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (Thread.getAllStackTraces().keys.any { it.isAlive && matches(it.name) }) {
            assertTrue("Actual consumers must stop before fixture cleanup", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(25)
        }
    }
}
