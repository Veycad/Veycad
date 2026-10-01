package com.example.autoedit

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ceil

/** Pixel correspondence for motion evidence, separate from the editorial blur-flow field. */
internal object LocalMotionCorrespondence {
    /** Native pixels per reference pixel on each axis. Unit sampling is for raw-pixel tests. */
    data class Sampling(val x: Float = 1f, val y: Float = 1f) {
        init { require(x.isFinite() && y.isFinite() && x > 0f && y > 0f) }
    }
    data class Cell(val centerX: Int, val centerY: Int, val x: Float, val y: Float, val confidence: Float,
                    val textured: Boolean, val fit: Float, val uniqueness: Float, val roundtrip: Boolean,
                    val patchRadiusX: Int, val patchRadiusY: Int)
    private data class Match(val dx: Float, val dy: Float, val fit: Float, val uniqueness: Float,
                             val textured: Boolean) {
        val confidence: Float get() = fit * uniqueness
    }
    internal class QuarterPlane(val width: Int, private val values: FloatArray) {
        operator fun get(x: Int, y: Int): Float = values[y * width + x]
        operator fun get(index: Int): Float = values[index]
        val sampleCount: Int get() = values.size
    }

    /** Caller-owned structural counters; never retained by a frame pair or global cache. */
    class NormalizationAudit {
        var previousHits: Long = 0L
            private set
        var previousMisses: Long = 0L
            private set
        var currentHits: Long = 0L
            private set
        var currentMisses: Long = 0L
            private set
        var arrayPayloadBytes: Long = 0L
            private set

        internal fun record(previous: Boolean, hit: Boolean) {
            if (previous) {
                if (hit) previousHits++ else previousMisses++
            } else {
                if (hit) currentHits++ else currentMisses++
            }
        }

        internal fun allocated(samples: Int) { arrayPayloadBytes += samples.toLong() * 9L }
    }

    private class NormalizationTables(samples: Int) {
        val means = FloatArray(samples)
        val textures = FloatArray(samples)
        val present = BooleanArray(samples)
    }

    /** One source direction and one exact footprint, for ONE estimate invocation only. */
    private class CandidateNormalization(private val source: QuarterPlane,
                                         private val offsets: IntArray,
                                         private val previousDirection: Boolean,
                                         private val audit: NormalizationAudit?) {
        private var tables: NormalizationTables? = null

        fun at(base: Int): NormalizationTables {
            val values = tables ?: NormalizationTables(source.sampleCount).also {
                tables = it
                audit?.allocated(source.sampleCount)
            }
            if (values.present[base]) {
                audit?.record(previousDirection, true)
                return values
            }
            // Candidate statistics use Float accumulation, unlike the query's Double sums.
            // Keep the original row-major additions and division, without summed-area tables.
            var mean = 0f
            for (i in offsets.indices) mean += source[base + offsets[i]]
            mean /= offsets.size
            var texture = 0f
            for (i in offsets.indices) {
                val centered = source[base + offsets[i]] - mean
                texture += abs(centered)
            }
            texture /= offsets.size
            values.means[base] = mean
            values.textures[base] = texture
            values.present[base] = true
            audit?.record(previousDirection, false)
            return values
        }
    }

    /** Immutable interpolated snapshots of one frame pair, never a global/source cache. */
    class PreparedPair internal constructor(previous: LumaMotionEstimator.Plane,
                                            current: LumaMotionEstimator.Plane) {
        init { require(previous.width == current.width && previous.height == current.height) }
        val width: Int = current.width
        val height: Int = current.height
        internal val previousQuarter = quarterPlane(previous)
        internal val currentQuarter = quarterPlane(current)
        val interpolatedSamples: Int get() = previousQuarter.sampleCount + currentQuarter.sampleCount
    }

    fun prepare(previous: LumaMotionEstimator.Plane?, current: LumaMotionEstimator.Plane): PreparedPair? =
        if (previous == null || previous.width != current.width || previous.height != current.height) null
        else PreparedPair(previous, current)

    fun estimate(previous: LumaMotionEstimator.Plane?, current: LumaMotionEstimator.Plane,
                 gridWidth: Int = 12, gridHeight: Int = 18, searchRadius: Int = 3,
                 patchRadius: Int = 2, sampling: Sampling = Sampling(), patchFraction: Float = 1f,
                 requestedCenters: List<Pair<Int, Int>>? = null,
                 patchShape: Sampling = Sampling(), normalizationAudit: NormalizationAudit? = null): List<Cell>? {
        if (previous == null || previous.width != current.width || previous.height != current.height) return null
        return estimatePrepared(current.width, current.height, gridWidth, gridHeight, searchRadius,
            patchRadius, sampling, patchFraction, requestedCenters, patchShape, normalizationAudit) {
            requireNotNull(prepare(previous, current))
        }
    }

    fun estimate(prepared: PreparedPair, gridWidth: Int = 12, gridHeight: Int = 18,
                 searchRadius: Int = 3, patchRadius: Int = 2, sampling: Sampling = Sampling(),
                 patchFraction: Float = 1f, requestedCenters: List<Pair<Int, Int>>? = null,
                 patchShape: Sampling = Sampling(), normalizationAudit: NormalizationAudit? = null): List<Cell>? =
        estimatePrepared(prepared.width, prepared.height, gridWidth, gridHeight, searchRadius,
            patchRadius, sampling, patchFraction, requestedCenters, patchShape, normalizationAudit) { prepared }

    private fun estimatePrepared(width: Int, height: Int, gridWidth: Int, gridHeight: Int,
                                 searchRadius: Int, patchRadius: Int, sampling: Sampling,
                                 patchFraction: Float, requestedCenters: List<Pair<Int, Int>>?,
                                 patchShape: Sampling, normalizationAudit: NormalizationAudit?,
                                 pair: () -> PreparedPair): List<Cell>? {
        require(gridWidth > 1 && gridHeight > 1 && searchRadius > 0 && patchRadius > 0)
        require(patchFraction.isFinite() && patchFraction > 0f)
        // Keep the patch's physical FOV footprint approximately constant as density changes.
        // A fixed native 5x5 patch would become a thin slit in reference coordinates on landscape.
        val radiusX = ceil(patchRadius * sampling.x * patchFraction * patchShape.x).toInt()
        val radiusY = ceil(patchRadius * sampling.y * patchFraction * patchShape.y).toInt()
        val padX = radiusX + ceil(2 * searchRadius * sampling.x).toInt()
        val padY = radiusY + ceil(2 * searchRadius * sampling.y).toInt()
        val stepsX = floor(searchRadius * sampling.x * 4).toInt()
        val stepsY = floor(searchRadius * sampling.y * 4).toInt()
        if (width <= 2 * padX || height <= 2 * padY || stepsX < 1 || stepsY < 1) return null
        val centers = requestedCenters?.filter { (x, y) ->
            x in padX until width - padX && y in padY until height - padY
        }?.distinct() ?: List(gridWidth * gridHeight) { i ->
            val x = (((i % gridWidth + .5f) * width / gridWidth).toInt())
                .coerceIn(padX, width - padX - 1)
            val y = (((i / gridWidth + .5f) * height / gridHeight).toInt())
                .coerceIn(padY, height - padY - 1)
            x to y
        }.distinct()
        if (centers.isEmpty()) return emptyList()
        // Reuse interpolated values across all patches rather than resampling each candidate.
        val prepared = pair()
        val previousQuarter = prepared.previousQuarter
        val currentQuarter = prepared.currentQuarter
        val patchWidth = 2 * radiusX + 1
        // Integer lookup offsets only: same row-major samples, float operation order unchanged.
        val offsets = IntArray(patchWidth * (2 * radiusY + 1)) { i ->
            (i / patchWidth - radiusY) * 4 * currentQuarter.width + (i % patchWidth - radiusX) * 4
        }
        // Absolute candidate patches recur across neighboring centers. Only their source-only
        // Float normalization is shared; query statistics, gain and every error remain separate.
        val previousNormalization = CandidateNormalization(previousQuarter, offsets, true, normalizationAudit)
        val currentNormalization = CandidateNormalization(currentQuarter, offsets, false, normalizationAudit)
        // Border clamping must not turn one physical patch into several support witnesses.
        return centers.map { (x, y) ->
            val forward = match(previousQuarter, currentQuarter, x * 4, y * 4, stepsX, stepsY, offsets,
                sampling, previousNormalization)
            val backward = match(currentQuarter, previousQuarter,
                x * 4 - (forward.dx * 4).toInt(), y * 4 - (forward.dy * 4).toInt(), stepsX, stepsY, offsets,
                sampling, currentNormalization)
            val consistent = abs(forward.dx + backward.dx) <= sampling.x &&
                abs(forward.dy + backward.dy) <= sampling.y
            Cell(x, y, forward.dx / (searchRadius * sampling.x), forward.dy / (searchRadius * sampling.y),
                if (consistent) minOf(forward.confidence, backward.confidence) else 0f,
                forward.textured && backward.textured, minOf(forward.fit, backward.fit),
                minOf(forward.uniqueness, backward.uniqueness), consistent, radiusX, radiusY)
        }
    }

    private fun match(previous: QuarterPlane, current: QuarterPlane,
                      x: Int, y: Int, stepsX: Int, stepsY: Int, offsets: IntArray, sampling: Sampling,
                      normalization: CandidateNormalization): Match {
        val queryBase = y * current.width + x
        val query = FloatArray(offsets.size) { i -> current[queryBase + offsets[i]] }
        val mean = query.average().toFloat()
        query.indices.forEach { query[it] -= mean }
        val texture = query.sumOf { abs(it).toDouble() }.toFloat() / query.size
        if (texture < .02f) return Match(0f, 0f, 0f, 0f, false)
        fun error(dx: Int, dy: Int): Float {
            val candidateBase = (y - dy) * previous.width + x - dx
            val statistics = normalization.at(candidateBase)
            val candidateMean = statistics.means[candidateBase]
            val candidateTexture = statistics.textures[candidateBase]
            // Mean removal handles offset only. A bounded positive contrast gain additionally
            // models affine lighting without pretending a flat candidate has spatial evidence.
            if (candidateTexture < .02f) return Float.POSITIVE_INFINITY
            val gain = texture / candidateTexture
            if (gain !in .5f..2f) return Float.POSITIVE_INFINITY
            var error = 0f
            for (i in query.indices) {
                val centered = previous[candidateBase + offsets[i]] - candidateMean
                error += abs(query[i] - gain * centered)
            }
            error /= query.size
            return error
        }
        val candidatesWidth = stepsX * 2 + 1
        val errors = FloatArray(candidatesWidth * (stepsY * 2 + 1))
        var best = Float.POSITIVE_INFINITY
        var bestX = 0; var bestY = 0
        // Exhaustive quarter-pixel hypotheses: no unvisited basin can silently supply fake
        // uniqueness. Each position occurs once, including every old integer hypothesis.
        for (dy in -stepsY..stepsY) for (dx in -stepsX..stepsX) {
            val value = error(dx, dy)
            errors[(dy + stepsY) * candidatesWidth + dx + stepsX] = value
            if (value < best) { best = value; bestX = dx; bestY = dy }
        }
        // Compare distinct basins at least one REFERENCE pixel apart. Increasing resolution
        // must not shrink this physical neighborhood or the roundtrip tolerance.
        var second = Float.POSITIVE_INFINITY
        for (dy in -stepsY..stepsY) for (dx in -stepsX..stepsX) {
            if (maxOf(abs(dx - bestX) / sampling.x, abs(dy - bestY) / sampling.y) >= 4)
                second = minOf(second, errors[(dy + stepsY) * candidatesWidth + dx + stepsX])
        }
        // Photometric residual and uniqueness, not just improvement over an arbitrary zero shift.
        val fit = (1f - best / .05f).coerceIn(0f, 1f)
        val unique = if (second.isFinite()) ((second - best) / .02f).coerceIn(0f, 1f) else 0f
        return Match(bestX * .25f, bestY * .25f, fit, unique, true)
    }

    private fun quarterPlane(plane: LumaMotionEstimator.Plane): QuarterPlane {
        val width = (plane.width - 1) * 4 + 1
        val height = (plane.height - 1) * 4 + 1
        return QuarterPlane(width, FloatArray(width * height) { i -> sample(plane,
            (i % width) * .25f, (i / width) * .25f) })
    }

    private fun sample(plane: LumaMotionEstimator.Plane, x: Float, y: Float): Float {
        val left = floor(x).toInt().coerceIn(0, plane.width - 1)
        val top = floor(y).toInt().coerceIn(0, plane.height - 1)
        val right = (left + 1).coerceAtMost(plane.width - 1)
        val bottom = (top + 1).coerceAtMost(plane.height - 1)
        val fx = (x - left).coerceIn(0f, 1f); val fy = (y - top).coerceIn(0f, 1f)
        val a = plane[left, top] * (1f - fx) + plane[right, top] * fx
        val b = plane[left, bottom] * (1f - fx) + plane[right, bottom] * fx
        return a * (1f - fy) + b * fy
    }
}
