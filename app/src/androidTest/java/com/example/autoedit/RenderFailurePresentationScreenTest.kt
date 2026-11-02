package com.veycad.app

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenderFailurePresentationScreenTest {
    @get:Rule val ui = UiTestFixtureRule()

    @Test fun material_refusals_pass_through_the_session_and_screen_with_the_request_source_count() {
        ui.launch()
        for (style in listOf("Heartbeat", "FEAR", "DUALITY")) {
            ui.selectStyle(style)
            if (style == "DUALITY") ui.importVideos("first.mp4", "second.mp4")
            else ui.importVideos("single.mp4")
            ui.startRender()
            val request = requireNotNull(ui.renderer.currentRequest)
            val error = assertThrows(MaterialRejectedException::class.java) {
                MaterialSuitability.checkDuration(request.recipe,
                    List(if (request.secondarySourceFile == null) 1 else 2) { 3_000_000L })
            }
            ui.renderer.fail(error)
            val expected = if (style == "DUALITY")
                "Для DUALITY каждое видео должно быть не короче 6 секунд"
            else "Нужно видео длительностью не менее 15 секунд"
            ui.awaitIdle()
            ui.awaitText(R.id.renderHint, expected)
            assertEquals(expected, EditSession.get(ui.context).state.value?.error)
            assertFalse(request.outputFile.exists())
            onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        }
        ui.recreate()
        ui.awaitText(R.id.renderHint, "Для DUALITY каждое видео должно быть не короче 6 секунд")
    }

    @Test fun heartbeat_search_outcomes_keep_their_distinct_messages_without_debug_counters() {
        ui.launch()
        ui.importVideos("heartbeat.mp4")
        val trace = HeartbeatSourcePool.SelectionTrace("test", 5, 4, 3, 2, false)
        val cases = listOf(
            HeartbeatCandidateSearchIncompleteException(trace) to
                "Не удалось закончить подбор фрагментов для Heartbeat. Попробуй другой стиль или другое видео. Этот результат не означает, что исходник непригоден",
            HeartbeatCandidateDomainExhaustedException(trace.copy(finiteDomainExhausted = true)) to
                "Не удалось собрать Heartbeat из проверенных фрагментов. Попробуй другой стиль или другое видео. Этот результат не означает, что исходник непригоден"
        )
        for ((error, expected) in cases) {
            ui.startRender()
            ui.renderer.fail(error)
            ui.awaitIdle()
            ui.awaitText(R.id.renderHint, expected)
            assertEquals(expected, EditSession.get(ui.context).state.value?.error)
            assertFalse(ui.text(R.id.renderHint).contains("visits="))
            assertNull(CompletedRenderStore.latest(ui.context.filesDir))
            onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        }
    }
}
