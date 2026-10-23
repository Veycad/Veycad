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
        while (Thread.getAllStackTraces().keys.any { it.isAlive &&
                (it.name.startsWith("Veycad-preview-") || it.name == "Veycad-manual-jobs") }) {
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
        fixture.waitUntil("landscape Activity ready", 30_000) {
            current()?.resources?.configuration?.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                ready()
        }
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
        capture("editor-paused-after-seek", expectedFrame = 61)
        tapTag("play")
        fixture.waitUntil("actual playback started", 30_000) { view<Button>("play")?.text == "Пауза" }
        capture("editor-playing")
        tapTag("play")
        fixture.waitUntil("actual paused frame", 30_000) { ready() }
        val position = view<SeekBar>("preview-seek")!!.progress
        instrumentation.runOnMainSync { current()!!.recreate() }
        fixture.waitUntil("paused after recreate", 30_000) { ready() }
        assertEquals(position, view<SeekBar>("preview-seek")!!.progress)
        recreateBehindHeldConsumer(exportQueued = false)
        recreateBehindHeldConsumer(exportQueued = true)
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
            // This CI device uses density=1: 720 physical pixels would be a tablet width.
            shell("wm size 320x640")
            fixture.waitUntil("narrow large-font Main recreation and splash settled", 30_000) {
                val main = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().singleOrNull() ?: return@waitUntil false
                main.resources.configuration.let { it.fontScale == 2f && it.screenWidthDp == 320 } &&
                    main.hasWindowFocus() && !main.window.decorView.isLayoutRequested &&
                    main.findViewById<View>(R.id.splashContent).visibility == View.GONE &&
                    main.findViewById<View>(R.id.manualMvpButton).isShown
            }
            fixture.click(R.id.manualMvpButton)
            fixture.waitUntil("readable error", 30_000) { view<TextView>("preview-state")?.text?.startsWith("Не удалось") == true }
            onView(withTagValue(`is`("aspect-FEED_4_5"))).perform(scrollTo())
            capture("editor-narrow-large-font-error")
            instrumentation.runOnMainSync { current()!!.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            fixture.waitUntil("landscape error recreated", 30_000) {
                current()?.resources?.configuration?.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                    view<TextView>("preview-state")?.text?.startsWith("Не удалось") == true
            }
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
    /** Hold actual owner executors, not a renderer substitute or a production test-only hook. */
    private fun recreateBehindHeldConsumer(exportQueued: Boolean) {
        var old: ManualMvpActivity? = null
        instrumentation.runOnMainSync { old = current() }
        val previous = requireNotNull(old)
        val borrowed = requireNotNull(field<Any>(previous, "binding"))
        val prepared = requireNotNull(field<PreparedHybridPreview>(borrowed, "prepared"))
        val executor = if (exportQueued) requireNotNull(field<java.util.concurrent.ExecutorService>(previous, "jobs"))
            else requireNotNull(field<java.util.concurrent.ExecutorService>(
                requireNotNull(field<DraftPreviewController>(previous, "controller")), "worker"))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        executor.execute {
            entered.countDown()
            val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
            var interrupted = false
            try {
                while (release.count > 0 && android.os.SystemClock.elapsedRealtime() < deadline) {
                    try { release.await(25, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    catch (_: InterruptedException) { interrupted = true }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
        assertTrue("Old native/job boundary entered", entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        try {
            if (exportQueued) {
                tapTag("export")
                fixture.waitUntil("native ack transferred prepared to queued export", 10_000) {
                    field<Any>(previous, "binding") == null && field<Any>(previous, "controller") == null
                }
            }
            instrumentation.runOnMainSync { previous.recreate() }
            fixture.waitUntil("replacement resumed behind old consumer", 10_000) { current()?.let { it !== previous } == true }
            var replacement: ManualMvpActivity? = null
            instrumentation.runOnMainSync { replacement = current() }
            // Drain both worker -> main -> worker dispatch hops. A replacement that starts
            // preparation without admission cannot hide behind an unprocessed callback.
            repeat(2) {
                val drained = java.util.concurrent.CountDownLatch(1)
                field<java.util.concurrent.ExecutorService>(requireNotNull(replacement), "jobs")!!.execute { drained.countDown() }
                assertTrue(drained.await(5, java.util.concurrent.TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
            }
            instrumentation.runOnMainSync {
                assertNull("No replacement native controller before old completion", field<Any>(requireNotNull(replacement), "controller"))
                assertNull("No replacement preparation before old completion", field<Any>(requireNotNull(replacement), "binding"))
                assertFalse(ready())
            }
            assertTrue("Old prepared lifetime remains valid while consumer is held", prepared.framePlan.frames.isNotEmpty())
        } finally { release.countDown() }
        fixture.waitUntil("one paused replacement after old cleanup", 30_000) { ready() }
        assertTrue("Old prepared closed after actual cleanup", runCatching { prepared.framePlan }.isFailure)
        fixture.waitUntil("one native preview owner", 10_000) {
            Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "Veycad-preview-GL" } == 1
        }
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner) as T?
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
    private fun finishEditor() {
        instrumentation.runOnMainSync { current()?.onBackPressedDispatcher?.onBackPressed() }
        fixture.waitUntil("Back returns only after editor cleanup", 30_000) { current() == null }
    }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
        .use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().readText() }
    private data class CaptureState(val status: String?, val clock: String?, val frame: Int?,
        val playing: Boolean, val paused: Boolean, val patterned: Boolean,
        val surfaceBounds: android.graphics.Rect?, val windowBoundsOnScreen: android.graphics.Rect?,
        val configuration: String)
    /** Called on main: record the same actual state around bitmap acquisition, not later metadata. */
    private fun captureState(): CaptureState {
        val activity = current()
        val decor = (activity ?: ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED).singleOrNull())?.window?.decorView
        val surface = view<View>("preview-surface")
        val bounds = android.graphics.Rect().takeIf {
            surface?.getGlobalVisibleRect(it) == true
        }?.apply {
            // getGlobalVisibleRect is root-relative, not screen-relative.
            val rootInWindow = IntArray(2)
            surface!!.rootView.getLocationInWindow(rootInWindow)
            offset(rootInWindow[0], rootInWindow[1])
        }
        val windowBounds = decor?.let {
            val origin = IntArray(2)
            it.getLocationOnScreen(origin)
            android.graphics.Rect(origin[0], origin[1], origin[0] + it.width, origin[1] + it.height)
        }
        return CaptureState(view<TextView>("preview-state")?.text?.toString(),
            activity?.let { field<TextView>(it, "clock")?.text?.toString() },
            view<SeekBar>("preview-seek")?.progress, view<Button>("play")?.text == "Пауза",
            ready(), surfaceHasPattern(), bounds, windowBounds, "fontScale=${activity?.resources?.configuration?.fontScale}\n" +
                "density=${activity?.resources?.displayMetrics?.density}\n" +
                "orientation=${activity?.resources?.configuration?.orientation}\n")
    }
    private fun capture(name: String, expectedFrame: Int? = null) {
        instrumentation.waitForIdleSync()
        val playingCapture = name == "editor-playing"
        val pausedCapture = name.startsWith("editor-") && !playingCapture &&
            name != "editor-exporting" && !name.endsWith("-error")
        if (pausedCapture) fixture.waitUntil("paused $name before scrolling", 30_000) {
            ready() && (expectedFrame == null || view<SeekBar>("preview-seek")?.progress == expectedFrame)
        }
        lateinit var expected: CaptureState
        instrumentation.runOnMainSync { expected = captureState() }
        // Include actual Surface pixels in every ready-player capture. Controls can be reached
        // independently by scrolling; landscape never changes the project's aspect itself.
        if (pausedCapture || playingCapture || name.startsWith("editor-export"))
            onView(withTagValue(`is`("preview-surface"))).perform(scrollTo())
        val directory = fixture.context.getExternalFilesDir("preview-evidence")!!.apply { mkdirs() }
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        fun settled(state: CaptureState) = when {
            pausedCapture -> state.paused && state.patterned && state.frame == expected.frame && state.clock == expected.clock
            playingCapture -> state.playing && state.patterned
            else -> true
        }
        fun unchanged(before: CaptureState, after: CaptureState) = settled(after) &&
            before.status == after.status && before.configuration == after.configuration &&
            before.surfaceBounds == after.surfaceBounds &&
            before.windowBoundsOnScreen == after.windowBoundsOnScreen &&
            (playingCapture || (before.clock == after.clock && before.frame == after.frame))
        repeat(5) { attempt ->
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            assertTrue("Capture $name did not settle within 30 seconds", remaining > 0)
            lateinit var before: CaptureState
            fixture.waitUntil("post-scroll $name state, output clock and Surface pixels", remaining) {
                before = captureState()
                settled(before)
            }
            // A frame commit is not a display-present fence. Copy the actual Window's
            // committed buffer, including the entire decor/inset area, instead of the display.
            val bitmap = copyCommittedWindow(deadline) { unchanged(before, captureState()) }
            lateinit var after: CaptureState
            instrumentation.runOnMainSync { after = captureState() }
            val stable = unchanged(before, after)
            val screenshotHasFrame = bitmap != null && (!(pausedCapture || playingCapture) ||
                before.surfaceBounds?.let { hasPattern(bitmap, it) } == true)
            if (bitmap != null && stable && screenshotHasFrame) {
                try {
                    assertTrue("Capture $name has no pixels", bitmap.width > 0 && bitmap.height > 0)
                    File(directory, "$name.png").outputStream().use {
                        assertTrue("Capture $name PNG encoding failed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    File(directory, "$name.txt").writeText("source=${BuildConfig.SOURCE_SHA}\n" +
                        "capture=window-pixelcopy-after-frame-commit\n" +
                        "scope=full-activity-decor-including-inset-area-not-system-ui-overlays\n" +
                        "windowPixels=${bitmap.width}x${bitmap.height}\nwindowBoundsOnScreen=${before.windowBoundsOnScreen}\n" +
                        "${before.configuration}state=${before.status}\n" +
                        "clockBefore=${before.clock}\nclockAfter=${after.clock}\n" +
                        "frameBefore=${before.frame}\nframeAfter=${after.frame}\nattempts=${attempt + 1}\n" +
                        "aspect=${store.list().firstOrNull()?.current?.visualSettings?.aspect}\n")
                    return
                } finally { bitmap.recycle() }
            }
            bitmap?.recycle()
        }
        throw AssertionError("Capture $name did not produce a real bitmap with stable state after five attempts")
    }
    /**
     * Android's frame-commit contract explicitly recommends PixelCopy: the committed buffer
     * need not be visible on the display yet. No View.draw(), renderer substitute or sleep.
     * The configured API 36 hardware-rendered CI satisfies this evidence prerequisite.
     */
    @android.annotation.TargetApi(29)
    private fun copyCommittedWindow(deadline: Long, stateStillCurrent: () -> Boolean): Bitmap? {
        check(android.os.Build.VERSION.SDK_INT >= 29) { "Committed-window evidence requires API 29+" }
        val committed = java.util.concurrent.CountDownLatch(1)
        val callback = Runnable { committed.countDown() }
        var observer: android.view.ViewTreeObserver? = null
        var activity: Activity? = null
        var decor: View? = null
        var width = 0
        var height = 0
        try {
            instrumentation.runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).singleOrNull()
                val root = resumed?.window?.decorView
                if (root != null && root.isAttachedToWindow && !root.isLayoutRequested &&
                    root.width > 0 && root.height > 0 && stateStillCurrent()) {
                    check(root.isHardwareAccelerated) { "Frame-commit evidence requires hardware rendering" }
                    activity = resumed
                    decor = root
                    width = root.width
                    height = root.height
                    observer = root.viewTreeObserver
                    observer!!.registerFrameCommitCallback(callback)
                    // Registered after post-scroll readiness, so this traversal contains that UI.
                    root.invalidate()
                }
            }
            if (observer == null) return null
            assertTrue("Timed out waiting for the ready Activity frame to commit",
                committed.await((deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0),
                    java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally {
            instrumentation.runOnMainSync {
                observer?.takeIf { it.isAlive }?.unregisterFrameCommitCallback(callback)
            }
        }
        val copy = java.util.concurrent.CompletableFuture<Bitmap?>()
        var requested = false
        instrumentation.runOnMainSync {
            val root = requireNotNull(decor)
            val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            if (resumed.any { it === activity } && activity?.window?.decorView === root &&
                root.isAttachedToWindow && !root.isLayoutRequested &&
                root.width == width && root.height == height && stateStillCurrent()) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    // Explicit decor coordinates avoid cropping content or scaling inset geometry.
                    android.view.PixelCopy.request(requireNotNull(activity).window,
                        android.graphics.Rect(0, 0, width, height), bitmap, { result ->
                            if (result == android.view.PixelCopy.SUCCESS) {
                                // A late callback owns cleanup when the bounded waiter has left.
                                if (!copy.complete(bitmap)) bitmap.recycle()
                            } else {
                                bitmap.recycle()
                                copy.complete(null)
                            }
                        }, android.os.Handler(android.os.Looper.getMainLooper()))
                    requested = true
                } catch (error: IllegalArgumentException) {
                    bitmap.recycle()
                    throw error
                }
            }
        }
        if (!requested) return null
        var delivered = false
        try {
            val bitmap = copy.get((deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0),
                java.util.concurrent.TimeUnit.MILLISECONDS)
            delivered = true
            return bitmap
        } catch (error: java.util.concurrent.TimeoutException) {
            throw AssertionError("Timed out copying the committed Activity window", error)
        } finally {
            // Resolve completion-vs-timeout races without recycling an in-flight destination.
            if (!delivered && !copy.cancel(false)) copy.getNow(null)?.recycle()
        }
    }
    private fun surfaceHasPattern(): Boolean {
        val bitmap = view<android.view.TextureView>("preview-surface")?.bitmap ?: return false
        return try { hasPattern(bitmap, android.graphics.Rect(0, 0, bitmap.width, bitmap.height)) }
        finally { bitmap.recycle() }
    }
    private fun hasPattern(bitmap: Bitmap, bounds: android.graphics.Rect): Boolean {
        val visible = android.graphics.Rect(bounds)
        if (!visible.intersect(0, 0, bitmap.width, bitmap.height)) return false
        val values = (1..8).flatMap { x -> (1..8).map { y ->
            android.graphics.Color.red(bitmap.getPixel(visible.left + visible.width() * x / 9,
                visible.top + visible.height() * y / 9))
        } }
        return values.max() - values.min() > 8
    }
    private fun assertSurfacePixels() {
        fixture.waitUntil("patterned pixels reached the real Surface", 30_000) { surfaceHasPattern() }
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
