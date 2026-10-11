package com.veycad.app

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/** Single source of truth for rotation at decoder/player Surface boundaries. */
internal object VideoDisplayOrientation {
    fun cropsForFiles(files: List<File>, width: Int, height: Int,
        checkCancelled: () -> Unit = {}): List<SourceFraming.Crop> = files.map { file ->
        checkCancelled()
        cropForFile(file, width, height)
    }

    fun cropForFile(file: File, width: Int, height: Int): SourceFraming.Crop {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            fun integer(key: String, fallback: Int) = if (format.containsKey(key)) format.getInteger(key) else fallback
            val sarWidth = integer("sar-width", 1)
            val sarHeight = integer("sar-height", 1)
            return SourceFraming.crop(format.getInteger(MediaFormat.KEY_WIDTH),
                format.getInteger(MediaFormat.KEY_HEIGHT), integer(MediaFormat.KEY_ROTATION, 0),
                width, height, sarWidth.toFloat() / sarHeight)
        } finally { extractor.release() }
    }


    /**
     * SurfaceTexture.getTransformMatrix() already converts decoder texture coordinates into
     * OpenGL space. Supplying a second top-to-bottom flip before that matrix rotates Samsung
     * CameraX frames upside down even when their MP4 track matrix is identity.
     */
    fun externalOesTextureCoordinates(): FloatArray = floatArrayOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f
    )

    /**
     * MediaCodec applies MediaFormat.KEY_ROTATION when decoded frames are rendered to a Surface.
     * The GLES geometry therefore contains only the authored virtual-camera rotation. Adding the
     * MP4 rotation again turns CameraX captures with a 180-degree hint upside down.
     */
    fun rendererGeometryRotation(authoredDegrees: Float, sourceMetadataDegrees: Int): Float {
        require(sourceMetadataDegrees in setOf(0, 90, 180, 270))
        return authoredDegrees
    }
}
