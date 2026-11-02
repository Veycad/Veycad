package com.veycad.app

import java.io.File
import java.io.RandomAccessFile

/** Narrow in-place correction for non-fragmented files with version-0 headers and v0/v1 edits.
 * No media/sample table/chunk offset is rewritten. Validate every field before the first write.
 * Wide edits use the 48 kHz PCM clock; v0 edits preserve an exact common movie multiple. */
internal object AacMp4Trim {
    private data class Atom(val type: String, val data: Long, val end: Long)
    fun apply(file: File, sampleFrames: Long, delayFrames: Int) {
        check(sampleFrames > 0 && delayFrames > 0)
        RandomAccessFile(file, "rw").use { io ->
            fun uint(at: Long): Long { check(at >= 0 && at + 4 <= io.length()); io.seek(at); return io.readInt().toLong() and 0xffffffffL }
            fun children(start: Long, end: Long, allowExtendedMdat: Boolean = false): List<Atom> {
                val result = ArrayList<Atom>()
                var at = start
                while (at < end) {
                    check(result.size < 128 && end - at >= 8) { "Unsupported AAC atom structure" }
                    var size = uint(at)
                    io.seek(at + 4); val type = ByteArray(4).also(io::readFully).toString(Charsets.US_ASCII)
                    var header = 8L
                    if (size == 1L) {
                        // MediaMuxer emits this header even for small mdat payloads. No other
                        // extended atom is part of our owned generated-container contract.
                        check(allowExtendedMdat && type == "mdat" && end - at >= 16) { "Unsupported/truncated extended AAC atom" }
                        val high = uint(at + 8); val low = uint(at + 12)
                        check(high <= 0x7fffffffL) { "Unsigned AAC atom size exceeds file address range" }
                        size = (high shl 32) or low
                        header = 16
                    }
                    // Subtract before comparing: at+size must never overflow for malformed input.
                    check(size >= header && size <= end - at) { "Truncated AAC atom" }
                    result += Atom(type, at + header, at + size); at += size
                }
                check(at == end)
                return result
            }
            fun List<Atom>.one(type: String) = singleOrNull { it.type == type } ?: error("Expected one $type atom")
            fun versionZero(atom: Atom, bytes: Long) { check(atom.end - atom.data >= bytes && uint(atom.data) ushr 24 == 0L) { "Unsupported ${atom.type} version/size" } }
            val writes = linkedMapOf<Long, Long>()
            fun put(at: Long, value: Long) { check(value in 0..0xffffffffL) { "AAC movie-time overflow" }; check(writes.put(at, value) == null) }
            val wideWrites = linkedMapOf<Long, Long>()
            fun putWide(at: Long, value: Long) { check(value >= 0); check(wideWrites.put(at, value) == null) }
            val top = children(0, io.length(), allowExtendedMdat = true)
            check(top.all { it.type in setOf("ftyp", "free", "mdat", "moov") }) { "Fragmented/unsupported AAC file" }
            top.one("ftyp"); top.one("mdat")
            val moov = top.one("moov")
            val movie = children(moov.data, moov.end)
            check(movie.none { it.type == "mvex" })
            val mvhd = movie.one("mvhd"); versionZero(mvhd, 20)
            val oldScale = uint(mvhd.data + 12); check(oldScale > 0)
            fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
            val tracks = movie.filter { it.type == "trak" }; check(tracks.size in 1..2)
            val wideEdits = tracks.any { trak -> children(trak.data, trak.end).filter { it.type == "edts" }.any { edit ->
                uint(children(edit.data, edit.end).one("elst").data) ushr 24 == 1L
            } }
            // Older extractors interpret edit media_time using the movie clock, not mdhd.
            val scale = if (wideEdits) 48000L else Math.multiplyExact(oldScale / gcd(oldScale, 48000), 48000)
            fun rescale(value: Long) = Math.addExact(Math.multiplyExact(value, scale), oldScale / 2) / oldScale
            put(mvhd.data + 12, scale)
            var audioCount = 0
            var videoCount = 0
            var movieDuration = 0L
            val ids = HashSet<Long>()
            for (trak in tracks) {
                val track = children(trak.data, trak.end)
                val tkhd = track.one("tkhd"); versionZero(tkhd, 24)
                check(ids.add(uint(tkhd.data + 12))) { "Duplicate track identity" }
                val mdia = track.one("mdia"); val media = children(mdia.data, mdia.end)
                val hdlr = media.one("hdlr"); versionZero(hdlr, 12)
                io.seek(hdlr.data + 8); val kind = ByteArray(4).also(io::readFully).toString(Charsets.US_ASCII)
                check(kind == "soun" || kind == "vide")
                val audio = kind == "soun"
                var duration = rescale(uint(tkhd.data + 20))
                val edits = track.filter { it.type == "edts" }; check(edits.size <= 1)
                if (audio) { audioCount++; check(edits.size == 1) { "AAC priming edit missing" } } else videoCount++
                for (edts in edits) {
                    val elst = children(edts.data, edts.end).one("elst")
                    check(elst.end - elst.data >= 8) { "Truncated edit list" }
                    val wide = when (uint(elst.data) ushr 24) {
                        0L -> false
                        1L -> true
                        else -> error("Unsupported edit-list version")
                    }
                    val stride = if (wide) 20 else 12
                    val count = uint(elst.data + 4); check(count in 1..2 && elst.end - elst.data == 8 + count * stride)
                    if (audio) check(count == 1L) { "Unsupported AAC edit sequence" }
                    repeat(count.toInt()) { index ->
                        val entry = elst.data + 8 + index * stride
                        check(uint(entry + if (wide) 16 else 8) == 65536L) { "Non-unit edit rate" }
                        val mediaTime = if (wide) { io.seek(entry + 8); io.readLong() } else uint(entry + 4)
                        if (!audio) check(mediaTime == 0L || index == 0 && count == 2L &&
                            mediaTime == if (wide) -1L else 0xffffffffL) { "Unsupported video edit" }
                        val rawSegment = if (wide) { io.seek(entry); io.readLong().also { check(it >= 0) } } else uint(entry)
                        var segment = rescale(rawSegment)
                        if (audio) {
                            val mdhd = media.one("mdhd"); versionZero(mdhd, 20)
                            check(uint(mdhd.data + 12) == 48000L && mediaTime == delayFrames.toLong()) { "AAC priming/timebase mismatch" }
                            val coded = uint(mdhd.data + 16)
                            check(coded >= sampleFrames + delayFrames && coded - sampleFrames - delayFrames < 4096) { "AAC coded tail mismatch" }
                            segment = Math.multiplyExact(sampleFrames, scale / 48000)
                            duration = segment
                        }
                        if (wide) putWide(entry, segment) else put(entry, segment)
                    }
                }
                put(tkhd.data + 20, duration)
                movieDuration = maxOf(movieDuration, duration)
            }
            check(audioCount == 1 && videoCount <= 1)
            put(mvhd.data + 16, movieDuration)
            // The target is an owned staging file. A failed IO never publishes it.
            writes.forEach { (at, value) -> io.seek(at); io.writeInt(value.toInt()) }
            wideWrites.forEach { (at, value) -> io.seek(at); io.writeLong(value) }
        }
    }
}
