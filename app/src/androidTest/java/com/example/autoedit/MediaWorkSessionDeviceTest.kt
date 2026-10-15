package com.veycad.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.camera.video.Recorder
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Intent
import android.media.MediaMetadataRetriever
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaWorkSessionDeviceTest {
    @get:Rule val ui = UiTestFixtureRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun source() = SyntheticVideo.create(File(ui.context.filesDir, "text-sources/fixture.mp4"), 2000)
    private fun idle(owner: MediaWorkCoordinator = MediaWorkCoordinator.process) {
        ui.waitUntil("all media leases released", 30_000) {
            owner.snapshot() == MediaWorkCoordinator.Snapshot(0, false)
        }
    }

    @Test fun dialogConfirmationRechecksAdmission() {
        ui.launch()
        ui.importVideos("protected-source.mp4")
        val source = File(ui.context.filesDir, "source-draft").listFiles()!!.first { it.extension == "mp4" }
        val bytes = source.readBytes()
        ui.click(R.id.settingsButton)
        ui.click(R.id.clearCacheButton)
        requireNotNull(MediaWorkCoordinator.process.tryBeginWork()).use {
            ui.click(android.R.id.button1)
            assertArrayEquals(bytes, source.readBytes())
            assertFalse(ui.context.getSharedPreferences("source_draft", 0).all.isEmpty())
        }
    }

    @Test fun cleanupPreservesUnknownAndProviderFiles() {
        ui.launch()
        val private = listOf("unknown/keep", "multiclass-decoded-frames/frame.argb", "exported-report.json")
            .map { File(ui.context.cacheDir, it).apply { parentFile!!.mkdirs(); writeText("preserve") } }
        ui.click(R.id.settingsButton)
        ui.click(R.id.clearCacheButton)
        ui.click(android.R.id.button1)
        ui.awaitIdle()
        private.forEach { assertEquals("preserve", it.readText()) }
    }

    @Test fun queuedWorkAlreadyBlocksCleanup() {
        ui.launch()
        val file = source()
        val owner = MediaWorkCoordinator()
        val queue = ManualExecutor()
        lateinit var session: TextEditSession
        main { session = TextEditSession(ui.context, coordinator = owner, worker = queue); session.open(file) }
        assertNull(owner.tryBeginCleanup())
        queue.next()
        idle(owner)
        assertNotNull(session.state.value?.project)
        main { session.close() }
    }

    @Test fun rejectedExecutorRestoresStateAndReleasesOwnLease() {
        ui.launch()
        val file = source()
        val owner = MediaWorkCoordinator()
        val executor = Executors.newSingleThreadExecutor().apply { shutdown() }
        lateinit var session: TextEditSession
        main { session = TextEditSession(ui.context, coordinator = owner, worker = executor); session.open(file) }
        assertFalse(session.state.value!!.busy)
        assertFalse(ui.context.getSharedPreferences("text_session", 0).getBoolean("running", false))
        idle(owner)
        assertTrue(file.isFile)
    }

    @Test fun cancelKeepsProtectionUntilWorkerExits() {
        ui.launch()
        val file = source()
        val owner = MediaWorkCoordinator()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var session: TextEditSession
        main { session = TextEditSession(ui.context, coordinator = owner,
            exportEngine = TextEditSession.ExportEngine { _, _, _, cancel, _ ->
                entered.countDown(); check(release.await(30, TimeUnit.SECONDS)); cancel()
            }); session.open(file) }
        ui.waitUntil("source opened") { session.state.value?.project != null && session.state.value?.busy == false }
        try {
            main { session.export() }
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            main { session.cancel() }
            assertNull(owner.tryBeginCleanup())
        } finally { release.countDown() }
        idle(owner)
        assertFalse(session.state.value!!.busy)
        assertTrue(file.isFile)
        main { session.close() }
    }

    @Test fun staleCameraGenerationCannotReleaseNewRecording() {
        ui.launch()
        val owner = MediaWorkCoordinator()
        val queue = ManualExecutor()
        lateinit var camera: CaptureRecordingSession
        val recorder = Recorder.Builder().build()
        val script = requireNotNull(CaptureScript.forStyle("heartbeat"))
        main {
            camera = CaptureRecordingSession(ui.context, owner, queue)
            camera.start(recorder, script, CaptureMode.MUSIC, false, 0)
            camera.stop()
            camera.start(recorder, script, CaptureMode.MUSIC, false, 0)
        }
        queue.next()
        instrumentation.waitForIdleSync()
        assertTrue(camera.state.value!!.busy)
        assertNull(owner.tryBeginCleanup())
        main { camera.stop() }
        queue.next()
        idle(owner)
        queue.shutdown()
    }

    @Test fun observerFailureDoesNotLeakCaptureLease() {
        ui.launch()
        val owner = MediaWorkCoordinator()
        val queue = ManualExecutor()
        lateinit var camera: CaptureRecordingSession
        val observer = androidx.lifecycle.Observer<CaptureRecordingSession.State> {
            if (it.phase == CaptureRecordingSession.Phase.IDLE) error("terminal observer")
        }
        main {
            camera = CaptureRecordingSession(ui.context, owner, queue)
            camera.start(Recorder.Builder().build(), requireNotNull(CaptureScript.forStyle("heartbeat")),
                CaptureMode.MUSIC, false, 0)
            camera.state.observeForever(observer)
            try { assertThrows(IllegalStateException::class.java) { camera.stop() } }
            finally { camera.state.removeObserver(observer) }
        }
        queue.next()
        idle(owner)
        queue.shutdown()
    }

    @Test fun speechCancellationKeepsFilesProtectedUntilTranscriberExits() {
        ui.launch()
        val owner = MediaWorkCoordinator()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val file = source()
        val speech = object : SpeechTranscriber {
            override fun transcribe(context: android.content.Context, source: File, language: String,
                checkCancelled: () -> Unit, onProgress: (Int) -> Unit): List<CaptionCue> {
                entered.countDown(); check(release.await(30, TimeUnit.SECONDS)); checkCancelled()
                return emptyList()
            }
            override fun transcribePcm(context: android.content.Context, pcm: PcmFile, language: String,
                checkCancelled: () -> Unit, onProgress: (Int) -> Unit): List<CaptionCue> = error("not used")
        }
        lateinit var session: TextEditSession
        main { session = TextEditSession(ui.context, coordinator = owner, transcriber = speech); session.open(file) }
        idle(owner)
        try {
            main { session.transcribe() }
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            main { session.cancel() }
            assertNull(owner.tryBeginCleanup())
            assertTrue(file.isFile)
        } finally { release.countDown() }
        idle(owner)
        assertFalse(session.state.value!!.busy)
        main { session.close() }
    }

    @Test fun newWorkRejectedWhileCleanupOwnsLease() {
        ui.launch()
        ui.importVideos("preserved.mp4")
        val session = EditSession.get(ui.context)
        val previous = session.state.value!!.draft
        requireNotNull(MediaWorkCoordinator.process.tryBeginCleanup()).use {
            main {
                assertFalse(session.clearDraft())
                session.importVideos(emptyList(), MontageStyleCatalog.heartbeat)
            }
            assertEquals(previous, session.state.value!!.draft)
            assertFalse(session.state.value!!.busy)
            assertTrue(previous!!.files.all { it.isFile })
        }
    }

    @Test fun partialDraftIsNotRestoredAfterReopen() {
        ui.launch()
        ui.importVideos("old-selection.mp4")
        val keep = File(ui.context.filesDir, "text-sources/keep.bin").apply { parentFile!!.mkdirs(); writeText("keep") }
        val link = File(ui.context.filesDir, "source-draft/protected-link")
        android.system.Os.symlink(keep.path, link.path)
        ui.click(R.id.settingsButton); ui.click(R.id.clearCacheButton); ui.click(android.R.id.button1)
        ui.awaitIdle(); idle()
        assertTrue(ui.context.getSharedPreferences("source_draft", 0).getBoolean("cleanup_incomplete", false))
        assertNotNull(EditSession.get(ui.context).state.value!!.error)
        ui.recreate()
        assertNull(EditSession.get(ui.context).state.value!!.draft)
        assertEquals("keep", keep.readText())
        assertTrue(link.exists())
    }

    @Test fun realTextExportRejectsConcurrentCleanupAndFinishes() {
        ui.launch()
        val app = ui.context.applicationContext as UiTestApplication
        val file = source()
        val original = file.readBytes()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = AtomicBoolean()
        app.textExportEngine = TextEditSession.ExportEngine { context, project, output, cancel, progress ->
            TextEditSession.NATIVE_EXPORT_ENGINE.export(context, project, output, cancel) { value ->
                progress(value)
                if (value in 10..84 && blocked.compareAndSet(false, true)) {
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                }
            }
        }
        ui.scenario.onActivity { it.startActivity(Intent(it, TextEditActivity::class.java)
            .putExtra(TextEditActivity.EXTRA_SOURCE, file.path)) }
        val session = TextEditSession.get(ui.context)
        ui.waitUntil("text source opened") { session.state.value?.project != null && session.state.value?.busy == false }
        try {
            main { session.export() }
            assertTrue("Real renderer reached frame progress", entered.await(15, TimeUnit.SECONDS))
            onView(withText("‹  Назад")).perform(click())
            ui.recreate()
            ui.click(R.id.settingsButton); ui.click(R.id.clearCacheButton)
            assertArrayEquals(original, file.readBytes())
            assertTrue(session.state.value!!.busy)
            assertTrue(File(ui.context.cacheDir, "text-work").listFiles().orEmpty().isNotEmpty())
        } finally {
            release.countDown()
            ui.waitUntil("renderer exits before fixture teardown", 30_000) { !session.state.value!!.busy }
            idle()
        }
        ui.waitUntil("real text export finished", 30_000) { !session.state.value!!.busy }
        idle()
        val entry = requireNotNull(session.state.value!!.entry) { session.state.value!!.error.orEmpty() }
        val bytes = entry.file.readBytes()
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(entry.file.path)
            val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertTrue("MP4 duration $duration ms", kotlin.math.abs(duration - 2000L) < 100L)
            assertEquals("yes", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
            assertEquals("yes", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            for (time in listOf(0L, 1_900_000L)) {
                val frame = metadata.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)
                assertNotNull(frame); frame!!.recycle()
            }
        } finally { metadata.release() }
        ui.click(R.id.clearCacheButton); ui.click(android.R.id.button1); ui.awaitIdle(); idle()
        assertArrayEquals(bytes, entry.file.readBytes())
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        File(ui.context.getExternalFilesDir(null), "cleanup-native-evidence.txt").writeText(
            "native_export=true\nfirst_last_frames_decoded=true\naudio_video_tracks=true\nduration_error_ms<100\n" +
                "cleanup_during_export=rejected\ncompleted_mp4_preserved=true\nsha256=$hash\nbytes=${bytes.size}\n")
    }

    private class ManualExecutor : AbstractExecutorService() {
        private var stopped = false
        private val jobs = LinkedBlockingQueue<Runnable>()
        override fun execute(command: Runnable) {
            if (stopped) throw java.util.concurrent.RejectedExecutionException()
            jobs.add(command)
        }
        fun next() = checkNotNull(jobs.poll(5, TimeUnit.SECONDS)).run()
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && jobs.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
}
