package com.veycad.app

/** Product styles are montage recipes, not colour filters or director alternatives. */
internal object MontageStyleCatalog {
    enum class Recipe { SIGMA, HEARTBEAT, FEAR_STROBE, DUALITY_LOOP, GALLERY_MONTAGE }

    data class Style(val id: String, val title: String, val description: String,
        val directorStyle: EventMatchingDirector.Style?, val recipe: Recipe?, val sourceCount: Int = 1) {
        init { require(sourceCount in 1..20) }
        val available: Boolean get() = directorStyle != null && recipe != null && isRecipeAvailable(recipe)
        val unavailableLabel: String get() = if (recipe == Recipe.SIGMA) "Временно недоступно" else "В разработке"
    }

    val sigma = Style("sigma", "Сигма", "Живое появление персонажа, тёмный фон и акценты под музыку",
        EventMatchingDirector.Style.DYNAMIC, Recipe.SIGMA)
    val heartbeat = Style("heartbeat", "Heartbeat", "Пульс, повтор музыкальной фразы, эхо и чёрный финал",
        EventMatchingDirector.Style.DYNAMIC, Recipe.HEARTBEAT)
    val fearStrobe = Style("fear_strobe", "FEAR", "Движение, резкие склейки, два строб-блока и финальная вспышка",
        EventMatchingDirector.Style.DYNAMIC, Recipe.FEAR_STROBE)
    val dualityLoop = Style(
        "duality_loop",
        "DUALITY",
        "Две связанные сцены, длинная завязка и ритмичная перекличка",
        EventMatchingDirector.Style.DYNAMIC,
        Recipe.DUALITY_LOOP,
        sourceCount = 2
    )
    val upcoming = Style("upcoming", "Upcoming", "В разработке · следующий стиль", null, null)
    // G6/G7 own public UI and export acceptance. Keep this internal identity out of the picker.
    val galleryMontage = Style("gallery_montage", "Ролик из галереи", "Разные сцены · склейки под музыку",
        EventMatchingDirector.Style.DYNAMIC, Recipe.GALLERY_MONTAGE, sourceCount = 20)
    private val comingSoon = listOf(
        Style("upcoming_lite", "Upcoming Lite", "В разработке · следующий вариант", null, null),
        Style("upcoming_motion", "Next Motion", "В разработке · следующий вариант", null, null),
        Style("upcoming_film", "Next Film", "В разработке · следующий вариант", null, null),
        Style("upcoming_rhythm", "Next Rhythm", "В разработке · следующий вариант", null, null),
        Style("upcoming_portrait", "Next Portrait", "В разработке · следующий вариант", null, null)
    )
    val all = listOf(sigma, heartbeat, fearStrobe, dualityLoop, upcoming) + comingSoon
    val available = all.filter { it.available }
    val minimumStylesForScrollablePicker = 8
    val isScrollableCatalog: Boolean
        get() = all.size >= minimumStylesForScrollablePicker
    fun restore(id: String?): Style = all.firstOrNull { it.id == id && it.available } ?: heartbeat

    fun isRecipeAvailable(recipe: Recipe): Boolean = recipe != Recipe.SIGMA && recipe != Recipe.GALLERY_MONTAGE

    fun requireRecipeAvailable(recipe: Recipe) {
        check(isRecipeAvailable(recipe)) {
            if (recipe == Recipe.SIGMA) "Sigma временно недоступна: работа над стилем приостановлена"
            else "Ролик из галереи пока недоступен: интеграция и проверка экспорта не завершены"
        }
    }
}
