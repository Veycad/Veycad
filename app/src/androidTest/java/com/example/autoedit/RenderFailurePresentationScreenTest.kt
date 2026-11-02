package com.veycad.app

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class RenderFailurePresentationScreenTest {
    @get:Rule val ui = UiTestFixtureRule()

    @Test fun unknown_failures_show_retry_copy_keep_safe_diagnostics_and_survive_recreation() {
        ui.launch()
        ui.importVideos("codec.mp4")
        val expected = "Не удалось завершить операцию. Попробуй ещё раз. " +
            "Если ошибка повторится, выбери другое видео или стиль."
        val marker = "private-error-copy-marker"
        val failures = listOf(
            IllegalStateException("Codec failed: $marker", java.io.IOException("source $marker")),
            IllegalArgumentException("configure returned 0xffffffed: $marker"))
        for (failure in failures) {
            ui.startRender()
            ui.renderer.fail(failure)
            ui.awaitIdle()
            assertEquals("Unknown failure must show recovery copy", expected, ui.text(R.id.renderHint))
            onView(withId(R.id.renderHint)).check(matches(isDisplayed()))
            assertEquals(expected, EditSession.get(ui.context).state.value?.error)
            val event = LocalDiagnostics.recent(ui.context).map(::JSONObject)
                .last { it.optString("event") == "render_failed" }
            assertEquals(failure.javaClass.name, event.getString("exception"))
            assertEquals((failure.cause ?: failure).javaClass.name, event.getString("root_exception"))
            assertFalse(event.toString().contains(marker))
            assertNull(CompletedRenderStore.latest(ui.context.filesDir))
            onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        }
        ui.recreate()
        ui.awaitText(R.id.renderHint, expected)
        ui.startRender()
        ui.renderer.fail(IllegalStateException("retry $marker"))
        ui.awaitIdle()
        ui.awaitText(R.id.renderHint, expected)
    }

    @Test fun owned_budget_failure_keeps_the_action_the_user_can_take() {
        ui.launch()
        ui.importVideos("over-budget.mp4")
        ui.startRender()
        val failure = assertThrows(IllegalArgumentException::class.java) {
            SourceAnalysisBudget.preflight(60_000_001, 250_000, Long.MAX_VALUE)
        }
        ui.renderer.fail(failure)
        ui.awaitIdle()
        ui.awaitText(R.id.renderHint,
            "Для анализа поддерживаются видео до 60 секунд. Сократи видео и повтори монтаж.")
        assertNull(CompletedRenderStore.latest(ui.context.filesDir))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
    }

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
                "Не удалось завершить подбор измеренных фрагментов для Heartbeat. Это не означает, что видео непригодно",
            HeartbeatCandidateDomainExhaustedException(trace.copy(finiteDomainExhausted = true)) to
                "Heartbeat не нашёл подходящую комбинацию среди проверенных измеренных фрагментов. Это не означает, что видео непригодно"
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
