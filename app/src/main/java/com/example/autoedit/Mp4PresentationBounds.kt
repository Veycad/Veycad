package com.veycad.app

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.math.BigInteger

/** Non-fragmented ISO-BMFF only. Unknown timing is not a duration estimate.
 * stts supplies each sample's hold. ctts and unit-rate edits establish the endpoint
 * in the extractor's observed timestamp coordinate system. No media payload is read.
 */
internal object Mp4PresentationBounds {
    fun read(file: File, trackId: Int?, firstPtsUs: Long, lastPtsUs: Long,
        sampleCount: Long, checkCancelled: () -> Unit): VideoPresentationBounds? = parse(file, trackId, checkCancelled) { id ->
        val track = track(id, sampleCount)
        listOfNotNull(track.edited, track.raw).firstOrNull {
            it.bounds.firstPtsUs == firstPtsUs && it.lastPtsUs == lastPtsUs
        }?.bounds
    }

    /** Only translate when the stored range and the extractor's first decode sample identify both clocks. */
    fun decoderClock(file: File, trackId: Int?, savedFirstUs: Long, savedEndUs: Long,
        firstDecodeUs: Long, checkCancelled: () -> Unit): DecoderClock? = parse(file, trackId, checkCancelled) { id ->
        val track = track(id, null, requireShift = true)
        val raw = track.raw ?: return@parse null
        val edited = track.edited ?: return@parse null
        val clocks = listOf(edited, raw)
        val saved = clocks.singleOrNull {
            it.bounds.firstPtsUs == savedFirstUs && it.bounds.endPtsUs == savedEndUs
        } ?: return@parse null
        val device = clocks.singleOrNull { it.firstDecodeUs == firstDecodeUs } ?: return@parse null
        if (saved === device || track.scale > 1_000_000L) return@parse null
        DecoderClock(track.scale, saved.shift - device.shift)
    }

    internal class DecoderClock(scale: Long, ticksToProject: Long) {
        private val units = BigInteger.valueOf(scale)
        private val micros = BigInteger.valueOf(1_000_000)
        private val shift = BigInteger.valueOf(ticksToProject)

        private fun firstTickAtOrAfter(timeUs: Long): BigInteger = BigInteger.valueOf(timeUs).multiply(units)
            .add(micros.subtract(BigInteger.ONE)).divide(micros)

        private fun toUs(tick: BigInteger): Long {
            require(tick.signum() >= 0)
            val value = tick.multiply(micros).divide(units)
            require(value.signum() >= 0 && value.bitLength() <= 63)
            return value.toLong()
        }

        fun toProjectUs(sampleUs: Long): Long {
            require(sampleUs >= 0)
            val tick = firstTickAtOrAfter(sampleUs)
            require(toUs(tick) == sampleUs)
            return toUs(tick.add(shift))
        }

        fun toDecoderSeekUs(targetUs: Long): Long {
            require(targetUs >= 0)
            return toUs(firstTickAtOrAfter(targetUs).subtract(shift).max(BigInteger.ZERO))
        }
    }

    data class AacEdit(val delayFrames: Int, val presentationFrames: Long)

    fun aacEdit(file: File, trackId: Int?, sampleRate: Int, checkCancelled: () -> Unit): AacEdit? =
        parse(file, trackId, checkCancelled) { id -> aacEdit(id, sampleRate) }

    private fun <T> parse(file: File, trackId: Int?, checkCancelled: () -> Unit,
        read: Parser.(Int) -> T?): T? {
        checkCancelled()
        if (trackId == null || trackId <= 0) return null
        var cancellation: Throwable? = null
        val check = {
            try { checkCancelled() } catch (failure: Throwable) { cancellation = failure; throw failure }
        }
        try {
            return RandomAccessFile(file, "r").use { input ->
                Parser(input, check).read(trackId)
            }
        } catch (failure: Exception) {
            cancellation?.let { throw it }
            if (failure is IOException || failure is IllegalArgumentException || failure is ArithmeticException) return null
            throw failure
        }
    }

    private data class Box(val type: String, val start: Long, val end: Long)
    private data class Edit(val emptyTicks: Long, val mediaStartTicks: Long, val activeDurationTicks: Long?)
    private data class Clock(val shift: Long, val firstDecodeUs: Long, val lastPtsUs: Long, val bounds: VideoPresentationBounds)
    private data class Track(val scale: Long, val edited: Clock?, val raw: Clock?)
    private class Parser(val input: RandomAccessFile, val check: () -> Unit) {
        private var headers = 0
        private fun children(start: Long, end: Long): List<Box> {
            val boxes = mutableListOf<Box>()
            var cursor = start
            while (cursor < end) {
                check()
                require(++headers <= 4096 && end - cursor >= 8)
                input.seek(cursor)
                val shortSize = input.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4).also { input.readFully(it) }.toString(Charsets.ISO_8859_1)
                val headerSize = if (shortSize == 1L) 16L else 8L
                require(end - cursor >= headerSize)
                val size = when (shortSize) { 0L -> end - cursor; 1L -> input.readLong(); else -> shortSize }
                require(size >= headerSize && size <= end - cursor)
                boxes += Box(type, cursor + headerSize, cursor + size)
                cursor += size
            }
            return boxes
        }
        private fun List<Box>.one(type: String): Box = requireNotNull(optional(type))
        private fun List<Box>.optional(type: String): Box? {
            val selected = filter { it.type == type }
            require(selected.size <= 1)
            return selected.singleOrNull()
        }
        private fun int(box: Box, offset: Long): Int {
            require(offset >= 0 && box.end - box.start >= 4 && offset <= box.end - box.start - 4)
            input.seek(box.start + offset)
            return input.readInt()
        }
        private fun uint(box: Box, offset: Long): Long = int(box, offset).toLong() and 0xffffffffL
        private fun long(box: Box, offset: Long): Long {
            require(offset >= 0 && box.end - box.start >= 8 && offset <= box.end - box.start - 8)
            input.seek(box.start + offset)
            return input.readLong()
        }
        private fun version(box: Box): Int {
            val flags = int(box, 0)
            require(flags and 0xffffff == 0 || box.type == "tkhd")
            return (flags ushr 24).also { require(it in 0..1) }
        }
        private fun timescale(box: Box): Long = uint(box, if (version(box) == 1) 20 else 12).also { require(it > 0) }
        private fun trackId(box: Box): Long = uint(box, if (version(box) == 1) 20 else 12)
        private fun edit(box: Box?): Edit {
            if (box == null) return Edit(0, 0, null)
            val elst = children(box.start, box.end).one("elst")
            val version = version(elst)
            val count = uint(elst, 4)
            require(count in 1..2)
            val step = if (version == 1) 20L else 12L
            require(elst.end - elst.start == 8 + count * step)
            fun duration(i: Long) = if (version == 1) long(elst, 8 + i * step) else uint(elst, 8 + i * step)
            fun media(i: Long) = if (version == 1) long(elst, 16 + i * step) else int(elst, 12 + i * step).toLong()
            for (i in 0 until count) require(int(elst, 8 + i * step + step - 4) == 0x10000 && duration(i) > 0)
            val active = count - 1
            if (count == 2L) require(media(0) == -1L)
            require(media(active) >= 0)
            return Edit(if (count == 2L) duration(0) else 0, media(active), duration(active))
        }
        private fun scale(value: Long, numerator: Long, denominator: Long, round: Boolean = false): Long {
            require(value >= 0 && numerator > 0 && denominator > 0)
            // Bounded scalar arithmetic also accepts large valid timestamps without a
            // multiplying Long wrap. Endpoint conversions always round inward.
            val product = BigInteger.valueOf(value).multiply(BigInteger.valueOf(numerator))
            val adjusted = if (round) product.add(BigInteger.valueOf(denominator / 2)) else product
            val result = adjusted.divide(BigInteger.valueOf(denominator))
            require(result.signum() >= 0 && result.bitLength() <= 63)
            return result.toLong()
        }
        private inner class Runs(val box: Box?, val countWithoutBox: Long, val signed: Boolean = false) {
            private val entries = box?.let { uint(it, 4).also { n ->
                require(n in 1..1_000_000 && it.end - it.start == 8 + n * 8)
            } } ?: 1L
            private var index = 0L
            var remaining = 0L
            var value = 0L
            init { next() }
            fun next() {
                check()
                require(remaining == 0L && index < entries)
                remaining = box?.let { uint(it, 8 + index * 8) } ?: countWithoutBox
                value = box?.let { if (signed) int(it, 12 + index * 8).toLong() else uint(it, 12 + index * 8) } ?: 0L
                require(remaining > 0)
                index++
            }
            fun exhausted(): Boolean = remaining == 0L && index == entries
        }
        private fun findTrack(id: Int): Pair<Long, List<Box>> {
            val top = children(0, input.length())
            top.one("ftyp")
            require(top.none { it.type == "moof" })
            val moov = top.one("moov")
            val movie = children(moov.start, moov.end)
            require(movie.none { it.type == "mvex" })
            val movieScale = timescale(movie.one("mvhd"))
            val tracks = movie.filter { it.type == "trak" }.map { children(it.start, it.end) }
            val ids = tracks.map { trackId(it.one("tkhd")) }
            require(ids.distinct().size == ids.size)
            return movieScale to tracks[ids.indexOf(id.toLong()).also { require(it >= 0) }]
        }

        fun aacEdit(id: Int, sampleRate: Int): AacEdit {
            val (movieScale, track) = findTrack(id)
            val media = track.one("mdia")
            val boxes = children(media.start, media.end)
            require(int(boxes.one("hdlr"), 8) == 0x736f756e && timescale(boxes.one("mdhd")) == sampleRate.toLong())
            val edit = edit(track.one("edts"))
            require(edit.emptyTicks == 0L && edit.mediaStartTicks in 1L..Int.MAX_VALUE.toLong())
            val numerator = Math.multiplyExact(requireNotNull(edit.activeDurationTicks), sampleRate.toLong())
            require(numerator % movieScale == 0L)
            return AacEdit(edit.mediaStartTicks.toInt(), numerator / movieScale)
        }

        fun track(id: Int, expectedCount: Long?, requireShift: Boolean = false): Track {
            val (movieScale, track) = findTrack(id)
            val mdiaBox = track.one("mdia")
            val mdia = children(mdiaBox.start, mdiaBox.end)
            require(int(mdia.one("hdlr"), 8) == 0x76696465) // vide, not an audio track with similar times
            val mediaScale = timescale(mdia.one("mdhd"))
            val edit = edit(track.optional("edts"))
            val empty = scale(edit.emptyTicks, mediaScale, movieScale, round = true)
            val rawShift = edit.mediaStartTicks - empty
            require(!requireShift || rawShift != 0L)
            val minf = mdia.one("minf")
            val stbl = children(minf.start, minf.end).one("stbl")
            val table = children(stbl.start, stbl.end)
            val stts = table.one("stts")
            require(version(stts) == 0)
            val actualCount = expectedCount ?: uint(table.optional("stsz") ?: table.one("stz2"), 8)
            require(actualCount > 0)
            val ctts = table.optional("ctts")
            val cttsVersion = ctts?.let { version(it) }
            val times = Runs(stts, actualCount)
            val offsets = Runs(ctts, actualCount, cttsVersion == 1)
            var decode = 0L
            var first = Long.MAX_VALUE
            var firstDecode = -1L
            var last = -1L
            var end = -1L
            var count = 0L
            while (true) {
                check()
                require(times.value > 0)
                val n = minOf(times.remaining, offsets.remaining)
                count = Math.addExact(count, n)
                require(count <= actualCount)
                val pts = Math.subtractExact(Math.addExact(Math.addExact(decode, offsets.value), empty), edit.mediaStartTicks)
                require(pts >= 0) // negative preroll/other edit schemes remain unsupported
                if (firstDecode < 0) firstDecode = pts
                val runLast = Math.addExact(pts, Math.multiplyExact(n - 1, times.value))
                val runEnd = Math.addExact(runLast, times.value)
                first = minOf(first, pts); last = maxOf(last, runLast); end = maxOf(end, runEnd)
                decode = Math.addExact(decode, Math.multiplyExact(n, times.value))
                times.remaining -= n; offsets.remaining -= n
                if (times.exhausted() || offsets.exhausted()) { require(times.exhausted() && offsets.exhausted()); break }
                if (times.remaining == 0L) times.next()
                if (offsets.remaining == 0L) offsets.next()
            }
            require(count == actualCount)
            edit.activeDurationTicks?.let {
                end = minOf(end, Math.addExact(empty, scale(it, mediaScale, movieScale)))
            }
            fun clock(shift: Long): Clock? = try {
                fun micros(ticks: Long) = scale(Math.addExact(ticks, shift), 1_000_000, mediaScale)
                val lastUs = micros(last)
                val endUs = micros(end)
                if (endUs > lastUs) Clock(shift, micros(firstDecode), lastUs, VideoPresentationBounds(micros(first), endUs))
                else null
            } catch (_: IllegalArgumentException) { null } catch (_: ArithmeticException) { null }
            // Older extractors can expose composition timestamps before applying elst.
            return Track(mediaScale, clock(0L), if (rawShift != 0L) clock(rawShift) else null)
        }
    }
}
