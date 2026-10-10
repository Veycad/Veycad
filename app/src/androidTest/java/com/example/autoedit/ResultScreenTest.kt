package com.veycad.app

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.VideoView
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.intent.Intents.intended
import org.hamcrest.Matchers.not
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real VideoView, file publication, MediaStore and FileProvider; no fake result screen. */
@RunWith(AndroidJUnit4::class)
class ResultScreenTest {
    @get:Rule val ui = UiTestFixtureRule()

    @Test fun real_preview_plays_pauses_and_resumes_without_losing_requested_pause() {
        ui.seedCompleted("preview-controls.mp4")
        ui.launch()
        ui.awaitDisplayed(R.id.resultContent)
        awaitPlayback(true)
        ui.click(R.id.resultVideo)
        awaitPlayback(false)

        ui.scenario.moveToState(Lifecycle.State.STARTED)
        ui.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitPlayback(false)
        ui.click(R.id.resultVideo)
        awaitPlayback(true)
        var firstPosition = 0
        ui.scenario.onActivity { firstPosition = it.findViewById<VideoView>(R.id.resultVideo).currentPosition }
        awaitVideo("decoder playback position must advance") { it.currentPosition != firstPosition }
    }

    @Test fun manual_save_copies_actual_video_and_disables_duplicate_save_after_recreation() {
        ui.seedCompleted("manual-gallery-save.mp4")
        ui.launch()
        ui.awaitDisplayed(R.id.resultContent)
        onView(withId(R.id.saveButton)).check(matches(isEnabled()))
        val original = requireNotNull(CompletedRenderStore.latest(ui.context.filesDir)).file
        val bytes = original.readBytes()

        ui.click(R.id.saveButton)
        ui.awaitText(R.id.saveButton, "Сохранено")
        ui.awaitIdle()
        onView(withId(R.id.saveButton)).check(matches(not(isEnabled())))
        assertTrue(requireNotNull(CompletedRenderStore.latest(ui.context.filesDir)).saved)
        ui.assertGalleryCopy(original.name)
        assertArrayEquals(bytes, java.io.File(ui.context.filesDir, "my-edits/${original.name}").readBytes())
        assertArrayEquals(bytes, original.readBytes())

        ui.recreate()
        ui.awaitText(R.id.saveButton, "Сохранено")
        onView(withId(R.id.saveButton)).check(matches(not(isEnabled())))
        ui.assertGalleryCopy(original.name)
    }

    @Test fun share_uses_readable_content_uri_with_grant_and_original_video_bytes() {
        ui.seedCompleted("share-current-result.mp4")
        ui.launch()
        ui.awaitDisplayed(R.id.resultContent)
        val source = requireNotNull(CompletedRenderStore.latest(ui.context.filesDir)).file
        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))

        ui.click(R.id.shareButton)
        intended(hasAction(Intent.ACTION_CHOOSER))
        val chooser = Intents.getIntents().last { it.action == Intent.ACTION_CHOOSER }
        @Suppress("DEPRECATION") val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("video/mp4", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION") val stream = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("content", stream.scheme)
        assertEquals("${ui.context.packageName}.files", stream.authority)
        val sharedBytes = requireNotNull(ui.context.contentResolver.openInputStream(stream)).use { it.readBytes() }
        assertArrayEquals(source.readBytes(), sharedBytes)
        assertFalse(requireNotNull(CompletedRenderStore.latest(ui.context.filesDir)).saved)
    }

    @Test fun failed_real_preview_keeps_save_and_share_available() {
        ui.seedCompleted("invalid-preview.mp4")
        val source = requireNotNull(CompletedRenderStore.latest(ui.context.filesDir)).file
        source.writeBytes(byteArrayOf(1, 2, 3, 4))
        ui.launch()
        ui.awaitDisplayed(R.id.resultContent)
        ui.awaitText(R.id.resultLoading, "Предпросмотр не открылся")
        onView(withId(R.id.saveButton)).check(matches(isEnabled()))
        onView(withId(R.id.shareButton)).check(matches(isEnabled()))
        awaitPlayback(false)
        assertTrue(source.isFile)
    }

    @Test fun create_new_clears_imported_draft_and_preserves_completed_result() {
        ui.launch()
        ui.importVideos("new-edit-source.mp4")
        ui.startRender()
        ui.renderer.complete()
        ui.awaitDisplayed(R.id.resultContent)
        ui.awaitIdle()
        val completed = requireNotNull(CompletedRenderStore.latest(ui.context.filesDir)).file
        val bytes = completed.readBytes()
        assertTrue(RenderWorkspace.sourceDraftDirectory(ui.context.filesDir).listFiles().orEmpty().isNotEmpty())

        ui.click(R.id.newEditButton)
        ui.awaitDisplayed(R.id.directorContent)
        ui.awaitText(R.id.renderHint, "Сначала")
        onView(withId(R.id.renderButton)).check(matches(not(isEnabled())))
        assertTrue(RenderWorkspace.sourceDraftDirectory(ui.context.filesDir).listFiles().orEmpty().isEmpty())
        assertArrayEquals(bytes, completed.readBytes())
        assertFalse(requireNotNull(CompletedRenderStore.latest(ui.context.filesDir)).saved)
        ui.click(R.id.myEditsButton)
        ui.awaitDisplayed(R.id.myEditsContent)
    }

    private fun awaitPlayback(playing: Boolean) = awaitVideo("playing=$playing") { it.isPlaying == playing }

    private fun awaitVideo(description: String, predicate: (VideoView) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var matched = false
            ui.scenario.onActivity { matched = predicate(it.findViewById(R.id.resultVideo)) }
            if (matched) return
            SystemClock.sleep(50L)
        }
        fail("Real preview did not satisfy $description")
    }
}
