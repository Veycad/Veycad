package com.veycad.app

/** Physical video facts. Declared duration and SHA-256 fingerprint live on the immutable ProjectAsset. */
data class VideoSourceMetadata(val geometry: SourceGeometry, val sizeBytes: Long,
    val mime: String, val colorTransfer: Int?, val hasAudio: Boolean,
    /** Physical measurement; null is UNKNOWN for old constructors/files. */
    val firstVideoPtsUs: Long? = null,
    /** Independently measured exclusive raw endpoint; null is UNKNOWN. */
    val videoEndPtsUs: Long? = null) {
    init {
        require(sizeBytes > 0 && mime.startsWith("video/") && (firstVideoPtsUs == null || firstVideoPtsUs >= 0))
        require(videoEndPtsUs == null || videoEndPtsUs > 0)
        require(firstVideoPtsUs == null || videoEndPtsUs == null || videoEndPtsUs > firstVideoPtsUs)
    }

    companion object {
        fun fromInspected(source: MediaSource, pixelAspectRatio: Float) = VideoSourceMetadata(
            SourceGeometry(source.width, source.height, source.rotationDegrees, pixelAspectRatio),
            source.sizeBytes, source.mime, source.colorTransfer, source.hasAudio, source.firstVideoPtsUs, source.videoEndPtsUs)
    }
}

/** Position in HybridProject.selectedVideos is the durable graph sourceIndex. */
data class SelectedVideo(val id: SourceId, val assetId: String, val ownership: SourceOwnership,
    val captureOrigin: CaptureOrigin? = null, val displayName: String) {
    init {
        require(assetId.isNotBlank() && displayName.isNotBlank())
        require((ownership == SourceOwnership.CAPTURE) == (captureOrigin != null))
    }

    companion object {
        fun fromInspected(source: MediaSource, asset: ProjectAsset, ownership: SourceOwnership,
            captureOrigin: CaptureOrigin? = null): SelectedVideo {
            val metadata = requireNotNull(asset.videoMetadata)
            require(asset.kind == ProjectAsset.Kind.VIDEO && asset.durationUs == source.durationUs &&
                asset.contentHash == source.fingerprint)
            require(metadata == VideoSourceMetadata.fromInspected(source, metadata.geometry.pixelAspectRatio))
            return SelectedVideo(SourceId(source.id), asset.id, ownership, captureOrigin, source.displayName)
        }
    }
}
