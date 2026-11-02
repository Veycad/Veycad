package com.veycad.app

import java.util.concurrent.CancellationException

/** User-facing copy only: search outcomes keep their diagnostic types and evidence scope. */
internal object MontageFailurePresentation {
    private const val UNKNOWN_FAILURE = "Не удалось завершить операцию. Попробуй ещё раз. " +
        "Если ошибка повторится, выбери другое видео или стиль."
    fun message(error: Throwable, sourceCount: Int): String = when {
        // This subtype describes only the finite measured candidate family, not the whole video.
        error is HeartbeatCandidateDomainExhaustedException ->
            "Не удалось собрать Heartbeat из проверенных фрагментов. " +
                "Попробуй другой стиль или другое видео. Этот результат не означает, что исходник непригоден"
        error is HeartbeatCandidateSearchIncompleteException ->
            "Не удалось закончить подбор фрагментов для Heartbeat. " +
                "Попробуй другой стиль или другое видео. Этот результат не означает, что исходник непригоден"
        error is MaterialRejectedException && error.code == "insufficient_duration" && sourceCount == 2 ->
            "Для DUALITY каждое видео должно быть не короче 6 секунд"
        error is MaterialRejectedException && error.code == "insufficient_duration" ->
            "Нужно видео длительностью не менее 15 секунд"
        error is MaterialRejectedException && error.code == "insufficient_human_evidence" && sourceCount == 2 ->
            "Не удалось уверенно обнаружить человека ни в одном из двух видео"
        error is MaterialRejectedException && error.code == "insufficient_human_evidence" ->
            "Не удалось уверенно обнаружить человека. Выберите видео с хорошо видимым героем"
        error is MaterialRejectedException && error.code == "insufficient_distinct_moments" ->
            "Недостаточно разных читаемых моментов для выбранного стиля"
        error is MaterialRejectedException && error.code == "insufficient_motion" ->
            "Не подтверждена непрерывная сцена с заметным движением для FEAR"
        error is MaterialRejectedException && error.code == "insufficient_motion_evidence" ->
            "Не удалось надёжно измерить движение для FEAR. Это не означает, что герой неподвижен"
        error is CancellationException -> error.message?.takeIf { it.isNotBlank() } ?: "Операция отменена"
        error is UserMessageException || error is MaterialRejectedException ->
            error.message?.takeIf { it.isNotBlank() } ?: UNKNOWN_FAILURE
        else -> UNKNOWN_FAILURE
    }
}
