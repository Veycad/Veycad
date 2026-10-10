package com.veycad.app

/** Product-owned copy only: no Android views, rendering policy or acceptance decisions. */
internal object MontageStylePresentation {
    data class Copy(
        val symbol: String,
        val title: String,
        val subtitle: String,
        val sourceCount: Int,
        val minimumSourceDurationMs: Long?,
        val unavailableLabel: String? = null
    ) {
        val selectorLabel: String get() = "$symbol   $title\n$subtitle"
        val addSourceLabel: String get() = if (sourceCount == 2) "Добавить 2 видео" else "Добавить видео"
        val sourceHint: String get() {
            unavailableLabel?.let { return it }
            val seconds = requireNotNull(minimumSourceDurationMs) / 1_000L
            return if (sourceCount == 2) "2 видео · каждое от $seconds секунд"
            else "1 видео · от $seconds секунд"
        }
        val musicPlaceholder: String get() = unavailableLabel?.let { "♫ $it" }
            ?: "♫ Музыка для $title подберётся автоматически"
        val musicLoading: String get() = unavailableLabel?.let { "♫ $it" }
            ?: "♫ Подбираю музыку для $title…"

        fun sourceDisplay(names: List<String>): String = when {
            names.isEmpty() -> sourceHint
            sourceCount == 2 && names.size >= 2 -> "1 · ${names[0]}\n2 · ${names[1]}"
            sourceCount == 2 -> "Выбрано 1 из 2 · ${names[0]}"
            else -> names[0]
        }
    }

    /** UI request identity: a late music callback must not undo a reset or a newer choice. */
    class MusicRequests {
        class Ticket internal constructor(val styleId: String, internal val generation: Long)
        private var generation = 0L

        fun begin(styleId: String): Ticket = Ticket(styleId, ++generation)
        fun invalidate() { generation++ }
        fun isCurrent(ticket: Ticket, selectedStyleId: String): Boolean =
            ticket.generation == generation && ticket.styleId == selectedStyleId
    }

    /** Import order and stored display names both use source-0 as the primary, not the newest file. */
    fun <T> restoredSources(orderedSources: List<T>, style: MontageStyleCatalog.Style): List<T> =
        orderedSources.take(style.sourceCount)

    fun forStyle(style: MontageStyleCatalog.Style): Copy {
        val (symbol, subtitle) = when (style.recipe) {
            MontageStyleCatalog.Recipe.SIGMA -> "🔒" to style.unavailableLabel
            MontageStyleCatalog.Recipe.HEARTBEAT -> "♥" to "Пульс · повтор фразы · чёрный финал"
            MontageStyleCatalog.Recipe.FEAR_STROBE -> "⚡" to "Движение · склейки · строб-акценты"
            MontageStyleCatalog.Recipe.DUALITY_LOOP -> "↔" to "Две связанные сцены · перекличка"
            null -> "○" to style.description
        }
        return Copy(
            symbol, style.title, subtitle, style.sourceCount,
            minimumSourceDurationMs = when {
                !style.available -> null
                style.recipe == MontageStyleCatalog.Recipe.DUALITY_LOOP ->
                    DualityLoopProfile.MINIMUM_SOURCE_DURATION_MS
                else -> EditDurationPolicy.MINIMUM_MS
            },
            unavailableLabel = style.unavailableLabel.takeIf { !style.available }
        )
    }
}
