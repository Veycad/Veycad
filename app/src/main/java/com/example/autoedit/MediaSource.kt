package com.veycad.app

import java.io.File
import java.util.Collections

/** Independent container presentation evidence, never inferred from KEY_DURATION. */
data class VideoPresentationBounds(val firstPtsUs: Long, val endPtsUs: Long, val timingVersion: Int = 1) {
    init { require(firstPtsUs >= 0 && endPtsUs > firstPtsUs && timingVersion == 1) }
}

/** Identity belongs to a selection; equal fingerprints need not have equal source IDs. */
data class MediaSource(
    val id: String,
    val file: File,
    val displayName: String,
    /** Legacy declared duration (or legacy estimate); its clock convention is not inferred. */
    val durationUs: Long,
    val sizeBytes: Long,
    val rotationDegrees: Int,
    val width: Int,
    val height: Int,
    val mime: String,
    val colorTransfer: Int?,
    val hasAudio: Boolean,
    val fingerprint: String,
    val firstVideoPtsUs: Long = 0L,
    /** Null means independently verified presentation timing is unavailable. */
    val videoPresentationBounds: VideoPresentationBounds? = null
) {
    init {
        require(id.isNotBlank() && displayName.isNotBlank())
        require(durationUs > 0 && sizeBytes > 0)
        require(firstVideoPtsUs >= 0 && durationUs <= Long.MAX_VALUE - firstVideoPtsUs) {
            "Некорректные границы PTS исходного видео"
        }
        require(videoPresentationBounds == null || videoPresentationBounds.firstPtsUs == firstVideoPtsUs)
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(width > 0 && height > 0 && mime.startsWith("video/"))
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    /** Describes the supported input matrix, not artistic or device acceptance. */
    val isBaselineInput: Boolean get() = mime == "video/avc" && (colorTransfer == null || colorTransfer == 3)

    /** Absolute endpoint requires independent evidence; legacy duration is not that proof. */
    val videoEndPtsUs: Long get() = requireNotNull(videoPresentationBounds) {
        "Границы времени этой видеодорожки пока не поддерживаются"
    }.endPtsUs
    val videoContentDurationUs: Long get() = videoEndPtsUs - firstVideoPtsUs
}

/** Graph source indices refer to this snapshot, never to a caller-owned mutable list. */
class MediaSourceSet(items: List<MediaSource>, val schemaVersion: Int = 1) {
    val items: List<MediaSource> = Collections.unmodifiableList(ArrayList(items))

    init {
        require(schemaVersion == 1) { "Неподдерживаемая версия списка исходников" }
        require(this.items.map { it.id }.toSet().size == this.items.size) { "Повторяющиеся ID исходников" }
    }
}

object GalleryImportPolicy {
    const val MAX_FILES = 20
    const val MAX_DURATION_US = 1_800_000_000L
    const val MIN_SOURCE_US = 500_000L

    fun validate(sources: MediaSourceSet) {
        require(sources.items.size in 1..MAX_FILES) { "Выберите от 1 до $MAX_FILES видео" }
        var remainingUs = MAX_DURATION_US
        for (source in sources.items) {
            val usableUs = source.videoContentDurationUs
            require(usableUs >= MIN_SOURCE_US) { "${source.displayName}: видео короче 0,5 секунды" }
            // Subtract before accumulating: even Long.MAX_VALUE cannot wrap the total.
            require(usableUs <= remainingUs) { "Общая длительность видео превышает 30 минут" }
            remainingUs -= usableUs
        }
    }
}
