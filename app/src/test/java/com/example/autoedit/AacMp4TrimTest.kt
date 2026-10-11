package com.veycad.app

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AacMp4TrimTest {
    private fun box(type: String, body: ByteArray): ByteArray = bytes { writeInt(body.size + 8); writeBytes(type); write(body) }
    private fun bytes(body: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).use(body) }.toByteArray()
    private fun fixture(mediaTime: Int = 2048, movieScale: Int = 10000, video: Boolean = false): ByteArray {
        val mvhd = box("mvhd", bytes { writeInt(0); writeLong(0); writeInt(movieScale); writeInt(640) })
        val tkhd = box("tkhd", bytes { writeInt(0); writeLong(0); writeInt(1); writeInt(0); writeInt(640) })
        val mdhd = box("mdhd", bytes { writeInt(0); writeLong(0); writeInt(48000); writeInt(4096) })
        val hdlr = box("hdlr", bytes { writeLong(0); writeBytes("soun") })
        val elst = box("elst", bytes { writeInt(0); writeInt(1); writeInt(427); writeInt(mediaTime); writeInt(65536) })
        val videoTrack = if (!video) byteArrayOf() else box("trak",
            box("tkhd", bytes { writeInt(0); writeLong(0); writeInt(2); writeInt(0); writeInt(11200) }) +
            box("edts", box("elst", bytes { writeInt(0); writeInt(2); writeInt(1200); writeInt(-1); writeInt(65536); writeInt(10000); writeInt(0); writeInt(65536) })) +
            box("mdia", box("mdhd", bytes { writeInt(0); writeLong(0); writeInt(90000); writeInt(90000) }) +
                box("hdlr", bytes { writeLong(0); writeBytes("vide") }) + box("minf", box("stbl", box("stco", bytes { writeInt(0); writeInt(1); writeInt(777) })))))
        return box("ftyp", ByteArray(8)) + box("moov", mvhd + box("trak", tkhd + box("edts", elst) + box("mdia", mdhd + hdlr)) + videoTrack) + box("mdat", byteArrayOf(1,2,3,4))
    }
    @Test fun both_tracks_keep_video_edit_origin_sample_tables_and_all_unrelated_bytes() {
        val file=File.createTempFile("aac-trim", ".mp4")
        try {
            for ((samples,ticks) in listOf(800L to 4000,1600L to 8000)) {
                val before=fixture(video=true); file.writeBytes(before)
                AacMp4Trim.apply(file,samples,2048)
                val after=file.readBytes()
                val text=String(before,Charsets.ISO_8859_1)
                fun positions(type: String): List<Int> = Regex(type).findAll(text).map { it.range.first+4 }.toList()
                val intended=HashSet<Int>()
                fun field(at: Int, expected: Int) { assertEquals(expected,java.nio.ByteBuffer.wrap(after).getInt(at)); repeat(4) { intended+=at+it } }
                field(positions("mvhd").single()+12,240000)
                field(positions("mvhd").single()+16,268800)
                field(positions("tkhd")[0]+20,ticks); field(positions("tkhd")[1]+20,268800)
                field(positions("elst")[0]+8,ticks)
                field(positions("elst")[1]+8,28800); field(positions("elst")[1]+20,240000)
                assertEquals(before.size,after.size)
                before.indices.filter { it !in intended }.forEach { assertEquals("unrelated byte $it",before[it],after[it]) }
            }
        } finally { file.delete() }
    }
    @Test fun unit_overflow_and_unsupported_versions_fail_without_writes() {
        val file=File.createTempFile("aac-trim", ".mp4")
        try {
            val badVersion=fixture().also { val pos=String(it,Charsets.ISO_8859_1).indexOf("elst")+4; it[pos]=1 }
            for (input in listOf(fixture(movieScale=Int.MAX_VALUE),badVersion)) {
                file.writeBytes(input)
                assertThrows(IllegalStateException::class.java) { AacMp4Trim.apply(file,1600,2048) }
                assertArrayEquals(input,file.readBytes())
            }
        } finally { file.delete() }
    }
    @Test fun exact_partial_frame_trim_preserves_media_and_promotes_movie_units_without_rounding() {
        val file = File.createTempFile("aac-trim", ".mp4")
        try {
            file.writeBytes(fixture())
            val before = file.readBytes()
            AacMp4Trim.apply(file, 1600, 2048)
            val after = file.readBytes()
            assertEquals(before.size, after.size)
            fun field(type: String, offset: Int) = java.nio.ByteBuffer.wrap(after).getInt(String(after, Charsets.ISO_8859_1).indexOf(type) + 4 + offset)
            assertEquals(240000, field("mvhd", 12))
            assertEquals(8000, field("mvhd", 16))
            assertEquals(8000, field("tkhd", 20))
            assertEquals(8000, field("elst", 8))
            assertEquals(2048, field("elst", 12))
            assertEquals(48000, field("mdhd", 12))
            assertEquals(4096, field("mdhd", 16))
            assertArrayEquals(before.takeLast(12).toByteArray(), after.takeLast(12).toByteArray())
        } finally { file.delete() }
    }
    @Test fun inconsistent_trim_or_malformed_atoms_are_rejected_before_mutation() {
        val file = File.createTempFile("aac-trim", ".mp4")
        try {
            for (content in listOf(fixture(1024), fixture().copyOf(31))) {
                file.writeBytes(content)
                assertThrows(IllegalStateException::class.java) { AacMp4Trim.apply(file, 1600, 2048) }
                assertArrayEquals(content, file.readBytes())
            }
        } finally { file.delete() }
    }
}
