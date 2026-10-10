package com.veycad.app

import java.util.Collections

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

/** Text values use absolute project microseconds, with end-exclusive intervals. */
class HybridTextState(
    layers: List<TextLayer> = emptyList(),
    captions: List<CaptionCue> = emptyList(),
    val captionStyle: TextStyle = TextStyle(position = TextPosition.BOTTOM, sizeRatio = 0.045f),
    val captionsEdited: Boolean = false,
    val language: String = "auto"
) {
    val layers: List<TextLayer> = Collections.unmodifiableList(ArrayList(layers))
    val captions: List<CaptionCue> = Collections.unmodifiableList(ArrayList(captions))

    init {
        require(this.layers.map { it.id }.distinct().size == this.layers.size)
        require(this.captions.map { it.id }.distinct().size == this.captions.size)
        require(language in setOf("auto", "ru", "en"))
    }

    fun copy(layers: List<TextLayer> = this.layers, captions: List<CaptionCue> = this.captions,
        captionStyle: TextStyle = this.captionStyle, captionsEdited: Boolean = this.captionsEdited,
        language: String = this.language) = HybridTextState(layers, captions, captionStyle, captionsEdited, language)

    fun activeLayers(projectTimeUs: Long): List<TextLayer> =
        layers.filter { projectTimeUs >= it.startUs && projectTimeUs < it.endUs && it.text.isNotBlank() } +
            captions.filter { projectTimeUs >= it.startUs && projectTimeUs < it.endUs && it.text.isNotBlank() }
                .map { TextLayer(it.id, it.text, it.startUs, it.endUs, captionStyle) }

    private fun values() = listOf(layers, captions, captionStyle, captionsEdited, language)
    override fun equals(other: Any?): Boolean = other is HybridTextState && values() == other.values()
    override fun hashCode(): Int = values().hashCode()
    override fun toString(): String = "HybridTextState(layers=$layers, captions=$captions, captionStyle=$captionStyle, captionsEdited=$captionsEdited, language=$language)"
}
