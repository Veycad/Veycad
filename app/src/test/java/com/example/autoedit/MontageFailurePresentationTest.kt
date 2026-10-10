package com.veycad.app

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Pure copy tests; these do not claim source suitability or rendered/human acceptance. */
class MontageFailurePresentationTest {
    @Test fun finite_measured_family_is_not_presented_as_a_whole_source_shortfall() {
        val error = HeartbeatCandidateDomainExhaustedException(trace(exhausted = true))
        assertEquals("insufficient_distinct_moments", error.code)
        for (sourceCount in listOf(1, 2)) {
            val message = MontageFailurePresentation.message(error, sourceCount)
            assertEquals("Heartbeat не нашёл подходящую комбинацию среди проверенных измеренных фрагментов. " +
                "Это не означает, что видео непригодно", message)
            assertFalse(message.contains("Недостаточно разных"))
            assertFalse(message.contains("generated="))
            assertFalse(message.contains("exhausted=true"))
        }
        assertTrue(error.message.orEmpty().contains("exhausted=true"))
        assertTrue(error.trace.finiteDomainExhausted)
    }

    @Test fun ordinary_distinct_moment_shortfall_keeps_its_existing_message() {
        val error = MaterialRejectedException("insufficient_distinct_moments", "director rejection")
        assertEquals("Недостаточно разных читаемых моментов для выбранного стиля",
            MontageFailurePresentation.message(error, 1))
    }

    @Test fun computational_limit_is_not_a_material_rejection_and_hides_debug_counters() {
        val error = HeartbeatCandidateSearchIncompleteException(trace(exhausted = false))
        val message = MontageFailurePresentation.message(error, 1)
        assertEquals("Не удалось завершить подбор измеренных фрагментов для Heartbeat. " +
            "Это не означает, что видео непригодно", message)
        assertFalse(message.contains("visits="))
        assertFalse(message.contains("validations="))
        assertTrue(error.message.orEmpty().contains("visits=17/20"))
        assertTrue(error.message.orEmpty().contains("validations=2/3"))
        assertFalse(error.trace.finiteDomainExhausted)
    }

    @Test fun single_source_duration_keeps_fifteen_seconds() {
        assertEquals("Нужно видео длительностью не менее 15 секунд",
            MontageFailurePresentation.message(MaterialRejectedException("insufficient_duration", "duration"), 1))
    }

    @Test fun duality_duration_keeps_six_seconds_for_each_source() {
        assertEquals("Для DUALITY каждое видео должно быть не короче 6 секунд",
            MontageFailurePresentation.message(MaterialRejectedException("insufficient_duration", "duration"), 2))
    }

    @Test fun human_evidence_copy_keeps_single_and_both_source_meanings() {
        val error = MaterialRejectedException("insufficient_human_evidence", "human evidence")
        assertEquals("Не удалось уверенно обнаружить человека. Выберите видео с хорошо видимым героем",
            MontageFailurePresentation.message(error, 1))
        assertEquals("Не удалось уверенно обнаружить человека ни в одном из двух видео",
            MontageFailurePresentation.message(error, 2))
    }

    @Test fun known_fear_motion_shortfall_keeps_continuity_requirement() {
        assertEquals("Не подтверждена непрерывная сцена с заметным движением для FEAR",
            MontageFailurePresentation.message(MaterialRejectedException("insufficient_motion", "motion"), 1))
    }

    @Test fun unavailable_fear_measurement_is_not_rewritten_as_still_subject() {
        assertEquals("Не удалось надёжно измерить движение для FEAR. Это не означает, что герой неподвижен",
            MontageFailurePresentation.message(MaterialRejectedException("insufficient_motion_evidence", "unknown"), 1))
    }

    @Test fun legacy_duration_fallbacks_keep_their_text_and_precedence() {
        assertEquals("Нужно видео длительностью не менее 15 секунд",
            MontageFailurePresentation.message(IllegalArgumentException("at least 15000 ms"), 2))
        assertEquals("Для DUALITY каждое видео должно быть не короче 6 секунд",
            MontageFailurePresentation.message(IllegalArgumentException("at least 6000 ms"), 1))
        assertEquals("Нужно видео длительностью не менее 15 секунд",
            MontageFailurePresentation.message(IllegalArgumentException("at least 6000 and at least 15000"), 2))
    }

    @Test fun untyped_codec_or_execution_failure_does_not_become_a_material_shortfall() {
        val message = "Codec failed: insufficient_distinct_moments / insufficient_motion_evidence"
        assertEquals(message, MontageFailurePresentation.message(IllegalStateException(message), 1))
    }

    @Test fun unknown_material_code_retains_its_original_message() {
        assertEquals("Музыка не подходит для выбранного стиля",
            MontageFailurePresentation.message(MaterialRejectedException("music", "Музыка не подходит для выбранного стиля"), 1))
    }

    @Test fun cancellation_retains_its_existing_message_without_becoming_a_negative_case() {
        assertEquals("Операция отменена",
            MontageFailurePresentation.message(CancellationException("Операция отменена"), 1))
    }

    @Test fun empty_or_blank_exception_message_keeps_generic_fallback() {
        for (message in listOf(null, "", " \t\n")) {
            assertEquals("Не удалось завершить операцию",
                MontageFailurePresentation.message(IllegalStateException(message), 1))
        }
        assertEquals("  original message  ",
            MontageFailurePresentation.message(IllegalStateException("  original message  "), 1))
    }

    private fun trace(exhausted: Boolean) = HeartbeatSourcePool.SelectionTrace(
        method = "measured-pair-fallback", generatedCandidates = 9, candidateCount = 12,
        candidateVisits = 17L, directorValidations = 2, finiteDomainExhausted = exhausted,
        maxCandidateVisits = 20L, maxDirectorValidations = 3)
}
