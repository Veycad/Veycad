package com.veycad.app

/** User-facing copy only: search outcomes keep their diagnostic types and evidence scope. */
internal object MontageFailurePresentation {
    fun message(error: Throwable, sourceCount: Int): String = when {
        // This subtype describes only the finite measured candidate family, not the whole video.
        error is HeartbeatCandidateDomainExhaustedException ->
            "Heartbeat не нашёл подходящую комбинацию среди проверенных измеренных фрагментов. " +
                "Это не означает, что видео непригодно"
        error is HeartbeatCandidateSearchIncompleteException ->
            "Не удалось завершить подбор измеренных фрагментов для Heartbeat. " +
                "Это не означает, что видео непригодно"
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
        error.message.orEmpty().contains("at least 15000") ->
            "Нужно видео длительностью не менее 15 секунд"
        error.message.orEmpty().contains("at least 6000") ->
            "Для DUALITY каждое видео должно быть не короче 6 секунд"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Не удалось завершить операцию"
    }
}
