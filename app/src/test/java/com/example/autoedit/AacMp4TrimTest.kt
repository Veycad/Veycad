package com.veycad.app

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AacMp4TrimTest {
    private fun box(type: String, body: ByteArray): ByteArray = bytes { writeInt(body.size + 8); writeBytes(type); write(body) }
    private fun bytes(body: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).use(body) }.toByteArray()
    private fun fixture(mediaTime: Int = 2048, movieScale: Int = 10000, video: Boolean = false, extendedMdat: Boolean = false, wideEdit: Boolean = false): ByteArray {
        val mvhd = box("mvhd", bytes { writeInt(0); writeLong(0); writeInt(movieScale); writeInt(640) })
        val tkhd = box("tkhd", bytes { writeInt(0); writeLong(0); writeInt(1); writeInt(0); writeInt(640) })
        val mdhd = box("mdhd", bytes { writeInt(0); writeLong(0); writeInt(48000); writeInt(4096) })
        val hdlr = box("hdlr", bytes { writeLong(0); writeBytes("soun") })
        fun editEntry(duration: Int, time: Int) = bytes {
            if (wideEdit) { writeLong(duration.toLong()); writeLong(time.toLong()) }
            else { writeInt(duration); writeInt(time) }
            writeInt(65536)
        }
        val editVersion = if (wideEdit) 1 shl 24 else 0
        val elst = box("elst", bytes { writeInt(editVersion); writeInt(1); write(editEntry(427, mediaTime)) })
        val videoTrack = if (!video) byteArrayOf() else box("trak",
            box("tkhd", bytes { writeInt(0); writeLong(0); writeInt(2); writeInt(0); writeInt(11200) }) +
            box("edts", box("elst", bytes { writeInt(editVersion); writeInt(2); write(editEntry(1200, -1)); write(editEntry(10000, 0)) })) +
            box("mdia", box("mdhd", bytes { writeInt(0); writeLong(0); writeInt(90000); writeInt(90000) }) +
                box("hdlr", bytes { writeLong(0); writeBytes("vide") }) + box("minf", box("stbl", box("stco", bytes { writeInt(0); writeInt(1); writeInt(if (extendedMdat) 32 else 24) })))))
        val media = if (extendedMdat) bytes { writeInt(1); writeBytes("mdat"); writeLong(20); writeInt(0x01020304) }
            else box("mdat", byteArrayOf(1,2,3,4))
        return box("ftyp", ByteArray(8)) + media + box("moov", mvhd + box("trak", tkhd + box("edts", elst) + box("mdia", mdhd + hdlr)) + videoTrack)
    }
    @Test fun both_tracks_keep_video_edit_origin_sample_tables_and_all_unrelated_bytes() {
        val file=File.createTempFile("aac-trim", ".mp4")
        try {
            for (extended in listOf(false,true)) for ((samples,ticks) in listOf(800L to 4000,1600L to 8000)) {
                val before=fixture(video=true,extendedMdat=extended); file.writeBytes(before)
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
                val chunkOffset=java.nio.ByteBuffer.wrap(after).getInt(positions("stco").single()+8)
                assertEquals(if (extended) 32 else 24,chunkOffset)
                assertArrayEquals(byteArrayOf(1,2,3,4),after.copyOfRange(chunkOffset,chunkOffset+4))
                assertEquals(before.size,after.size)
                before.indices.filter { it !in intended }.forEach { assertEquals("unrelated byte $it",before[it],after[it]) }
            }
        } finally { file.delete() }
    }
    @Test fun generated_extended_mdat_header_is_preserved_and_invalid_unsigned_sizes_never_write() {
        val file=File.createTempFile("aac-trim", ".mp4")
        try {
            val valid=fixture(extendedMdat=true)
            file.writeBytes(valid)
            AacMp4Trim.apply(file,1600,2048)
            val after=file.readBytes()
            // Independently authored ftyp is 16 bytes, followed by a 16-byte mdat header and payload.
            assertEquals(1,java.nio.ByteBuffer.wrap(after).getInt(16))
            assertEquals(20L,java.nio.ByteBuffer.wrap(after).getLong(24))
            assertArrayEquals(valid.copyOfRange(16,36),after.copyOfRange(16,36))
            val invalid=listOf(
                valid.copyOf(28), // incomplete 64-bit size
                valid.copyOf().also { java.nio.ByteBuffer.wrap(it).putLong(24,15) }, // less than header
                valid.copyOf().also { java.nio.ByteBuffer.wrap(it).putLong(24,valid.size.toLong()) }, // exceeds remaining file
                valid.copyOf().also { java.nio.ByteBuffer.wrap(it).putLong(24,Long.MAX_VALUE) }, // at+size would overflow
                valid.copyOf().also { java.nio.ByteBuffer.wrap(it).putLong(24,Long.MIN_VALUE) }, // unsigned 2^63
                valid.copyOf().also { java.nio.ByteBuffer.wrap(it).putLong(24,-1L) }, // unsigned maximum
                valid.copyOf().also { "free".toByteArray(Charsets.US_ASCII).copyInto(it,20) }) // only mdat supported
            for (input in invalid) {
                file.writeBytes(input)
                assertThrows(IllegalStateException::class.java) { AacMp4Trim.apply(file,1600,2048) }
                assertArrayEquals(input,file.readBytes())
            }
        } finally { file.delete() }
    }
    @Test fun unit_overflow_and_unsupported_versions_fail_without_writes() {
        val file=File.createTempFile("aac-trim", ".mp4")
        try {
            val badVersion=fixture().also { val pos=String(it,Charsets.ISO_8859_1).indexOf("elst")+4; it[pos]=2 }
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
            assertArrayEquals(before.copyOfRange(16,28), after.copyOfRange(16,28))
        } finally { file.delete() }
    }
    @Test fun wide_edit_entries_preserve_priming_video_origin_and_every_unrelated_byte() {
        val file = File.createTempFile("aac-trim-wide", ".mp4")
        try {
            val before = fixture(video = true, extendedMdat = true, wideEdit = true)
            file.writeBytes(before)
            AacMp4Trim.apply(file, 1600, 2048)
            val after = file.readBytes()
            val buffer = java.nio.ByteBuffer.wrap(after)
            fun positions(type: String) = Regex(type).findAll(String(before, Charsets.ISO_8859_1)).map { it.range.first + 4 }.toList()
            val changed = HashSet<Int>()
            fun word(at: Int, expected: Int) { assertEquals(expected, buffer.getInt(at)); repeat(4) { changed += at + it } }
            fun wide(at: Int, expected: Long) { assertEquals(expected, buffer.getLong(at)); repeat(8) { changed += at + it } }
            word(positions("mvhd").single() + 12, 48000)
            word(positions("mvhd").single() + 16, 53760)
            word(positions("tkhd")[0] + 20, 1600)
            word(positions("tkhd")[1] + 20, 53760)
            val audio = positions("elst")[0]
            val video = positions("elst")[1]
            wide(audio + 8, 1600)
            wide(video + 8, 5760)
            wide(video + 28, 48000)
            assertEquals(2048, buffer.getLong(audio + 16))
            assertEquals(-1, buffer.getLong(video + 16))
            assertEquals(0, buffer.getLong(video + 36))
            assertEquals(before.size, after.size)
            before.indices.filter { it !in changed }.forEach { assertEquals("unrelated byte $it", before[it], after[it]) }
        } finally { file.delete() }
    }

    @Test fun invalid_wide_entries_and_overflow_never_mutate_the_file() {
        val file = File.createTempFile("aac-trim-wide", ".mp4")
        try {
            val valid = fixture(wideEdit = true)
            val entry = String(valid, Charsets.ISO_8859_1).indexOf("elst") + 4 + 8
            val invalid = listOf(
                valid.copyOf().also { java.nio.ByteBuffer.wrap(it).putLong(entry, -1) },
                valid.copyOf().also { java.nio.ByteBuffer.wrap(it).putLong(entry + 8, 1024) },
                valid.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(entry + 16, 65537) },
                valid.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(entry - 4, 3) },
                valid.copyOf(entry + 19))
            for (input in invalid) {
                file.writeBytes(input)
                assertThrows(IllegalStateException::class.java) { AacMp4Trim.apply(file, 1600, 2048) }
                assertArrayEquals(input, file.readBytes())
            }
            val overflow = valid.copyOf().also { java.nio.ByteBuffer.wrap(it).putLong(entry, Long.MAX_VALUE) }
            file.writeBytes(overflow)
            assertThrows(ArithmeticException::class.java) { AacMp4Trim.apply(file, 1600, 2048) }
            assertArrayEquals(overflow, file.readBytes())
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

    @Test fun legacy_gapless_tag_matches_validated_trim_without_moving_media_or_changing_other_bytes() {
        val file = File.createTempFile("aac-trim-tag", ".mp4")
        try {
            for (wide in listOf(false, true)) for (video in listOf(false, true)) for (samples in listOf(800L, 1600L)) {
                // Our muxer adds video first and audio last, as Android 8's metadata reader requires.
                var input = fixture(video = video, extendedMdat = true, wideEdit = wide)
                if (video) {
                    val text = String(input, Charsets.ISO_8859_1)
                    val first = text.indexOf("trak") - 4
                    val second = text.lastIndexOf("trak") - 4
                    input = input.copyOfRange(0, first) + input.copyOfRange(second, input.size) + input.copyOfRange(first, second)
                }
                file.writeBytes(input)
                AacMp4Trim.apply(file, samples, 2048)
                val edited = file.readBytes()
                file.writeBytes(input)
                AacMp4Trim.apply(file, samples, 2048, legacyGaplessTag = true)
                val result = file.readBytes()
                val tag = box("udta", box("meta", ByteArray(4) +
                    box("hdlr", bytes { writeLong(0); writeBytes("mdir"); write(ByteArray(13)) }) +
                    box("ilst", box("----",
                        box("mean", bytes { writeInt(0); writeBytes("com.apple.iTunes") }) +
                        box("name", bytes { writeInt(0); writeBytes("iTunSMPB") }) +
                        box("data", bytes { writeInt(1); writeInt(0); writeBytes(
                            if (samples == 800L) " 00000000 00000800 000004e0 0000000000000320"
                            else " 00000000 00000800 000001c0 0000000000000640") })))))
                val moov = String(input, Charsets.ISO_8859_1).indexOf("moov") - 4
                java.nio.ByteBuffer.wrap(edited).putInt(moov, edited.size - moov + tag.size)
                assertArrayEquals(edited + tag, result)
                // The original media payload and chunk offsets are included in the byte comparison.
            }
        } finally { file.delete() }
    }

    @Test fun unsupported_legacy_tag_layout_and_invalid_trim_never_mutate() {
        val file = File.createTempFile("aac-trim-tag", ".mp4")
        try {
            val duplicate = fixture().let { original ->
                val tagged = original + box("udta", byteArrayOf())
                val moov = String(original, Charsets.ISO_8859_1).indexOf("moov") - 4
                java.nio.ByteBuffer.wrap(tagged).putInt(moov, tagged.size - moov)
                tagged
            }
            for (input in listOf(fixture(video = true), fixture() + box("free", byteArrayOf()), duplicate, fixture(1024), fixture().copyOf(31))) {
                file.writeBytes(input)
                assertThrows(IllegalStateException::class.java) { AacMp4Trim.apply(file, 1600, 2048, legacyGaplessTag = true) }
                assertArrayEquals(input, file.readBytes())
            }
        } finally { file.delete() }
    }
}
