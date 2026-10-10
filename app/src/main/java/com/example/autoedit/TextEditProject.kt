package com.veycad.app

import java.util.UUID

enum class TextPosition { TOP, CENTER, BOTTOM }
enum class TextFont { SANS, BOLD, SERIF }
enum class TextAnimation { NONE, FADE, SLIDE }

data class TextStyle(
    val position: TextPosition = TextPosition.TOP,
    val font: TextFont = TextFont.BOLD,
    val sizeRatio: Float = 0.055f,
    val color: Int = 0xffffffff.toInt(),
    val darkPlate: Boolean = true,
    val animation: TextAnimation = TextAnimation.NONE
) {
    init { require(sizeRatio in 0.025f..0.12f) }
}

data class TextLayer(
    val id: String,
    val text: String,
    val startUs: Long,
    val endUs: Long,
    val style: TextStyle = TextStyle()
) {
    init { require(id.isNotBlank() && startUs >= 0 && endUs > startUs); require(text.length <= 2000) }
}

data class CaptionCue(val id: String, val text: String, val startUs: Long, val endUs: Long) {
    init { require(id.isNotBlank() && startUs >= 0 && endUs > startUs); require(text.length <= 2000) }
}

data class TextEditProject(
    val sourcePath: String,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val layers: List<TextLayer> = emptyList(),
    val captions: List<CaptionCue> = emptyList(),
    val captionStyle: TextStyle = TextStyle(position = TextPosition.BOTTOM, sizeRatio = 0.045f),
    val captionsEdited: Boolean = false,
    val language: String = "auto"
) {
    init {
        require(sourcePath.isNotBlank() && durationUs > 0 && width > 0 && height > 0)
        require(layers.all { it.endUs <= durationUs } && captions.all { it.endUs <= durationUs })
        require(layers.map { it.id }.distinct().size == layers.size)
        require(captions.map { it.id }.distinct().size == captions.size)
        require(language in setOf("auto", "ru", "en"))
    }
    fun hook(text: String) = TextLayer(UUID.randomUUID().toString(), text, 0, minOf(3_000_000, durationUs))
    fun activeLayers(timeUs: Long): List<TextLayer> =
        layers.filter { timeUs >= it.startUs && timeUs < it.endUs && it.text.isNotBlank() } +
            captions.filter { timeUs >= it.startUs && timeUs < it.endUs && it.text.isNotBlank() }
                .map { TextLayer(it.id, it.text, it.startUs, it.endUs, captionStyle) }
}
