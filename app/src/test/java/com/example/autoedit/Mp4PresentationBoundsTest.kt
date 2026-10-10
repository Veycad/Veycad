package com.veycad.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CancellationException

class Mp4PresentationBoundsTest {
    private fun bytes(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(write)
    }.toByteArray()
    private fun box(type: String, payload: ByteArray) = bytes {
        writeInt(payload.size + 8); writeBytes(type); write(payload)
    }
    private fun full(type: String, payload: ByteArray, version: Int = 0) = box(type, bytes {
        writeInt(version shl 24); write(payload)
    })
    private fun movie(deltas: List<Long>, offsets: List<Long>? = null,
        emptyUs: Long = 0, editDurationUs: Long? = null, mediaStartUs: Long = 0,
        rate: Int = 0x10000, trackId: Int = 7, movieScale: Int = 1_000_000,
        mediaScale: Int = 1_000_000, editVersion: Int = 0): ByteArray {
        val tkhd = full("tkhd", bytes { writeLong(0); writeInt(trackId) })
        val mdhd = full("mdhd", bytes { writeLong(0); writeInt(mediaScale); writeInt(deltas.sum().toInt()) })
        val stts = full("stts", bytes {
            writeInt(deltas.size); deltas.forEach { writeInt(1); writeInt(it.toInt()) }
        })
        val ctts = offsets?.let { full("ctts", bytes {
            writeInt(it.size); it.forEach { offset -> writeInt(1); writeInt(offset.toInt()) }
        }, if (it.any { value -> value < 0 }) 1 else 0) } ?: byteArrayOf()
        val edits = editDurationUs?.let { active -> box("edts", full("elst", bytes {
            writeInt(if (emptyUs > 0) 2 else 1)
            fun entry(duration: Long, media: Long, entryRate: Int) {
                if (editVersion == 1) { writeLong(duration); writeLong(media) }
                else { writeInt(duration.toInt()); writeInt(media.toInt()) }
                writeInt(entryRate)
            }
            if (emptyUs > 0) entry(emptyUs, -1, 0x10000)
            entry(active, mediaStartUs, rate)
        }, editVersion)) } ?: byteArrayOf()
        val track = box("trak", tkhd + edits + box("mdia", mdhd +
            full("hdlr", bytes { writeInt(0); writeBytes("vide") }) + box("minf", box("stbl", stts + ctts))))
        return box("ftyp", "isom0000".toByteArray()) + box("moov",
            full("mvhd", bytes { writeLong(0); writeInt(movieScale) }) + track)
    }
    private fun read(data: ByteArray, first: Long, last: Long, count: Long, id: Int? = 7,
        check: () -> Unit = {}): VideoPresentationBounds? {
        val file = File.createTempFile("gallery-timing", ".mp4")
        try {
            file.writeBytes(data)
            return Mp4PresentationBounds.read(file, id, first, last, count, check)
        } finally { assertTrue("Timing reader leaked its file handle", file.delete()) }
    }

    @Test fun initialEmptyEditPreservesAuthoredEosWithoutAddingItTwice() {
        val pts = LongArray(23) { i -> 120_000L + i * 42_000L + (i / 3) * 31_000L }
        val deltas = pts.asList().zipWithNext().map { it.second - it.first } + 33_300L
        val bounds = read(movie(deltas, emptyUs = 120_000, editDurationUs = deltas.sum()),
            pts.first(), pts.last(), 23)!!
        assertEquals(1_294_300L, bounds.endPtsUs)
        assertTrue(bounds.endPtsUs <= 1_294_334L) // independently authored native EOS
        assertEquals(1_174_300L, bounds.endPtsUs - bounds.firstPtsUs)
    }
    @Test fun finalHoldsAndNonzeroTrueContentDurationsRemainIntact() {
        assertEquals(600_000L, read(movie(List(48) { 10_000L } + 120_000L), 0, 480_000, 49)!!.endPtsUs)
        assertEquals(505_000L, read(movie(List(50) { 10_000L }, emptyUs = 5_000,
            editDurationUs = 500_000), 5_000, 495_000, 50)!!.endPtsUs)
        assertEquals(2_600_000L, read(movie(listOf(240_000, 240_000, 120_000),
            offsets = List(3) { 2_000_000 }), 2_000_000, 2_480_000, 3)!!.endPtsUs)
        assertEquals(605_000L, read(movie(listOf(600_000), offsets = listOf(5_000)),
            5_000, 5_000, 1)!!.endPtsUs)
    }
    @Test fun reorderedCompositionTimesUsePresentationExtremaAndSampleDurations() {
        // Decode order: 0,300,100,200 ms. The final displayed frame has its own 100 ms hold.
        val bounds = read(movie(List(4) { 100_000L }, listOf(0, 200_000, -100_000, -100_000)),
            0, 300_000, 4)!!
        assertEquals(400_000L, bounds.endPtsUs)
    }
    @Test fun unsupportedTimingCannotBecomeMeasuredEvidence() {
        val data = movie(listOf(500_000))
        assertNull(read(data, 0, 0, 1, id = 8))
        assertNull(read(data, 0, 0, 1, id = null))
        assertNull(read(data, 0, 0, 0))
        assertNull(read(data, 5_000, 5_000, 1))
        assertNull(read(data, 0, 0, 2))
        assertNull(read(movie(listOf(500_000), editDurationUs = 500_000, rate = 0x20000), 0, 0, 1))
        assertNull(read(data + box("moof", byteArrayOf()), 0, 0, 1))
        assertNull(read(data.copyOf(data.size - 1), 0, 0, 1))
        assertNull(read(box("moov", byteArrayOf(0, 0, 0, 4, 116, 114, 97, 107)), 0, 0, 1))
    }
    @Test fun cancellationEscapesAndMalformedLargeTablesDoNotAllocateAnArchive() {
        var checks = 0
        assertThrows(CancellationException::class.java) {
            read(movie(List(20) { 10_000 }), 0, 190_000, 20,
                check = { if (++checks == 12) throw CancellationException() })
        }
        assertEquals(12, checks)
        val cancelled = IllegalArgumentException("callback cancellation is not malformed metadata")
        checks = 0
        assertSame(cancelled, assertThrows(IllegalArgumentException::class.java) {
            read(movie(listOf(500_000)), 0, 0, 1, check = { if (++checks == 12) throw cancelled })
        })
        assertNull(read(movie(listOf(0)), 0, 0, 1))
        val huge = movie(listOf(500_000)).also { data ->
            val index = data.toString(Charsets.ISO_8859_1).indexOf("stts")
            for (i in 8..11) data[index + i] = 0x7f
        }
        assertNull(read(huge, 0, 0, 1))
    }
    @Test fun mixedTimescalesRoundEndpointInwardAndOverflowIsUnknown() {
        val data = movie(listOf(500_000), emptyUs = 5, editDurationUs = 500,
            movieScale = 1000, editVersion = 1)
        assertEquals(505_000L, read(data, 5_000, 5_000, 1)!!.endPtsUs)
        val rounded = movie(listOf(1_200_000), emptyUs = 120, editDurationUs = 1174,
            movieScale = 1000)
        assertEquals(1_294_000L, read(rounded, 120_000, 120_000, 1)!!.endPtsUs)
        assertNull(read(movie(listOf(500_000), emptyUs = Long.MAX_VALUE,
            editDurationUs = 500_000, editVersion = 1), Long.MAX_VALUE, Long.MAX_VALUE, 1))
        assertNull(read(movie(listOf(500_000), editDurationUs = 500_000, mediaScale = 0), 0, 0, 1))
    }
}
