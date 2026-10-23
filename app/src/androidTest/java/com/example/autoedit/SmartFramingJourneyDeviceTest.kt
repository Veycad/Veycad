package com.veycad.app

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.security.MessageDigest
import org.hamcrest.Matchers.`is`
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Common automatic winner -> exact project -> real SMART miss -> direct GL -> native export. */
class SmartFramingJourneyDeviceTest {
    @get:Rule val fixture = UiTestFixtureRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val store get() = HybridProjectStore(fixture.context.filesDir)

    @After fun stopNativeOwnersBeforeDeletingFixtureSources() = finishEditor()
    private fun finishEditor() {
        instrumentation.runOnMainSync {
            listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED)
                .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
                .filterIsInstance<ManualMvpActivity>().distinct().forEach { it.finish() }
        }
        fixture.waitUntil("actual native owners retired", 30_000) {
            Thread.getAllStackTraces().keys.none { it.isAlive &&
                (it.name.startsWith("Veycad-preview-") || it.name == "Veycad-manual-jobs") }
        }
    }

    @Test fun missingOrCorruptAnalysisOpensReadOnlyWithoutChangingRecoveryBytesOrRendering() {
        fixture.launch()
        fixture.awaitEnabled(R.id.manualMvpButton)
        val directory = File(fixture.context.cacheDir, "analysis-recovery-journey").apply { mkdirs() }
        val input = SyntheticVideo.create(File(directory, "input.mp4"), 1000)
        val inspected = MediaInputInspector().inspect("recovery-source", input, "Recovery source")
        val music = SourceAudioNativeFixtures.wav(File(directory, "music.wav")) { 0 }
        val graph = MontageGraph(1000, 1000, clips = listOf(MontageGraph.Clip("winner", 0, 1000, 1000,
            MontageGraph.ShotRole.ACTION, MontageGraph.Transition.HARD_CUT, MontageGraph.Motion.HOLD, 1f, 0)),
            audioTrack = MontageGraph.AudioTrack("music", 0f), frameAttachments = FrameAttachmentTimeline(listOf(
                FrameAttachments(0, mask = FrameAttachments.Plane(2, 2, FloatArray(4) { .9f }, 1f)))))
        for (corrupt in listOf(false, true)) {
            val id = CustomMusicProjectHandoff.publish(fixture.context, graph, MediaSourceSet(listOf(inspected)), music,
                ProjectExportSettings(1080, 1920, 30, 8_000_000, 192_000), recipe = MontageStyleCatalog.Recipe.HEARTBEAT)
            val projectDir = store.directory(id)
            val analysis = File(projectDir, "analysis").listFiles()!!.single()
            if (corrupt) analysis.writeBytes(analysis.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            else assertTrue(analysis.delete())
            val damaged = store.loadWithAnalysisStatus(id)
            assertTrue(damaged.analysisRegenerationRequired)
            val before = inventory(projectDir)
            fixture.scenario.onActivity { it.startActivity(Intent(it, ManualMvpActivity::class.java)
                .putExtra(ManualMvpActivity.EXTRA_PROJECT_ID, id)) }
            fixture.waitUntil("explicit read-only recovery for corrupt=$corrupt", 30_000) {
                view<TextView>("project-recovery-required") != null && view<Button>("backup-project")?.isEnabled == true
            }
            assertNull(view<Button>("export"))
            assertNull(view<Button>("framing-SMART_PERSON"))
            assertNull(view<Button>("project-audio-text"))
            lateinit var previous: ManualMvpActivity
            instrumentation.runOnMainSync {
                assertNull(field<Any>(current()!!, "controller"))
                assertNull(field<Any>(current()!!, "binding"))
                previous = current()!!; previous.recreate()
            }
            fixture.waitUntil("recreated project stays read-only", 30_000) {
                current()?.let { it !== previous } == true && view<TextView>("project-recovery-required") != null
            }
            assertEquals(before, inventory(projectDir))
            assertEquals(damaged.missingAnalysisHashes, store.loadWithAnalysisStatus(id).missingAnalysisHashes)
            assertTrue(File(fixture.context.cacheDir, "manual-renders").walkTopDown().none { it.isFile })
            assertTrue(File(fixture.context.filesDir, "completed-renders").walkTopDown().none { it.isFile })
            finishEditor()
        }
    }

    @Test fun smartModeRegeneratesSavesAcrossAspectsExportsAndReopensExactProject() {
        fixture.launch() // Startup cleanup completes before creating this winner's owned inputs.
        fixture.awaitEnabled(R.id.manualMvpButton)
        val directory = File(fixture.context.cacheDir, "smart-journey").apply { mkdirs() }
        val input = SyntheticVideo.create(File(directory, "input.mp4"), 1000, 320, 160,
            lumaAt = { x, _, _ -> if (x < 160) 40 else 180 })
        val inspected = MediaInputInspector().inspect("journey-source", input, "SMART source")
        val music = SourceAudioNativeFixtures.wav(File(directory, "music.wav")) { 0 }
        val graph = MontageGraph(1000, 1000, clips = listOf(MontageGraph.Clip("winner", 0, 1000, 1000,
            MontageGraph.ShotRole.ACTION, MontageGraph.Transition.HARD_CUT, MontageGraph.Motion.HOLD, 1f, 0)),
            audioTrack = MontageGraph.AudioTrack("music", 0f))
        val id = CustomMusicProjectHandoff.publish(fixture.context, graph, MediaSourceSet(listOf(inspected)), music,
            ProjectExportSettings(1080, 1920, 30, 8_000_000, 192_000), recipe = MontageStyleCatalog.Recipe.HEARTBEAT)
        val initial = store.load(id)
        val selection = initial.selectedVideos!!.single()
        val sourceBytes = initial.assets.associate { it.id to ProjectAssetStore(store.directory(id)).resolve(it).readBytes() }
        fixture.context.getSharedPreferences("manual_mvp", 0).edit().putString("project", "wrong-project")
            .putString("clip", "wrong-clip").putLong("position", 800_000).commit()
        fixture.scenario.onActivity { it.startActivity(Intent(it, ManualMvpActivity::class.java)
            .putExtra(ManualMvpActivity.EXTRA_PROJECT_ID, id)) }
        fixture.waitUntil("exact winner direct preview", 45_000) { ready() }
        instrumentation.runOnMainSync {
            assertEquals(id, field<String>(current()!!, "projectId"))
            assertEquals(0L, field<Long>(current()!!, "lastShownUs"))
        }
        val asset = initial.assets.single { it.id == selection.assetId }
        val metadata = requireNotNull(asset.videoMetadata)
        val key = SmartFramingKey(asset.contentHash, asset.durationUs, metadata.firstVideoPtsUs!!,
            metadata.videoEndPtsUs!!, listOf(VisualEventMap.UsableWindow(metadata.firstVideoPtsUs, metadata.videoEndPtsUs)))
        assertNull("This journey must exercise a real cache miss", MediaFrameAnalysisCache.loadSmart(fixture.context, key))
        tap("framing-SMART_PERSON")
        fixture.waitUntil("real source analysis and SMART GL READY", 120_000) {
            mode(id, selection.id, ProjectAspect.PORTRAIT_9_16) == FramingMode.SMART_PERSON && ready()
        }
        val preparedTrack = requireNotNull(MediaFrameAnalysisCache.loadSmart(fixture.context, key))
        assertTrue(preparedTrack.points.isNotEmpty())
        assertTrue(view<Button>("framing-SMART_PERSON")!!.isSelected)
        tap("aspect-FEED_4_5")
        fixture.waitUntil("new aspect keeps its independent default", 45_000) {
            store.load(id).current.visualSettings.aspect == ProjectAspect.FEED_4_5 && ready()
        }
        assertTrue(view<Button>("framing-MANUAL")!!.isSelected)
        tap("framing-SMART_PERSON")
        fixture.waitUntil("same source cached SMART in feed aspect", 45_000) {
            mode(id, selection.id, ProjectAspect.FEED_4_5) == FramingMode.SMART_PERSON && ready()
        }
        assertEquals(preparedTrack.points, requireNotNull(MediaFrameAnalysisCache.loadSmart(fixture.context, key)).points)
        tap("aspect-PORTRAIT_9_16")
        fixture.waitUntil("original aspect restores saved SMART", 45_000) {
            store.load(id).current.visualSettings.aspect == ProjectAspect.PORTRAIT_9_16 && ready()
        }
        assertTrue(view<Button>("framing-SMART_PERSON")!!.isSelected)
        tap("framing-BLURRED_FIT")
        fixture.waitUntil("blurred full-source preview", 45_000) {
            mode(id, selection.id, ProjectAspect.PORTRAIT_9_16) == FramingMode.BLURRED_FIT && ready()
        }
        tap("framing-SMART_PERSON")
        fixture.waitUntil("saved SMART ready for export", 45_000) {
            mode(id, selection.id, ProjectAspect.PORTRAIT_9_16) == FramingMode.SMART_PERSON && ready()
        }
        val revision = store.load(id).current.id
        tap("export")
        fixture.waitUntil("SMART native export linked to exact saved revision", 180_000) {
            store.load(id).exports.any { it.revisionId == revision } && ready()
        }
        val exported = store.load(id).exports.last()
        val output = File(fixture.context.filesDir, "completed-renders/${exported.fileName}")
        MediaMetadataRetriever().use { reader ->
            reader.setDataSource(output.path)
            assertEquals("1080", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
            assertEquals("1920", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
            assertNotNull(reader.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST)?.also { it.recycle() })
        }
        lateinit var previous: ManualMvpActivity
        instrumentation.runOnMainSync { previous = current()!!; previous.recreate() }
        fixture.waitUntil("same project SMART after recreation", 45_000) { current()?.let { it !== previous } == true && ready() }
        assertEquals(revision, store.load(id).current.id)
        assertTrue(view<Button>("framing-SMART_PERSON")!!.isSelected)
        instrumentation.runOnMainSync { assertEquals(id, field<String>(current()!!, "projectId")) }
        val after = store.load(id)
        assertEquals(initial.selectedVideos, after.selectedVideos)
        assertEquals(initial.assets, after.assets)
        after.assets.forEach { retained ->
            val bytes = ProjectAssetStore(store.directory(id)).resolve(retained).readBytes()
            assertArrayEquals(sourceBytes.getValue(retained.id), bytes)
            assertEquals(retained.contentHash, MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) })
        }
    }

    private fun mode(id: String, source: SourceId, aspect: ProjectAspect) =
        store.load(id).current.visualSettings.framings[FramingKey(source, aspect)]?.mode ?: FramingMode.MANUAL
    private fun ready() = view<TextView>("preview-state")?.text?.startsWith("Черновик · пауза") == true &&
        view<Button>("play")?.isEnabled == true
    private fun current() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
        .filterIsInstance<ManualMvpActivity>().firstOrNull()
    private fun <T : View> view(tag: String): T? {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            return current()?.window?.decorView?.findViewWithTag(tag)
        var result: T? = null
        instrumentation.runOnMainSync { result = current()?.window?.decorView?.findViewWithTag(tag) }
        return result
    }
    private fun tap(tag: String) = onView(withTagValue(`is`(tag))).perform(scrollTo(), click())
    private fun inventory(directory: File) = directory.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(directory).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
    }
    @Suppress("UNCHECKED_CAST") private fun <T> field(owner: Any, name: String): T? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T?
}
