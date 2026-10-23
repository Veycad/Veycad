package com.veycad.app

import java.io.File

/** Owned transient files from one composition. Borrow speechPcmFile for the existing 16 kHz
 * mono recognizer, then mux a video-only render with aacFile. Neither borrower may delete them.
 * Close after STT/render/mux (also on cancellation). No project/store/recognition state lives here. */
internal class SourceAacPreparation internal constructor(
    val aacFile: File,
    val speechPcmFile: File,
    val sampleFrames: Long,
    val compositionStats: PcmCompositionStats,
    val peakAacPacketBytes: Int
) : AutoCloseable {
    val durationUs: Long get() = AudioExportPlan.presentationTimeUs(sampleFrames, 48_000)
    private var closed = false
    internal fun requireOpen() { check(!closed && aacFile.isFile && speechPcmFile.isFile) { "Prepared audio has been closed" } }
    override fun close() {
        if (closed) return
        closed = true
        try { java.nio.file.Files.deleteIfExists(aacFile.toPath()) }
        finally { java.nio.file.Files.deleteIfExists(speechPcmFile.toPath()) }
    }
}
