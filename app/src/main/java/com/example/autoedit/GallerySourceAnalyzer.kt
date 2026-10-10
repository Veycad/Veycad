package com.veycad.app

import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat

data class GalleryFrameBounds(val firstPtsUs: Long, val lastPtsUs: Long)

/** Read callbacks own no bitmap; each observation carries the codec's actual presentation time. */
interface GalleryFrameReader {
    fun bounds(source: MediaSource, checkCancelled: () -> Unit): GalleryFrameBounds
    fun read(source: MediaSource, targetsUs: LongArray, checkCancelled: () -> Unit,
        onFrame: (ptsUs: Long, luma: FloatArray, width: Int, height: Int) -> Unit)
}

/** At most one decoder and one previous luma sample per pass, plus bounded scalar observations. */
class GallerySourceAnalyzer(
    val coarseIntervalUs: Long = 1_000_000L,
    val refineIntervalUs: Long = 250_000L,
    private val frameReader: GalleryFrameReader = GalleryBitmapFrameReader,
    private val cache: GalleryAnalysisCache = GalleryAnalysisCache()
) {
    private data class Observation(val features: GalleryFeatures, val hash: Long)
    private val profile = GalleryAnalysisProfile(coarseIntervalUs = coarseIntervalUs, refineIntervalUs = refineIntervalUs)

    fun analyze(source: MediaSource, checkCancelled: () -> Unit): List<GalleryMoment> {
        checkCancelled()
        require(source.isBaselineInput) { "Gallery analysis currently requires H.264/SDR" }
        require(source.durationUs in 500_000L..GalleryImportPolicy.MAX_DURATION_US)
        cache.load(source, profile)?.let { checkCancelled(); return it }
        val bounds = frameReader.bounds(source, checkCancelled)
        require(bounds.firstPtsUs == source.firstVideoPtsUs && bounds.lastPtsUs >= bounds.firstPtsUs &&
            bounds.lastPtsUs < source.videoEndPtsUs) { "Measured gallery PTS do not fit inspected source bounds" }
        val coarse = sortedSetOf(bounds.firstPtsUs, bounds.lastPtsUs)
        var target = bounds.firstPtsUs
        while (bounds.lastPtsUs - target >= coarseIntervalUs) {
            checkCancelled()
            target += coarseIntervalUs
            coarse += target
            require(coarse.size <= MAX_COARSE_SAMPLES) { "Gallery overview interval exceeds sample budget" }
        }
        val observations = sortedMapOf<Long, Observation>()
        fun sample(targets: LongArray) {
            var previous: FloatArray? = null
            var previousPts = -1L
            frameReader.read(source, targets, checkCancelled) { pts, luma, width, height ->
                checkCancelled()
                require(pts in bounds.firstPtsUs..bounds.lastPtsUs && pts >= previousPts) { "Invalid decoded gallery PTS" }
                if (pts != previousPts) {
                    val measured = GalleryFrameMetrics.measure(pts, luma, width, height, previous)
                    // A refined predecessor can make a gradual transition look smaller. It
                    // must not discard stronger scene evidence already measured by overview.
                    val features = measured.copy(sceneChange = maxOf(measured.sceneChange,
                        observations[pts]?.features?.sceneChange ?: 0f))
                    observations[pts] = Observation(features, GalleryFrameMetrics.keyHash(luma, width, height))
                    previous = luma.copyOf()
                    previousPts = pts
                }
            }
        }
        sample(coarse.toLongArray())
        // Keep candidates spread over the source; a long static landscape cannot monopolize refinement.
        val candidates = observations.values.sortedByDescending { score(it.features) }
        val chosen = mutableListOf<Long>()
        for (candidate in candidates) {
            checkCancelled()
            if (chosen.all { kotlin.math.abs(it - candidate.features.timeUs) >= 2_000_000L }) chosen += candidate.features.timeUs
            if (chosen.size == MAX_REFINE_CANDIDATES) break
        }
        val refine = sortedSetOf<Long>()
        for (center in chosen) {
            var time = center - minOf(1_000_000L, center - bounds.firstPtsUs)
            val end = center + minOf(1_000_000L, bounds.lastPtsUs - center)
            while (time <= end) {
                checkCancelled()
                refine += time
                require(refine.size <= MAX_REFINE_SAMPLES) { "Gallery refinement interval exceeds sample budget" }
                if (end - time < refineIntervalUs) break
                time += refineIntervalUs
            }
        }
        // Include overview targets so a refinement pass cannot erase a cut by making it
        // its first observation, and measure changes against the preceding observed frame.
        if (refine.isNotEmpty()) sample((refine + coarse).sorted().toLongArray())
        checkCancelled()
        val cuts = observations.values.filter { it.features.sceneChange >= .6f }.map { it.features.timeUs }
        val boundaries = (listOf(bounds.firstPtsUs) + cuts + source.videoEndPtsUs).distinct().sorted()
        val moments = mutableListOf<GalleryMoment>()
        for ((start, end) in boundaries.zipWithNext()) {
            checkCancelled()
            if (end - start < 500_000L) continue
            val sceneFrames = observations.values.filter { it.features.timeUs in start until end }
                .sortedByDescending { score(it.features) }
            for (frame in sceneFrames) {
                checkCancelled()
                if (frame.features.quality < .15f) continue
                val length = minOf(4_000_000L, end - start)
                val windowStart = (frame.features.timeUs - length / 2).coerceIn(start, end - length)
                val windowEnd = windowStart + length
                if (moments.any { it.startUs == windowStart && it.endUs == windowEnd }) continue
                moments += GalleryMoment(source.id, windowStart, windowEnd, frame.features, frame.hash)
                if (moments.size == MAX_MOMENTS) break
            }
            if (moments.size == MAX_MOMENTS) break
        }
        val result = moments.sortedBy { it.startUs }
        checkCancelled()
        cache.store(source, result, profile)
        return result
    }

    private fun score(features: GalleryFeatures): Float =
        features.quality + .25f * features.localMotion + .1f * features.composition - .25f * features.cameraInstability

    private companion object {
        const val MAX_COARSE_SAMPLES = 2_048
        const val MAX_REFINE_CANDIDATES = 32
        const val MAX_REFINE_SAMPLES = 512
        const val MAX_MOMENTS = 128
    }
}

internal object GalleryBitmapFrameReader : GalleryFrameReader {
    override fun bounds(source: MediaSource, checkCancelled: () -> Unit): GalleryFrameBounds {
        val extractor = MediaExtractor()
        try {
            checkCancelled()
            extractor.setDataSource(source.file.path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it)
                .getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            extractor.selectTrack(track)
            var first = Long.MAX_VALUE
            var last = -1L
            while (extractor.sampleTrackIndex >= 0) {
                checkCancelled()
                val pts = extractor.sampleTime
                require(pts >= 0)
                first = minOf(first, pts)
                last = maxOf(last, pts)
                if (!extractor.advance()) break
            }
            require(last >= 0) { "Gallery video has no frames" }
            return GalleryFrameBounds(first, last)
        } finally { extractor.release() }
    }

    override fun read(source: MediaSource, targetsUs: LongArray, checkCancelled: () -> Unit,
        onFrame: (Long, FloatArray, Int, Int) -> Unit) {
        val rotated = source.rotationDegrees % 180 != 0
        val displayWidth = if (rotated) source.height else source.width
        val displayHeight = if (rotated) source.width else source.height
        val scale = minOf(1f, 64f / maxOf(displayWidth, displayHeight))
        val width = maxOf(1, (displayWidth * scale).toInt())
        val height = maxOf(1, (displayHeight * scale).toInt())
        SequentialBitmapDecoder.decodeActual(source.file, targetsUs, width, height,
            checkCancelled = checkCancelled) { _, pts, bitmap ->
            checkCancelled()
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val luma = FloatArray(pixels.size) { i ->
                (.2126f * Color.red(pixels[i]) + .7152f * Color.green(pixels[i]) + .0722f * Color.blue(pixels[i])) / 255f
            }
            onFrame(pts, luma, width, height)
        }
    }
}
