package com.veycad.app

import java.util.Collections

// Shared TextLayer/CaptionCue/TextStyle are supplied by the text owner in TextEditProject.kt.
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
                .map { TextLayer(it.id, it.text, it.startUs, it.endUs, captionStyle, TextLayerKind.SUBTITLE) }

    private fun values() = listOf(layers, captions, captionStyle, captionsEdited, language)
    override fun equals(other: Any?): Boolean = other is HybridTextState && values() == other.values()
    override fun hashCode(): Int = values().hashCode()
    override fun toString(): String = "HybridTextState(layers=$layers, captions=$captions, captionStyle=$captionStyle, captionsEdited=$captionsEdited, language=$language)"
}
