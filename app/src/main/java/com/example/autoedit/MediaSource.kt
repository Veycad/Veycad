package com.veycad.app

import java.io.File
import java.util.Collections

/** Identity belongs to a selection; equal fingerprints need not have equal source IDs. */
data class MediaSource(
    val id: String,
    val file: File,
    val displayName: String,
    val durationUs: Long,
    val sizeBytes: Long,
    val rotationDegrees: Int,
    val width: Int,
    val height: Int,
    val mime: String,
    val colorTransfer: Int?,
    val hasAudio: Boolean,
    val fingerprint: String
) {
    init {
        require(id.isNotBlank() && displayName.isNotBlank())
        require(durationUs > 0 && sizeBytes > 0)
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(width > 0 && height > 0 && mime.startsWith("video/"))
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    /** Describes the supported input matrix, not artistic or device acceptance. */
    val isBaselineInput: Boolean get() = mime == "video/avc" && (colorTransfer == null || colorTransfer == 3)
}

/** Graph source indices refer to this snapshot, never to a caller-owned mutable list. */
class MediaSourceSet(items: List<MediaSource>, val schemaVersion: Int = 1) {
    val items: List<MediaSource> = Collections.unmodifiableList(ArrayList(items))

    init {
        require(schemaVersion == 1) { "Неподдерживаемая версия списка исходников" }
        require(this.items.map { it.id }.toSet().size == this.items.size) { "Повторяющиеся ID исходников" }
    }
}
