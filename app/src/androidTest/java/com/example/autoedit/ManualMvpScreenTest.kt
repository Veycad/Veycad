package com.veycad.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.hamcrest.Matcher
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.containsString
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real Activity -> production import/store -> real decoder/Surface; no preview renderer substitute. */
class ManualMvpScreenTest {
    @get:Rule val fixture = UiTestFixtureRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val store get() = HybridProjectStore(fixture.context.filesDir)

    @After fun releaseEditorBeforeFixtureDeletesOwnedSources() {
        instrumentation.runOnMainSync {
            listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED)
                .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
                .filterIsInstance<ManualMvpActivity>().distinct().forEach { it.finish() }
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith("Veycad-preview-") }) {
            assertTrue("Preview owners must stop before fixture cleanup", android.os.SystemClock.elapsedRealtime() < deadline)
            android.os.SystemClock.sleep(25)
        }
    }

    @Test fun importFourFormatsTrimUndoReopenAndLandscapeExportShareOneRevision() {
        openImported()
        val id = store.list().single().id
        val sourceHashes = store.load(id).assets.associate { it.id to it.contentHash }
        for ((index, aspect) in ProjectAspect.entries.withIndex()) {
            tapTag("aspect-${aspect.name}")
            fixture.waitUntil("saved aspect ${aspect.name}", 30_000) {
                store.load(id).current.visualSettings.let { it.aspect == aspect && it.explicitlySelected } && ready()
            }
            assertEquals((index + 2).toLong(), store.load(id).current.id)
            assertTrue(view<Button>("aspect-${aspect.name}")!!.isSelected)
            assertSurfacePixels()
            capture("editor-${aspect.name}-paused")
        }
        assertFalse("Preview must never create an MP4", File(fixture.context.cacheDir, "manual-renders")
            .walkTopDown().any { it.isFile })
        onView(withText(containsString("2. B ·"))).perform(scrollTo(), click())
        fixture.waitUntil("selected B") { view<TextView>("selected-clip")?.text?.contains("B") == true && ready() }
        onView(withText("Конец −1")).perform(scrollTo(), click())
        fixture.waitUntil("trim persisted") { store.load(id).current.clips[1].span.length == 59 && ready() }
        onView(withText("Ниже")).perform(scrollTo(), click())
        fixture.waitUntil("reorder persisted") { store.load(id).current.clips.map { it.id } == listOf("A1", "A2", "B") && ready() }
        onView(withText("Отменить")).perform(scrollTo(), click())
        fixture.waitUntil("undo persisted") { store.load(id).current.clips.map { it.id } == listOf("A1", "B", "A2") && ready() }
        assertEquals(59, store.load(id).current.clips[1].span.length)
        tapTag("aspect-LANDSCAPE_16_9")
        fixture.waitUntil("landscape ready", 30_000) { store.load(id).current.visualSettings.aspect == ProjectAspect.LANDSCAPE_16_9 && ready() }
        val revision = store.load(id).current.id
        tapTag("export")
        capture("editor-exporting")
        fixture.waitUntil("export published", 180_000) { store.load(id).exports.any { it.revisionId == revision } && ready() }
        val saved = store.load(id).exports.last()
        val file = File(fixture.context.filesDir, "completed-renders/${saved.fileName}")
        MediaMetadataRetriever().use { metadata ->
            metadata.setDataSource(file.path)
            assertEquals("1920", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
            assertEquals("1080", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
        }
        assertEquals(sourceHashes, store.load(id).assets.associate { it.id to it.contentHash })
        store.load(id).assets.forEach { asset ->
            val owned = ProjectAssetStore(store.directory(id)).resolve(asset)
            val actual = java.security.MessageDigest.getInstance("SHA-256").digest(owned.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals("Retained original bytes", asset.contentHash, actual)
        }
        assertNotNull(view<Button>("share"))
        capture("editor-export-success")
        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        tapTag("share")
        val chooser = androidx.test.espresso.intent.Intents.getIntents().last { it.action == Intent.ACTION_CHOOSER }
        @Suppress("DEPRECATION")
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        @Suppress("DEPRECATION")
        val sharedUri = requireNotNull(send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertArrayEquals(file.readBytes(), fixture.context.contentResolver.openInputStream(sharedUri)!!.use { it.readBytes() })
        instrumentation.runOnMainSync { current()!!.recreate() }
        fixture.waitUntil("reopened exact paused frame", 30_000) { ready() }
        assertEquals(revision, store.load(id).current.id)
        assertTrue(view<Button>("aspect-LANDSCAPE_16_9")!!.isSelected)
        capture("editor-reopened")
        instrumentation.runOnMainSync { current()!!.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        fixture.waitUntil("landscape Activity ready", 30_000) { ready() }
        for (aspect in ProjectAspect.entries) {
            tapTag("aspect-${aspect.name}")
            fixture.waitUntil("landscape ${aspect.name} ready", 30_000) {
                ready() && view<Button>("aspect-${aspect.name}")?.isSelected == true
            }
            assertSurfacePixels()
            capture("editor-landscape-${aspect.name}")
        }
        finishEditor()
    }

    @Test fun saved60FpsScrubPlayPauseBackgroundAndRecreateRemainPaused() {
        openImported(fps = 60)
        assertEquals(60, store.list().single().fps)
        onView(withTagValue(`is`("preview-seek"))).perform(scrollTo(), scrub(25), scrub(100), scrub(61))
        fixture.waitUntil("60 fps exact scrub", 30_000) { ready() && view<SeekBar>("preview-seek")?.progress == 61 }
        assertEquals("Воспроизвести", view<Button>("play")!!.text.toString())
        capture("editor-paused-after-seek")
        tapTag("play")
        fixture.waitUntil("actual playback started", 30_000) { view<Button>("play")?.text == "Пауза" }
        capture("editor-playing")
        tapTag("play")
        fixture.waitUntil("actual paused frame", 30_000) { ready() }
        val position = view<SeekBar>("preview-seek")!!.progress
        instrumentation.runOnMainSync { current()!!.recreate() }
        fixture.waitUntil("paused after recreate", 30_000) { ready() }
        assertEquals(position, view<SeekBar>("preview-seek")!!.progress)
        finishEditor()
        fixture.click(R.id.manualMvpButton)
        fixture.waitUntil("paused after reopen", 30_000) { ready() }
        assertEquals(position, view<SeekBar>("preview-seek")!!.progress)
        capture("editor-back-return")
        tapTag("play")
        fixture.waitUntil("playing before Home", 30_000) { view<Button>("play")?.text == "Пауза" }
        androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressHome()
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith("Veycad-preview-") }) {
            assertTrue("Background must release video and music owners", android.os.SystemClock.elapsedRealtime() < deadline)
            android.os.SystemClock.sleep(25)
        }
        shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n ${fixture.context.packageName}/com.veycad.app.MainActivity")
        fixture.waitUntil("paused after Home return", 30_000) { ready() }
        capture("editor-background-return")
        finishEditor()
    }

    @Test fun landscapeLargeFontMissingSourceShowsRecoverableErrorAndSelectedFormat() {
        openImported()
        tapTag("aspect-FEED_4_5")
        fixture.waitUntil("4:5 ready", 30_000) { ready() && view<Button>("aspect-FEED_4_5")?.isSelected == true }
        val project = store.list().single()
        finishEditor()
        // Wait for asynchronous preview disposal before touching original source bytes.
        fixture.waitUntil("preview worker released", 30_000) {
            Thread.getAllStackTraces().keys.none { it.isAlive && it.name == "Veycad-preview-GL" }
        }
        val source = ProjectAssetStore(store.directory(project.id)).resolve(project.assets.first { it.kind == ProjectAsset.Kind.VIDEO })
        val bytes = source.readBytes()
        source.writeBytes(byteArrayOf(0))
        val previousScale = shell("settings get system font_scale").trim()
        try {
            shell("settings put system font_scale 2.0")
            shell("wm size 720x1280")
            fixture.click(R.id.manualMvpButton)
            fixture.waitUntil("readable error", 30_000) { view<TextView>("preview-state")?.text?.startsWith("Не удалось") == true }
            onView(withTagValue(`is`("aspect-FEED_4_5"))).perform(scrollTo())
            capture("editor-narrow-large-font-error")
            instrumentation.runOnMainSync { current()!!.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            fixture.waitUntil("landscape error recreated", 30_000) { view<TextView>("preview-state")?.text?.startsWith("Не удалось") == true }
            onView(withTagValue(`is`("aspect-FEED_4_5"))).perform(scrollTo())
            assertTrue(view<Button>("aspect-FEED_4_5")!!.isSelected)
            capture("editor-landscape-large-font-error")
            source.writeBytes(bytes)
            tapTag("retry")
            fixture.waitUntil("retry real exact frame", 30_000) { ready() }
            capture("editor-landscape-large-font-retry")
        } finally {
            source.writeBytes(bytes)
            shell("settings put system font_scale ${previousScale.toFloatOrNull() ?: 1f}")
            shell("wm size reset")
            finishEditor()
        }
    }

    private fun openImported(fps: Int = 30) {
        fixture.launch()
        capture("main-entry")
        fixture.click(R.id.manualMvpButton)
        fixture.waitUntil("editor import ready") { view<Button>("import")?.isEnabled == true }
        if (fps == 60) {
            onView(withText("60 кадр/с")).perform(scrollTo(), click())
            fixture.waitUntil("60 fps import ready") { view<Button>("import")?.isEnabled == true }
        }
        // AFTER launch: startup cleanup must not delete the picker input.
        val file = SyntheticVideo.create(File(fixture.context.filesDir, "pending-renders/manual-input.mp4"),
            lumaAt = { x, y, frame -> if (x < 40) 40 else if (x > 120) 180 else 90 + (y + frame) % 40 })
        val uri = FileProvider.getUriForFile(fixture.context, "${fixture.context.packageName}.files", file)
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().apply { data = uri }))
        tapTag("import")
        fixture.waitUntil("real imported paused preview", 45_000) { ready() }
    }

    private fun ready() = view<TextView>("preview-state")?.text == "Черновик · пауза" &&
        view<Button>("play")?.isEnabled == true
    private fun current(): ManualMvpActivity? = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<ManualMvpActivity>().firstOrNull()
    private fun <T : View> view(tag: String): T? {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            return current()?.window?.decorView?.findViewWithTag(tag)
        var found: T? = null
        instrumentation.runOnMainSync { found = current()?.window?.decorView?.findViewWithTag(tag) }
        return found
    }
    private fun tapTag(tag: String) = onView(withTagValue(`is`(tag))).perform(scrollTo(), click())
    private fun finishEditor() { instrumentation.runOnMainSync { current()?.finish() }; instrumentation.waitForIdleSync() }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
        .use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().readText() }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        // Include actual Surface pixels in every ready-player capture. Controls can be reached
        // independently by scrolling; landscape never changes the project's aspect itself.
        if (ready() || view<Button>("play")?.text == "Пауза" || name.startsWith("editor-export"))
            onView(withTagValue(`is`("preview-surface"))).perform(scrollTo())
        val directory = fixture.context.getExternalFilesDir("preview-evidence")!!.apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var configuration = ""
        instrumentation.runOnMainSync {
            val activity = current()
            configuration = "fontScale=${activity?.resources?.configuration?.fontScale}\n" +
                "density=${activity?.resources?.displayMetrics?.density}\nstate=${view<TextView>("preview-state")?.text}\n"
        }
        File(directory, "$name.txt").writeText("source=${BuildConfig.SOURCE_SHA}\n" +
            "screen=${bitmap.width}x${bitmap.height}\n" + configuration +
            "aspect=${store.list().firstOrNull()?.current?.visualSettings?.aspect}\n")
        bitmap.recycle()
    }
    private fun assertSurfacePixels() {
        fixture.waitUntil("patterned pixels reached the real Surface", 30_000) {
            val bitmap = view<android.view.TextureView>("preview-surface")?.bitmap ?: return@waitUntil false
            val values = (1..8).flatMap { x -> (1..8).map { y ->
                android.graphics.Color.red(bitmap.getPixel(bitmap.width * x / 9, bitmap.height * y / 9))
            } }
            val patterned = values.max() - values.min() > 8
            bitmap.recycle()
            patterned
        }
    }
    private fun scrub(frame: Int) = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(SeekBar::class.java)
        override fun getDescription() = "Scrub output frame $frame using the actual SeekBar touch handlers"
        override fun perform(controller: UiController, view: View) {
            val seek = view as SeekBar
            val now = android.os.SystemClock.uptimeMillis()
            val x = seek.paddingLeft + (seek.width - seek.paddingLeft - seek.paddingRight) * frame.toFloat() / seek.max
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                android.view.MotionEvent.obtain(now, now + 10, action, x, seek.height / 2f, 0).let { event ->
                    seek.dispatchTouchEvent(event); event.recycle()
                }
            }
            controller.loopMainThreadUntilIdle()
        }
    }
}
