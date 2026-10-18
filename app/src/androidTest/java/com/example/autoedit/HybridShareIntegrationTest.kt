package com.veycad.app

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HybridShareIntegrationTest {
    @get:Rule val ui = UiTestFixtureRule()

    @Test fun dirtyProjectExportsBeforeSharingAndForegroundCompletionSharesExactNewResultOnce() {
        val older = ui.seedCompleted("older-share.mp4")
        ui.launch()
        ui.awaitDisplayed(R.id.resultContent)
        pressBack()
        ui.importVideos("changed-source.mp4")
        startShare()
        assertEquals(0, choosers().size)
        assertEquals(1, ui.renderer.requestCount)
        assertEquals(older, CompletedRenderStore.latest(ui.context.filesDir))
        ui.scenario.onActivity { assertFalse(it.findViewById<Button>(R.id.renderShareButton).isEnabled) }
        ui.renderer.complete()
        ui.awaitIdle()
        ui.awaitDisplayed(R.id.resultContent)
        val published = requireNotNull(CompletedRenderStore.latest(ui.context.filesDir))
        assertNotEquals(older.file, published.file)
        assertEquals(1, choosers().size)
        val send = sendIntent(choosers().single())
        val stream = stream(send)
        assertArrayEquals(published.file.readBytes(), ui.context.contentResolver.openInputStream(stream)!!.use { it.readBytes() })
        assertEquals(published.file.name, stream.lastPathSegment)
        assertFalse(published.saved)
        assertFalse(ui.context.getSharedPreferences("app_settings", 0).getBoolean("notifications", true))
        assertTrue(ui.context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.isEmpty())
        assertTrue(Intents.getIntents().none { it.action == "android.content.pm.action.REQUEST_PERMISSIONS" })
        ui.recreate()
        ui.awaitDisplayed(R.id.resultContent)
        assertEquals("Cancelled chooser and replayed state must not repeat", 1, choosers().size)
        ui.click(R.id.saveButton)
        ui.awaitIdle()
        assertEquals("Save state must not replay sharing", 1, choosers().size)
    }

    @Test fun backgroundCompletionAndRecreationDuringRenderKeepOnlyManualSharing() {
        ui.launch()
        ui.importVideos("background-share.mp4")
        startShare()
        ui.scenario.moveToState(Lifecycle.State.CREATED)
        ui.renderer.complete()
        ui.awaitIdle()
        ui.scenario.moveToState(Lifecycle.State.RESUMED)
        ui.awaitDisplayed(R.id.resultContent)
        assertEquals(0, choosers().size)
        ui.click(R.id.shareButton)
        assertEquals(1, choosers().size)
        pressBack()
        ui.renderer.reset()
        startShare()
        ui.recreate()
        ui.renderer.complete()
        ui.awaitIdle()
        ui.awaitDisplayed(R.id.resultContent)
        assertEquals(1, choosers().size)
        ui.click(R.id.shareButton)
        assertEquals(2, choosers().size)
    }

    @Test fun failedAndCancelledRenderDoNotShareOlderResultAndNormalCreateStaysManual() {
        val older = ui.seedCompleted("before-failed-share.mp4")
        ui.launch()
        ui.awaitDisplayed(R.id.resultContent)
        pressBack()
        ui.importVideos("failed-share-source.mp4")
        startShare()
        ui.renderer.fail("share render failed")
        ui.awaitIdle()
        assertEquals(0, choosers().size)
        assertEquals(older, CompletedRenderStore.latest(ui.context.filesDir))
        ui.renderer.reset()
        startShare()
        ui.click(R.id.cancelRenderButton)
        ui.awaitIdle()
        assertEquals(0, choosers().size)
        ui.startRender()
        ui.renderer.complete()
        ui.awaitIdle()
        ui.awaitDisplayed(R.id.resultContent)
        assertEquals(0, choosers().size)
        assertNotEquals(older.file, CompletedRenderStore.latest(ui.context.filesDir)!!.file)
    }

    @Test fun receiverCanReadExactMp4WithoutGalleryCopy() {
        val entry = ui.seedCompleted("receiver-share.mp4")
        ui.launch()
        ui.awaitDisplayed(R.id.resultContent)
        val send = HybridShareCoordinator(ui.context).createIntent(entry)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("video/mp4", send.type)
        assertEquals("content", stream(send).scheme)
        assertEquals(stream(send), send.clipData!!.getItemAt(0).uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        ShareReadActivity.reset()
        ui.scenario.onActivity { activity ->
            activity.startActivity(send.setClass(activity, ShareReadActivity::class.java))
        }
        ui.waitUntil("receiving Activity read the MP4") { ShareReadActivity.receivedBytes != null || ShareReadActivity.failure != null }
        assertNull(ShareReadActivity.failure)
        assertArrayEquals(entry.file.readBytes(), ShareReadActivity.receivedBytes)
        assertEquals(stream(send), ShareReadActivity.receivedUri)
        assertFalse(entry.saved)
        assertFalse(java.io.File(ui.context.filesDir, "my-edits/${entry.file.name}").exists())
    }

    @Test fun unavailableChooserAndInvalidFilePreserveResultAndAllowManualRetry() {
        val entry = ui.seedCompleted("unavailable-chooser.mp4")
        val coordinator = HybridShareCoordinator(ui.context)
        val unavailable = object : ContextWrapper(ui.context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException("no chooser") }
        }
        assertTrue(coordinator.openChooser(unavailable, entry.file).isFailure)
        assertTrue(entry.file.isFile)
        assertEquals(entry, CompletedRenderStore.latest(ui.context.filesDir))
        ui.launch()
        ui.awaitDisplayed(R.id.resultContent)
        val bytes = entry.file.readBytes()
        entry.file.writeBytes(byteArrayOf())
        ui.click(R.id.shareButton)
        ui.awaitDisplayed(R.id.shareError)
        assertEquals(0, choosers().size)
        assertTrue(entry.file.exists())
        entry.file.writeBytes(bytes)
        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
        ui.click(R.id.shareButton)
        assertEquals(1, choosers().size)
        ui.scenario.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.shareError).visibility) }
    }

    @Test fun consumedIdsSurviveCoordinatorRecreationAndCaptureModeHidesNewAction() {
        val entry = ui.seedCompleted("consumed.mp4")
        val first = HybridShareCoordinator(ui.context)
        assertTrue(first.pending("completed-operation", entry).automaticEligible)
        assertTrue(first.consume("completed-operation"))
        val restored = HybridShareCoordinator(ui.context)
        assertFalse(restored.consume("completed-operation"))
        assertFalse(restored.pending("completed-operation", entry).automaticEligible)
        ui.launch()
        ui.awaitDisplayed(R.id.resultContent)
        pressBack()
        ui.click(R.id.autoModeButton)
        ui.scenario.onActivity {
            assertEquals(View.GONE, it.findViewById<View>(R.id.renderShareButton).visibility)
            assertFalse(it.findViewById<View>(R.id.renderShareButton).isEnabled)
        }
    }

    @Test fun noSourcesDisableCreateAndShareAndSavedLibraryUsesSamePublishedFileContract() {
        val saved = ui.seedSaved("share-saved-library.mp4")
        ui.launch()
        ui.scenario.onActivity {
            assertFalse(it.findViewById<Button>(R.id.renderButton).isEnabled)
            assertFalse(it.findViewById<Button>(R.id.renderShareButton).isEnabled)
        }
        ui.click(R.id.myEditsButton)
        onView(withText(saved.nameWithoutExtension)).perform(scrollTo(), click())
        ui.awaitDisplayed(R.id.resultContent)
        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
        ui.click(R.id.shareButton)
        val send = sendIntent(choosers().single())
        assertEquals(stream(send), send.clipData!!.getItemAt(0).uri)
        assertEquals(saved.name, stream(send).lastPathSegment)
        assertArrayEquals(saved.readBytes(), ui.context.contentResolver.openInputStream(stream(send))!!.use { it.readBytes() })
    }

    private fun startShare() {
        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
        ui.click(R.id.renderShareButton)
        ui.waitUntil("new renderer request") { ui.renderer.currentRequest != null }
    }
    private fun choosers() = Intents.getIntents().filter { it.action == Intent.ACTION_CHOOSER }
    @Suppress("DEPRECATION")
    private fun sendIntent(chooser: Intent): Intent = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT))
    @Suppress("DEPRECATION")
    private fun stream(send: Intent): Uri = requireNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM))
}
