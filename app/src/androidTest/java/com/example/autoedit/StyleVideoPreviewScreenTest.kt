package com.veycad.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StyleVideoPreviewScreenTest {
    @get:Rule val ui = UiTestFixtureRule()

    @Test fun every_available_style_has_a_decodable_three_second_bundled_example() {
        for (style in MontageStyleCatalog.available) {
            val asset = requireNotNull(StylePreviewAssets.forStyle(style))
            val retriever = MediaMetadataRetriever()
            try {
                ui.context.resources.openRawResourceFd(asset.video).use {
                    retriever.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                val duration = requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
                assertTrue("${style.id}: $duration ms", duration in 2_967L..3_033L)
                assertNotEquals("Examples must be muted assets", "yes",
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                val frame = requireNotNull(retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST))
                assertTrue(frame.width > 0 && frame.height > 0)
                frame.recycle()
            } finally { retriever.release() }
        }
        MontageStyleCatalog.all.filter { !it.available }.forEach { assertNull(StylePreviewAssets.forStyle(it)) }
    }

    @Test fun watching_fear_does_not_choose_it_or_persist_a_pending_style() {
        ui.launch()
        ui.click(R.id.selectStyleButton)
        onView(withText("Динамичный бит")).perform(scrollTo())
        onView(withTagValue(equalTo("style_preview:fear_strobe"))).perform(click())
        val preferences = ui.context.getSharedPreferences("montage_style", Context.MODE_PRIVATE)
        assertNull(preferences.getString("selected", null))
        onView(allOf(isClickable(), hasDescendant(withText("Динамичный бит")))).check(matches(not(isSelected())))
        onView(withText("Применить")).perform(click())
        assertEquals("heartbeat", preferences.getString("selected", null))
        onView(withId(R.id.selectStyleButton)).check(matches(withSubstring("Пульс под музыку")))
    }

    @Test fun a_manual_preview_keeps_moving_after_one_loop_and_releases_the_view_on_cancel() {
        ui.launch()
        ui.click(R.id.selectStyleButton)
        onView(withTagValue(equalTo("style_preview:heartbeat"))).perform(scrollTo(), restartPreview())
        waitForPlayback("heartbeat")
        assertPreviewAction("Пример воспроизводится без звука", "Приостановить пример")
        // Both samples are taken after the first 3-second cycle: a non-looping player freezes here.
        SystemClock.sleep(3_300)
        val first = frameSignature("heartbeat")
        SystemClock.sleep(700)
        val second = frameSignature("heartbeat")
        assertNotEquals("The example froze after its first cycle", first, second)
        onView(withTagValue(equalTo("style_preview:heartbeat"))).perform(click())
        assertPreviewAction("Пример остановлен", "Воспроизвести пример")
        onView(withTagValue(equalTo("style_preview_playing:heartbeat"))).check(doesNotExist())
        onView(withText("Отмена")).perform(click())
        onView(withTagValue(equalTo("style_preview_playing:heartbeat"))).check(doesNotExist())
        ui.awaitDisplayed(R.id.directorContent)
    }

    private fun assertPreviewAction(state: String, action: String) {
        onView(withTagValue(equalTo("style_preview:heartbeat"))).check { view, error ->
            if (error != null) throw error
            assertEquals(state, ViewCompat.getStateDescription(view)?.toString())
            val node = view.createAccessibilityNodeInfo()
            assertTrue(node.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK && it.label == action })
        }
    }

    private fun restartPreview() = object : ViewAction {
        override fun getDescription() = "Start the example regardless of its autoplay state"
        override fun getConstraints(): Matcher<View> = allOf(isDisplayed(), isAssignableFrom(FrameLayout::class.java))
        override fun perform(controller: UiController, view: View) {
            val frame = view as FrameLayout
            if ((0 until frame.childCount).any { frame.getChildAt(it) is TextureView }) frame.performClick()
            frame.performClick()
            controller.loopMainThreadUntilIdle()
        }
    }

    private fun waitForPlayback(id: String) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (true) {
            try { onView(withTagValue(equalTo("style_preview_playing:$id"))).check(matches(isDisplayed())); return }
            catch (error: RuntimeException) { if (SystemClock.uptimeMillis() >= deadline) throw error }
            SystemClock.sleep(100)
        }
    }

    private fun frameSignature(id: String): Int {
        var signature = 0
        onView(withTagValue(equalTo("style_preview_playing:$id"))).perform(object : ViewAction {
            override fun getDescription() = "Sample decoded preview pixels"
            override fun getConstraints(): Matcher<View> = isAssignableFrom(TextureView::class.java)
            override fun perform(controller: UiController, view: View) {
                val bitmap: Bitmap = requireNotNull((view as TextureView).bitmap)
                try {
                    for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8)
                        signature = 31 * signature + bitmap.getPixel(x,y)
                } finally { bitmap.recycle() }
            }
        })
        return signature
    }
}
