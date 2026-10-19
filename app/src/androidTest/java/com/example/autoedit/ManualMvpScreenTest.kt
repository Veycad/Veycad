package com.veycad.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDisplayingAtLeast
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real AVC picker input and durable common-store edits; no renderer substitute. */
class ManualMvpScreenTest {
    @get:Rule val fixture = UiTestFixtureRule()

    @Test fun pickerTrimReorderUndoAndRecreateKeepSavedSelection() {
        fixture.launch()
        val file = SyntheticVideo.create(File(fixture.context.filesDir, "pending-renders/manual-input.mp4"))
        val uri = FileProvider.getUriForFile(fixture.context, "${fixture.context.packageName}.files", file)
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().apply { data = uri }))
        fixture.click(R.id.manualMvpButton)
        onView(withText("Выбрать видео для базового монтажа")).perform(scrollTo(), click())
        try {
            fixture.waitUntil("basic project created", 30_000) {
                HybridProjectStore(fixture.context.filesDir).list().size == 1
            }
        } catch (failure: AssertionError) {
            var visible = "Manual activity is not resumed"
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                currentManual()?.let { activity ->
                    val labels = mutableListOf<String>()
                    fun collect(view: View) {
                        if (view is TextView && view.isShown) labels += view.text.toString()
                        if (view is ViewGroup) repeat(view.childCount) { collect(view.getChildAt(it)) }
                    }
                    collect(activity.window.decorView)
                    visible = labels.joinToString(" | ")
                }
            }
            throw AssertionError("${failure.message}; visible manual screen: $visible", failure)
        }
        val store = HybridProjectStore(fixture.context.filesDir)
        val id = store.list().single().id
        fixture.waitUntil("B visible") { hasManualText("○ 2. B ·") }
        onView(withText(containsString("2. B ·"))).perform(scrollTo(), click())
        fixture.waitUntil("B selected") { hasManualText("● 2. B ·") }
        onView(withText("Конец −1")).perform(scrollTo(), click())
        fixture.waitUntil("one frame saved") {
            store.load(id).current.clips[1].span.length == 59 && hasManualText("● 2. B · 59")
        }
        onView(withText("Ниже")).perform(scrollTo(), click())
        fixture.waitUntil("reorder saved") {
            store.load(id).current.clips.map { it.id } == listOf("A1", "A2", "B") && hasManualText("● 3. B ·")
        }
        onView(withText("Отменить")).perform(scrollTo(), click())
        fixture.waitUntil("undo saved") {
            store.load(id).current.clips.map { it.id } == listOf("A1", "B", "A2")
        }
        assertEquals(59, store.load(id).current.clips[1].span.length)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { currentManual()?.recreate() }
        fixture.waitUntil("selection reopened") { hasManualText("● 2. B ·") }
        onView(withText(containsString("● 2. B ·"))).perform(scrollTo()).check(matches(isDisplayed()))
        assertEquals(listOf("A1", "B", "A2"), store.load(id).current.clips.map { it.id })
        InstrumentationRegistry.getInstrumentation().runOnMainSync { currentManual()?.finish() }
    }

    private fun hasManualText(prefix: String): Boolean {
        val activity = currentManual() ?: return false
        // Refresh rebuilds the controls asynchronously. Text can enter the tree before
        // its first layout, when scrollTo would receive an empty drawing rectangle.
        fun laidOut(view: View): Boolean {
            var current: View? = view
            while (current != null) {
                if (!current.isAttachedToWindow || !current.isLaidOut ||
                    current.width <= 0 || current.height <= 0 || current.isLayoutRequested) return false
                current = current.parent as? View
            }
            return true
        }
        fun find(view: View): Boolean = when (view) {
            is TextView -> view.text.toString().startsWith(prefix) && laidOut(view)
            is ViewGroup -> (0 until view.childCount).any { find(view.getChildAt(it)) }
            else -> false
        }
        return find(activity.window.decorView)
    }

    private fun currentManual(): ManualMvpActivity? =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<ManualMvpActivity>().firstOrNull()
}
