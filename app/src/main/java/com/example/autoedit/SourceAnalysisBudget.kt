package com.veycad.app

/** Conservative full-source costs; the provider never exceeds 480 × 480 ARGB pixels. */
internal object SourceAnalysisBudget {
    const val MAX_INPUT_SIDE = 480
    const val MAX_DURATION_US = 60_000_000L
    private const val MAX_FRAMES = 240L
    private const val MAX_RAW_CACHE_BYTES = 256L * 1024 * 1024
    private const val MAX_ATTACHMENT_BYTES = 128L * 1024 * 1024
    private const val STORAGE_RESERVE_BYTES = 256L * 1024 * 1024

    data class Estimate(val frames: Long, val rawCacheBytes: Long, val attachmentBytes: Long)

    fun preflight(durationUs: Long, intervalUs: Long, usableSpace: Long): Estimate {
        require(durationUs > 0L) { "Не удалось определить длительность видео. Выбери другое видео." }
        require(durationUs <= MAX_DURATION_US) {
            "Для анализа поддерживаются видео до 60 секунд. Сократи видео и повтори монтаж."
        }
        require(intervalUs in 100_000L..1_000_000L) { "Недопустимый шаг анализа видео." }
        require(usableSpace >= 0L) { "Не удалось проверить свободное место для анализа видео." }
        val first = minOf(intervalUs / 2L, durationUs - 1L)
        val frames = 1L + (durationUs - 1L - first) / intervalUs
        val rawCacheBytes = frames * (MAX_INPUT_SIDE.toLong() * MAX_INPUT_SIDE * 4 + 8)
        // Two 256² float planes plus 16 KiB for boxed flow vectors and per-frame metadata.
        val attachmentBytes = frames * (528L * 1024)
        require(frames <= MAX_FRAMES && rawCacheBytes <= MAX_RAW_CACHE_BYTES &&
            attachmentBytes <= MAX_ATTACHMENT_BYTES) {
            "Слишком много кадров для анализа: поддерживается до 240. Сократи видео и повтори монтаж."
        }
        val requiredSpace = rawCacheBytes + STORAGE_RESERVE_BYTES
        require(usableSpace >= requiredSpace) {
            "Недостаточно места для анализа видео. Нужно не менее " +
                "${(requiredSpace + 1024 * 1024 - 1) / (1024 * 1024)} МиБ свободного места " +
                "во внутренней памяти. Освободи место или сократи видео и повтори монтаж."
        }
        return Estimate(frames, rawCacheBytes, attachmentBytes)
    }
}
