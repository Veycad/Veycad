package com.veycad.app

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaInputInspectorTest {
    @get:Rule val fixture = UiTestFixtureRule(grantPermissions = false)

    private fun video(): File {
        val file = File(fixture.context.cacheDir, "actual-video.data")
        fixture.context.contentResolver.openInputStream(fixture.videoUri("inspection.mp4"))!!.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    @Test fun inspectsActualContentUriBytesWithoutTrustingExtension() {
        val file = video()
        val source = MediaInputInspector().inspect("selected", file, "Travel.txt")
        assertEquals("selected", source.id)
        assertEquals("Travel.txt", source.displayName)
        assertEquals(file, source.file)
        assertEquals(file.length(), source.sizeBytes)
        assertEquals(160, source.width)
        assertEquals(240, source.height)
        assertEquals(0, source.rotationDegrees)
        assertEquals("video/avc", source.mime)
        assertFalse(source.hasAudio)
        assertTrue(source.isBaselineInput)
        assertTrue(source.durationUs in 2_950_000L..3_050_000L)
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expectedHash, source.fingerprint)
    }

    @Test fun allQuarterTurnMetadataIsRetainedWithoutSwappingEncodedDimensions() {
        val input = video()
        for (rotation in listOf(0, 90, 180, 270)) {
            val rotated = remux(input, "rotation-$rotation.mp4", rotation = rotation)
            val source = MediaInputInspector().inspect("rotation-$rotation", rotated, rotated.name)
            assertEquals(rotation, source.rotationDegrees)
            assertEquals(160, source.width)
            assertEquals(240, source.height)
        }
    }

    @Test fun shiftedAndVariablePtsDoNotInflateDurationByTheStartOffset() {
        val input = video()
        val shifted = remux(input, "shifted.mp4", startUs = 2_000_000L)
        val source = MediaInputInspector().inspect("shifted", shifted, shifted.name)
        assertTrue("Duration was ${source.durationUs}", source.durationUs in 2_950_000L..3_050_000L)
        val variable = remux(input, "variable.mp4", startUs = 2_000_000L, variablePts = true)
        val vfr = MediaInputInspector().inspect("variable", variable, variable.name)
        assertTrue("Duration was ${vfr.durationUs}", vfr.durationUs in 3_580_000L..3_650_000L)
    }

    @Test fun audioPresenceIsDetectedAndAudioOnlyIsRejected() {
        val audio = File(fixture.context.cacheDir, "music.m4a")
        fixture.context.resources.openRawResource(R.raw.heartbeat_author).use { input ->
            audio.outputStream().use { output -> input.copyTo(output) }
        }
        assertThrows(IllegalArgumentException::class.java) { MediaInputInspector().inspect("audio", audio, audio.name) }
        val combined = remux(video(), "with-audio.mp4", audio = audio)
        assertTrue(MediaInputInspector().inspect("combined", combined, combined.name).hasAudio)
    }

    @Test fun hdrTransferIsRejectedExplicitly() {
        val input = video()
        for (transfer in listOf(6, 7)) {
            val hdr = remux(input, "hdr-$transfer.mp4", colorTransfer = transfer)
            val error = assertThrows(IllegalArgumentException::class.java) {
                MediaInputInspector().inspect("hdr-$transfer", hdr, hdr.name)
            }
            assertTrue(error.message.orEmpty().contains("HDR"))
        }
    }

    @Test fun malformedAndEmptyFilesCannotBecomeSources() {
        for (bytes in listOf(byteArrayOf(), "This is not video".toByteArray())) {
            val file = File(fixture.context.cacheDir, "invalid.mp4").apply { writeBytes(bytes) }
            assertThrows(IllegalArgumentException::class.java) { MediaInputInspector().inspect("invalid", file, file.name) }
        }
    }

    @Test fun cancellationIsObservedDuringPtsScanAndDoesNotDeleteInput() {
        val file = video()
        var checks = 0
        assertThrows(CancellationException::class.java) {
            MediaInputInspector { if (++checks > 5) throw CancellationException("cancel") }
                .inspect("cancelled", file, file.name)
        }
        assertEquals(6, checks)
        assertTrue(file.isFile)
        assertTrue(file.length() > 0)
    }

    private fun remux(input: File, name: String, rotation: Int = 0, startUs: Long = 0,
        variablePts: Boolean = false, colorTransfer: Int? = null, audio: File? = null): File {
        val target = File(fixture.context.cacheDir, name)
        val video = MediaExtractor()
        val sound = audio?.let { MediaExtractor().apply { setDataSource(it.path) } }
        val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            video.setDataSource(input.path)
            val videoIndex = (0 until video.trackCount).first {
                video.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")
            }
            val format = video.getTrackFormat(videoIndex)
            colorTransfer?.let { format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
            val videoTrack = muxer.addTrack(format)
            val audioIndex = sound?.let { extractor -> (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")
            } }
            val audioTrack = audioIndex?.let { muxer.addTrack(sound!!.getTrackFormat(it)) }
            muxer.setOrientationHint(rotation)
            muxer.start()
            val buffer = ByteBuffer.allocate(1_048_576)
            val info = android.media.MediaCodec.BufferInfo()
            video.selectTrack(videoIndex)
            var frame = 0
            while (video.sampleTime >= 0) {
                buffer.clear()
                val size = video.readSampleData(buffer, 0)
                val pts = startUs + if (variablePts) {
                    frame * 40_000L + if (frame % 2 == 1) 20_000L else 0L
                } else video.sampleTime
                info.set(0, size, pts, video.sampleFlags)
                muxer.writeSampleData(videoTrack, buffer, info)
                video.advance()
                frame++
            }
            if (sound != null && audioIndex != null && audioTrack != null) {
                sound.selectTrack(audioIndex)
                while (sound.sampleTime in 0L..2_999_999L) {
                    buffer.clear()
                    val size = sound.readSampleData(buffer, 0)
                    info.set(0, size, sound.sampleTime, sound.sampleFlags)
                    muxer.writeSampleData(audioTrack, buffer, info)
                    sound.advance()
                }
            }
            muxer.stop()
        } finally {
            muxer.release()
            video.release()
            sound?.release()
        }
        return target
    }
}
