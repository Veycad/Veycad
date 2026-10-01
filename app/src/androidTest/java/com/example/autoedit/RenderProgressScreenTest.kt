package com.example.autoedit

import android.content.Context
import android.widget.ProgressBar
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Controls only expensive render timing; production jobs, progress observers and publication run. */
@RunWith(AndroidJUnit4::class)
class RenderProgressScreenTest {
    @get:Rule val ui = UiTestFixtureRule()

    @Test fun every_render_stage_has_its_copy_and_indeterminate_or_exact_candidate_percentage() {
        ui.launch()
        ui.importVideos("progress.mp4")
        ui.startRender()
        var activity: MainActivity? = null
        ui.scenario.onActivity { activity = it }
        val progress = requireNotNull(activity).findViewById<ProgressBar>(R.id.renderProgress)
        ui.awaitText(R.id.progressTitle, "Анализирую видео")
        ui.waitUntil("analysis progress is indeterminate") { progress.isIndeterminate }
        onView(withId(R.id.progressDetail)).check(matches(withSubstring("лица, движение, композицию")))

        ui.renderer.progress(VeycadAutomaticEditor.Stage.DIRECTING, 0, 4)
        ui.awaitText(R.id.progressTitle, "Строю режиссуру")
        ui.waitUntil("directing progress is indeterminate") { progress.isIndeterminate }
        onView(withId(R.id.progressDetail)).check(matches(withSubstring("ритмом музыки")))

        ui.renderer.progress(VeycadAutomaticEditor.Stage.RENDERING, 1, 4)
        ui.awaitText(R.id.progressTitle, "Рендерю варианты")
        ui.awaitText(R.id.progressDetail, "Кандидат 2 из 4")
        ui.waitUntil("one of four candidates gives 25 percent") { !progress.isIndeterminate && progress.progress == 25 }

        ui.renderer.progress(VeycadAutomaticEditor.Stage.VERIFYING, 3, 4)
        ui.awaitText(R.id.progressTitle, "Проверяю результат")
        ui.awaitText(R.id.progressDetail, "Готово 3 из 4")
        ui.waitUntil("three of four verified candidates gives 75 percent") { !progress.isIndeterminate && progress.progress == 75 }

        ui.renderer.progress(VeycadAutomaticEditor.Stage.COMPLETE, 4, 4)
        ui.awaitText(R.id.progressTitle, "Монтаж готов")
        ui.awaitText(R.id.progressDetail, "Выбран лучший вариант")
        ui.waitUntil("complete progress is 100 percent") { !progress.isIndeterminate && progress.progress == 100 }
        ui.renderer.complete()
        ui.awaitIdle()
        ui.awaitDisplayed(R.id.resultContent)
        onView(withId(R.id.progressPanel)).check(matches(not(isDisplayed())))
        onView(withId(R.id.resultSummary)).check(matches(withSubstring("проверено")))
    }

    @Test fun a_running_render_blocks_import_style_collection_settings_and_duplicate_render_actions() {
        ui.launch()
        ui.importVideos("busy-controls.mp4")
        ui.startRender()
        val request = requireNotNull(ui.renderer.currentRequest)
        val intentCount = Intents.getIntents().size
        for (id in listOf(R.id.selectVideoButton, R.id.selectStyleButton, R.id.renderButton,
            R.id.myEditsButton, R.id.newEditButton)) {
            onView(withId(id)).check(matches(not(isEnabled())))
        }
        ui.click(R.id.selectVideoButton)
        ui.click(R.id.selectStyleButton)
        ui.click(R.id.renderButton)
        ui.click(R.id.myEditsButton)
        ui.click(R.id.allStylesButton)
        onView(withText("Выбрать стиль")).check(doesNotExist())
        ui.click(R.id.settingsButton)
        onView(withId(R.id.settingsContent)).check(matches(not(isDisplayed())))
        onView(withId(R.id.myEditsContent)).check(matches(not(isDisplayed())))
        onView(withId(R.id.directorContent)).check(matches(isDisplayed()))
        assertEquals("No gallery should open while the source is in use", intentCount, Intents.getIntents().size)
        assertSame(request, ui.renderer.currentRequest)
        assertEquals(1, ui.renderer.requestCount)
        ui.click(R.id.cancelRenderButton)
        ui.awaitIdle()
        onView(withId(R.id.selectVideoButton)).check(matches(isEnabled()))
        onView(withId(R.id.selectStyleButton)).check(matches(isEnabled()))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        onView(withId(R.id.myEditsButton)).check(matches(isEnabled()))
        onView(withId(R.id.cancelRenderButton)).check(matches(not(isDisplayed())))
    }

    @Test fun cancellation_unwinds_scratch_and_keeps_the_draft_previous_result_and_saved_library() {
        val previous = ui.seedCompleted("previous-result.mp4")
        val saved = ui.seedSaved("preserved-library.mp4")
        val previousBytes = previous.file.readBytes()
        val savedBytes = saved.readBytes()
        ui.launch()
        ui.awaitDisplayed(R.id.resultContent)
        pressBack()
        ui.importVideos("cancel-source.mp4")
        val source = RenderWorkspace.sourceDraftDirectory(ui.context.filesDir).listFiles().orEmpty().single()
        val sourceBytes = source.readBytes()
        ui.startRender()
        val request = requireNotNull(ui.renderer.currentRequest)
        val scratch = File(requireNotNull(request.outputFile.parentFile), "candidate.partial").apply { writeText("partial") }
        assertTrue(requireNotNull(request.jobLease).isFile)
        ui.click(R.id.cancelRenderButton)
        ui.awaitIdle()
        onView(withId(R.id.videoSelection)).check(matches(withText("cancel-source.mp4")))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        assertEquals("Монтаж отменён", EditSession.get(ui.context).state.value?.error)
        assertFalse(scratch.exists())
        assertFalse(request.outputFile.exists())
        assertFalse(requireNotNull(request.jobLease).exists())
        assertTrue(RenderWorkspace.pendingRendersDirectory(ui.context.filesDir).listFiles().orEmpty().isEmpty())
        assertArrayEquals(sourceBytes, source.readBytes())
        assertArrayEquals(previousBytes, previous.file.readBytes())
        assertArrayEquals(savedBytes, saved.readBytes())
        assertEquals(previous, CompletedRenderStore.latest(ui.context.filesDir))
        assertFalse(ui.context.getSharedPreferences("edit_session", Context.MODE_PRIVATE).getBoolean("running", true))
    }

    @Test fun render_failure_preserves_the_previous_result_and_readiness_then_a_retry_can_finish() {
        val previous = ui.seedCompleted("before-failure.mp4")
        ui.launch()
        ui.awaitDisplayed(R.id.resultContent)
        pressBack()
        ui.importVideos("retry-source.mp4")
        ui.startRender()
        val failedRequest = requireNotNull(ui.renderer.currentRequest)
        ui.renderer.fail("Тестовая ошибка рендера")
        ui.awaitText(R.id.renderHint, "Тестовая ошибка рендера")
        onView(withId(R.id.renderHint)).perform(scrollTo()).check(matches(isDisplayed()))
        ui.awaitIdle()
        ui.waitUntil("failure message published") { EditSession.get(ui.context).state.value?.error == "Тестовая ошибка рендера" }
        onView(withId(R.id.progressPanel)).check(matches(not(isDisplayed())))
        onView(withId(R.id.videoSelection)).check(matches(withText("retry-source.mp4")))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        assertEquals(previous, CompletedRenderStore.latest(ui.context.filesDir))
        assertTrue(previous.file.exists())
        assertFalse(requireNotNull(failedRequest.outputFile.parentFile).exists())
        ui.startRender()
        val retry = requireNotNull(ui.renderer.currentRequest)
        assertNotEquals(failedRequest.outputFile, retry.outputFile)
        assertEquals(failedRequest.sourceFile, retry.sourceFile)
        ui.renderer.complete()
        ui.awaitIdle()
        ui.awaitDisplayed(R.id.resultContent)
        val completed = requireNotNull(CompletedRenderStore.latest(ui.context.filesDir))
        assertNotEquals(previous.file, completed.file)
        assertTrue(completed.file.length() > 0L)
        assertNull(EditSession.get(ui.context).state.value?.error)
        onView(withId(R.id.resultSummary)).check(matches(withSubstring("Heartbeat")))
    }

    @Test fun activity_recreation_keeps_the_same_running_request_and_its_current_progress() {
        ui.launch()
        ui.importVideos("recreate-during-render.mp4")
        ui.startRender()
        val request = requireNotNull(ui.renderer.currentRequest)
        ui.renderer.progress(VeycadAutomaticEditor.Stage.RENDERING, 1, 3)
        ui.awaitText(R.id.progressDetail, "Кандидат 2 из 3")
        ui.recreate()
        ui.awaitDisplayed(R.id.directorContent)
        ui.awaitText(R.id.progressDetail, "Кандидат 2 из 3")
        onView(withId(R.id.renderButton)).check(matches(not(isEnabled())))
        var activity: MainActivity? = null
        ui.scenario.onActivity { activity = it }
        ui.waitUntil("restored progress is 33 percent") {
            val progress = requireNotNull(activity).findViewById<ProgressBar>(R.id.renderProgress)
            !progress.isIndeterminate && progress.progress == 33
        }
        assertSame(request, ui.renderer.currentRequest)
        assertEquals(1, ui.renderer.requestCount)
        assertEquals(request.outputFile.parentFile,
            RenderWorkspace.pendingRendersDirectory(ui.context.filesDir).listFiles().orEmpty().single())
        ui.renderer.complete()
        ui.awaitIdle()
        ui.awaitDisplayed(R.id.resultContent)
        assertSame(request, ui.renderer.currentRequest)
        assertEquals(1, ui.renderer.requestCount)
        assertTrue(RenderWorkspace.pendingRendersDirectory(ui.context.filesDir).listFiles().orEmpty().isEmpty())
    }

    @Test fun a_quality_warning_is_shown_explicitly_and_does_not_disable_saving_or_sharing() {
        ui.launch()
        ui.importVideos("quality-warning.mp4")
        ui.startRender()
        ui.renderer.passedQualityGate = false
        ui.renderer.complete()
        ui.awaitIdle()
        ui.awaitDisplayed(R.id.resultContent)
        onView(withId(R.id.resultSummary)).check(matches(withSubstring("готово с предупреждением QA")))
        onView(withId(R.id.resultSummary)).check(matches(withSubstring("test quality warning")))
        onView(withId(R.id.saveButton)).check(matches(isEnabled()))
        onView(withId(R.id.shareButton)).check(matches(isEnabled()))
        assertTrue(requireNotNull(CompletedRenderStore.latest(ui.context.filesDir)).file.length() > 0L)
    }
}
