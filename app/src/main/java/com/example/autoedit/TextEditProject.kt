package com.veycad.app

import java.util.UUID

enum class TextPosition { TOP, CENTER, BOTTOM }
enum class TextFont { SANS, BOLD, SERIF, MONO }
enum class TextAnimation { NONE, FADE, SLIDE, SCALE }
enum class TextPlate { NONE, DARK, ACCENT }
enum class TextLayerKind { LEGACY, TITLE, PLATE, SUBTITLE }
object TextLimits {
    const val TITLE_CODE_POINTS=180
    fun codePoints(text:String)=text.codePointCount(0,text.length)
}

data class TextStyle(
    val position: TextPosition = TextPosition.TOP,
    val font: TextFont = TextFont.BOLD,
    val sizeRatio: Float = 0.055f,
    val color: Int = 0xffffffff.toInt(),
    val darkPlate: Boolean = true,
    val animation: TextAnimation = TextAnimation.NONE,
    // Null preserves the original Boolean contract, including copy(darkPlate=...).
    val plate: TextPlate? = null
) {
    init { require(sizeRatio in 0.025f..0.12f) }
    val resolvedPlate:TextPlate get()=plate ?: if(darkPlate) TextPlate.DARK else TextPlate.NONE
}

data class TextLayer(
    val id: String,
    val text: String,
    val startUs: Long,
    val endUs: Long,
    val style: TextStyle = TextStyle(),
    val kind:TextLayerKind = TextLayerKind.LEGACY
) {
    init {
        require(id.isNotBlank() && startUs >= 0 && endUs > startUs); require(text.length <= 2000)
        if(kind==TextLayerKind.TITLE) require(TextLimits.codePoints(text)<=TextLimits.TITLE_CODE_POINTS)
    }
    val maxLines:Int get()=if(kind==TextLayerKind.TITLE) 3 else 2
}

data class CaptionCue(val id: String, val text: String, val startUs: Long, val endUs: Long,
    val confidence:Double?=null,val manuallyReviewed:Boolean=false,val confidenceCalibrated:Boolean=false) {
    init {
        require(id.isNotBlank() && startUs >= 0 && endUs > startUs); require(text.length <= 2000)
        require(confidence==null || (confidence.isFinite() && confidence in 0.0..1.0))
        require(!confidenceCalibrated || confidence!=null)
    }
    val needsReview:Boolean get()=!manuallyReviewed && (!confidenceCalibrated || confidence==null || confidence<.75)
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
    val language: String = "auto",
    val sourceOriginUs: Long = 0
) {
    init {
        require(sourcePath.isNotBlank() && durationUs > 0 && width > 0 && height > 0)
        require(layers.all { it.endUs <= durationUs } && captions.all { it.endUs <= durationUs })
        require(layers.map { it.id }.distinct().size == layers.size)
        require(captions.map { it.id }.distinct().size == captions.size)
        require(language in setOf("auto", "ru", "en"))
        require(sourceOriginUs>=0)
    }
    fun hook(text: String) = TextLayer(UUID.randomUUID().toString(), text, 0, minOf(3_000_000, durationUs),kind=TextLayerKind.TITLE)
    fun activeLayers(timeUs: Long): List<TextLayer> =
        layers.filter { timeUs >= it.startUs && timeUs < it.endUs && it.text.isNotBlank() } +
            captions.filter { timeUs >= it.startUs && timeUs < it.endUs && it.text.isNotBlank() }
                .map { TextLayer(it.id, it.text, it.startUs, it.endUs, captionStyle,TextLayerKind.SUBTITLE) }
}
