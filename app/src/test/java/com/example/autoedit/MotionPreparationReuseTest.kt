package com.example.autoedit

import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.ceil
import org.junit.Assert.*
import org.junit.Test

/** An independently retained pre-refactor exhaustive implementation is the equivalence oracle. */
class MotionPreparationReuseTest {
    private fun plane(width: Int = 64, height: Int = 64, seed: Long = 703991) =
        LumaMotionEstimator.Plane(width, height, FloatArray(width * height).also { values ->
            val random = java.util.Random(seed)
            values.indices.forEach { values[it] = .2f + .5f * random.nextFloat() }
        })

    private fun shift(a: LumaMotionEstimator.Plane, dx: Float, dy: Float, gain: Float = 1f) =
        a.copy(luma = FloatArray(a.luma.size) { i ->
            val x = (i % a.width - dx).coerceIn(0f, a.width - 1f)
            val y = (i / a.width - dy).coerceIn(0f, a.height - 1f)
            val left = floor(x).toInt(); val top = floor(y).toInt()
            val right = (left + 1).coerceAtMost(a.width - 1)
            val bottom = (top + 1).coerceAtMost(a.height - 1)
            val fx = x - left; val fy = y - top
            val upper = a[left, top] * (1f - fx) + a[right, top] * fx
            val lower = a[left, bottom] * (1f - fx) + a[right, bottom] * fx
            .5f + gain * (upper * (1f - fy) + lower * fy - .5f)
        })

    private fun equivalent(a: LumaMotionEstimator.Plane?, b: LumaMotionEstimator.Plane,
                           centers: List<Pair<Int, Int>>?, sampling: LocalMotionCorrespondence.Sampling,
                           shape: LocalMotionCorrespondence.Sampling = LocalMotionCorrespondence.Sampling(),
                           fraction: Float = .25f): List<LocalMotionCorrespondence.Cell>? {
        val old = PreReuseLocalMotionOracle.estimate(a, b, sampling =
            PreReuseLocalMotionOracle.Sampling(sampling.x, sampling.y), patchFraction = fraction,
            requestedCenters = centers, patchShape = PreReuseLocalMotionOracle.Sampling(shape.x, shape.y))
        val standalone = LocalMotionCorrespondence.estimate(a, b, sampling = sampling,
            patchFraction = fraction, requestedCenters = centers, patchShape = shape)
        val prepared = LocalMotionCorrespondence.prepare(a, b)
        val reused = prepared?.let { LocalMotionCorrespondence.estimate(it, sampling = sampling,
            patchFraction = fraction, requestedCenters = centers, patchShape = shape) }
        fun assertCells(actual: List<LocalMotionCorrespondence.Cell>?) {
            if (old == null) { assertNull(actual); return }
            val cells = requireNotNull(actual)
            assertEquals(old.size, cells.size)
            old.zip(cells).forEach { (expected, got) ->
                assertEquals(expected.centerX, got.centerX); assertEquals(expected.centerY, got.centerY)
                assertEquals(expected.x.toRawBits(), got.x.toRawBits())
                assertEquals(expected.y.toRawBits(), got.y.toRawBits())
                assertEquals(expected.confidence.toRawBits(), got.confidence.toRawBits())
                assertEquals(expected.textured, got.textured)
                assertEquals(expected.fit.toRawBits(), got.fit.toRawBits())
                assertEquals(expected.uniqueness.toRawBits(), got.uniqueness.toRawBits())
                assertEquals(expected.roundtrip, got.roundtrip)
                assertEquals(expected.patchRadiusX, got.patchRadiusX)
                assertEquals(expected.patchRadiusY, got.patchRadiusY)
            }
        }
        assertCells(standalone); assertCells(reused)
        return reused
    }

    @Test fun exhaustive_subpixel_gain_offset_and_mismatch_are_bit_exact_in_both_orientations() {
        for ((w, h) in listOf(64 to 64, 144 to 81, 81 to 144)) {
            val a = plane(w, h)
            val sampling = LocalMotionCorrespondence.Sampling(w / 48f, h / 72f)
            val centers = listOf(w / 2 to h / 2, w / 2 + 2 to h / 2 - 2, w / 2 to h / 2, 0 to 0)
            val variants = listOf(a, shift(a, .25f, -.5f), shift(a, 2f, 0f, .6f),
                a.copy(luma = FloatArray(a.luma.size) { a.luma[it] + .1f }), plane(w, h, 812773))
            for (b in variants) for (shape in listOf(
                LocalMotionCorrespondence.Sampling(), LocalMotionCorrespondence.Sampling(1f, 4f),
                LocalMotionCorrespondence.Sampling(4f, 1f), LocalMotionCorrespondence.Sampling(2f, 8f)))
                equivalent(a, b, centers, sampling, shape)
        }
    }

    @Test fun flat_periodic_and_tied_basins_preserve_unknown_flags_and_order() {
        val a = plane()
        val periodic = a.copy(luma = FloatArray(a.luma.size) { i ->
            if ((i % a.width) % 3 == 0) .8f else .2f
        })
        val flat = a.copy(luma = FloatArray(a.luma.size) { .4f })
        val centers = listOf(32 to 32, 31 to 30, 32 to 32, 1 to 1)
        for (b in listOf(periodic, flat)) {
            val cells = requireNotNull(equivalent(b, b, centers, LocalMotionCorrespondence.Sampling()))
            assertTrue(cells.all { it.confidence < .5f })
        }
    }

    @Test fun differently_moving_inner_and_outer_footprints_preserve_conflicting_reliable_shapes() {
        val a = plane(96, 96)
        val outer = shift(a, -1f, 0f)
        val inner = shift(a, 2f, 0f)
        val b = outer.copy(luma = outer.luma.copyOf().also { values ->
            for (y in 44..52) for (x in 44..52) values[y * 96 + x] = inner[x, y]
        })
        val sampling = LocalMotionCorrespondence.Sampling()
        val small = requireNotNull(equivalent(a, b, listOf(48 to 48), sampling)).single()
        val large = requireNotNull(equivalent(a, b, listOf(48 to 48), sampling,
            LocalMotionCorrespondence.Sampling(32f, 32f))).single()
        assertTrue(small.confidence >= .5f && large.confidence >= .5f)
        assertNotEquals(small.x.toRawBits(), large.x.toRawBits())
    }

    @Test fun missing_unequal_tiny_and_empty_center_contracts_match_the_old_implementation() {
        val a = plane()
        val sampling = LocalMotionCorrespondence.Sampling()
        equivalent(null, a, null, sampling)
        equivalent(plane(65, 64), a, null, sampling)
        equivalent(plane(8, 8), plane(8, 8), null, sampling)
        assertTrue(requireNotNull(equivalent(a, a, emptyList(), sampling)).isEmpty())
        assertTrue(requireNotNull(equivalent(a, a, listOf(0 to 0, 1 to 1), sampling)).isEmpty())
        equivalent(plane(17, 17), plane(17, 17), null, sampling, fraction = 1f)
    }

    @Test fun prepared_pair_is_a_snapshot_not_an_alias_or_a_global_cache() {
        val a = plane(); val b = shift(a, .5f, 0f)
        val prepared = requireNotNull(LocalMotionCorrespondence.prepare(a, b))
        val centers = listOf(32 to 32)
        val before = LocalMotionCorrespondence.estimate(prepared, requestedCenters = centers)
        a.luma.fill(.2f); b.luma.fill(.8f)
        assertEquals(before, LocalMotionCorrespondence.estimate(prepared, requestedCenters = centers))
        val changed = requireNotNull(LocalMotionCorrespondence.prepare(a, b))
        assertNotEquals(before, LocalMotionCorrespondence.estimate(changed, requestedCenters = centers))
    }

    @Test fun fourteen_shape_calls_reuse_two_planes_instead_of_twenty_eight() {
        val a = plane(144, 81); val b = shift(a, .25f, .5f)
        val prepared = requireNotNull(LocalMotionCorrespondence.prepare(a, b))
        assertEquals(367866, prepared.interpolatedSamples)
        var repeatedSamples = 0
        repeat(14) { repeatedSamples += requireNotNull(LocalMotionCorrespondence.prepare(a, b)).interpolatedSamples }
        assertEquals(5150124, repeatedSamples)
        val sampling = MotionAnalysisGeometry.sampling(b)
        repeat(14) {
            LocalMotionCorrespondence.estimate(prepared, sampling = sampling, requestedCenters = listOf(72 to 40))
        }
        assertEquals(367866, prepared.interpolatedSamples)
        // Structural allocation/sample counts, deliberately not a flaky wall-clock speed assertion.
    }

    @Test fun dense_candidate_reuse_is_bit_exact_for_footprints_lighting_noise_and_both_source_directions() {
        for ((w, h) in listOf(64 to 64, 144 to 81, 81 to 144)) {
            val a = plane(w, h, 825633)
            val sampling = LocalMotionCorrespondence.Sampling(w / 48f, h / 72f)
            val centers = (-1..1).flatMap { dy -> (-1..1).map { dx -> w / 2 + dx to h / 2 + dy } } +
                listOf(w / 2 to h / 2, 0 to 0)
            val variants = listOf(a, shift(a, .25f, -.5f), shift(a, 1.25f, -.25f, .6f),
                a.copy(luma = FloatArray(a.luma.size) { a.luma[it] + .1f }), plane(w, h, 732941))
            val shapes = listOf(LocalMotionCorrespondence.Sampling(),
                LocalMotionCorrespondence.Sampling(1f, 4f), LocalMotionCorrespondence.Sampling(4f, 1f),
                LocalMotionCorrespondence.Sampling(2f, 8f))
            for (b in variants) for (shape in shapes) {
                equivalent(a, b, centers, sampling, shape)
                equivalent(b, a, centers.reversed(), sampling, shape)
            }
        }
    }

    @Test fun normalization_memo_retains_flat_periodic_ties_and_reset_between_footprints() {
        val a = plane()
        val flat = a.copy(luma = FloatArray(a.luma.size) { .4f })
        val periodic = a.copy(luma = FloatArray(a.luma.size) { i ->
            if ((i % a.width) % 3 == 0) .8f else .2f
        })
        val centers = (30..32).flatMap { y -> (30..32).map { x -> x to y } }
        for (source in listOf(flat, periodic)) {
            for (shape in listOf(LocalMotionCorrespondence.Sampling(),
                LocalMotionCorrespondence.Sampling(4f, 1f), LocalMotionCorrespondence.Sampling(1f, 4f))) {
                equivalent(source, source, centers, LocalMotionCorrespondence.Sampling(), shape)
            }
        }
        val prepared = requireNotNull(LocalMotionCorrespondence.prepare(a, shift(a, .25f, 0f)))
        val small = LocalMotionCorrespondence.estimate(prepared, requestedCenters = centers, patchFraction = .25f)
        LocalMotionCorrespondence.estimate(prepared, requestedCenters = centers, patchFraction = .25f,
            patchShape = LocalMotionCorrespondence.Sampling(4f, 1f))
        assertEquals(small, LocalMotionCorrespondence.estimate(prepared,
            requestedCenters = centers, patchFraction = .25f))
    }

    @Test fun source_only_normalizations_are_reused_structurally_but_not_retained_between_estimates() {
        val a = plane(144, 81, 741913)
        val b = shift(a, .25f, 0f)
        val sampling = MotionAnalysisGeometry.sampling(b)
        val centers = (39..41).flatMap { y -> (71..73).map { x -> x to y } }
        val prepared = requireNotNull(LocalMotionCorrespondence.prepare(a, b))
        val first = LocalMotionCorrespondence.NormalizationAudit()
        val second = LocalMotionCorrespondence.NormalizationAudit()
        val cells = LocalMotionCorrespondence.estimate(prepared, sampling = sampling,
            patchFraction = .25f, requestedCenters = centers, normalizationAudit = first)
        assertEquals(cells, LocalMotionCorrespondence.estimate(prepared, sampling = sampling,
            patchFraction = .25f, requestedCenters = centers, normalizationAudit = second))
        // The nine textured queries still visit ALL 73*27 hypotheses in EACH direction.
        val requestsPerDirection = centers.size * 1971L
        assertEquals(requestsPerDirection, first.previousHits + first.previousMisses)
        assertEquals(requestsPerDirection, first.currentHits + first.currentMisses)
        assertTrue(first.previousMisses > 0L && first.currentMisses > 0L)
        assertTrue(first.previousHits > first.previousMisses)
        assertTrue(first.currentHits > first.currentMisses)
        assertEquals(3_310_794L, first.arrayPayloadBytes)
        assertEquals(first.previousMisses, second.previousMisses)
        assertEquals(first.currentMisses, second.currentMisses)
        assertEquals(first.previousHits, second.previousHits)
        assertEquals(first.currentHits, second.currentHits)
        assertEquals(first.arrayPayloadBytes, second.arrayPayloadBytes)
        // No timing claim: counts demonstrate saved normalization work, not device speed.
    }

    @Test fun lazy_normalization_tables_are_absent_for_flat_and_empty_queries() {
        val flat = plane().let { it.copy(luma = FloatArray(it.luma.size) { .4f }) }
        val prepared = requireNotNull(LocalMotionCorrespondence.prepare(flat, flat))
        for (centers in listOf(emptyList(), listOf(31 to 31, 32 to 32))) {
            val audit = LocalMotionCorrespondence.NormalizationAudit()
            LocalMotionCorrespondence.estimate(prepared, requestedCenters = centers, normalizationAudit = audit)
            assertEquals(0L, audit.previousHits + audit.previousMisses + audit.currentHits + audit.currentMisses)
            assertEquals(0L, audit.arrayPayloadBytes)
        }
    }

    @Test fun dense_memo_calls_preserve_the_immutable_snapshot_after_input_mutation() {
        val a = plane(); val b = shift(a, .25f, -.5f)
        val centers = (30..32).flatMap { y -> (30..32).map { x -> x to y } }
        val sampling = LocalMotionCorrespondence.Sampling(64 / 48f, 64 / 72f)
        val shapes = listOf(LocalMotionCorrespondence.Sampling(),
            LocalMotionCorrespondence.Sampling(4f, 1f), LocalMotionCorrespondence.Sampling(1f, 4f))
        val prepared = requireNotNull(LocalMotionCorrespondence.prepare(a, b))
        val oldVerified = shapes.map { shape -> equivalent(a, b, centers, sampling, shape) }
        a.luma.fill(.2f); b.luma.fill(.8f)
        for ((index, shape) in shapes.withIndex()) {
            assertEquals(oldVerified[index], LocalMotionCorrespondence.estimate(prepared,
                sampling = sampling, patchFraction = .25f, requestedCenters = centers, patchShape = shape))
        }
    }
}


/** Pixel correspondence for motion evidence, separate from the editorial blur-flow field. */
private object PreReuseLocalMotionOracle {
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
    private class QuarterPlane(val width: Int, val values: FloatArray) {
        operator fun get(x: Int, y: Int): Float = values[y * width + x]
    }

    fun estimate(previous: LumaMotionEstimator.Plane?, current: LumaMotionEstimator.Plane,
                 gridWidth: Int = 12, gridHeight: Int = 18, searchRadius: Int = 3,
                 patchRadius: Int = 2, sampling: Sampling = Sampling(), patchFraction: Float = 1f,
                 requestedCenters: List<Pair<Int, Int>>? = null,
                 patchShape: Sampling = Sampling()): List<Cell>? {
        if (previous == null || previous.width != current.width || previous.height != current.height) return null
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
        if (current.width <= 2 * padX || current.height <= 2 * padY || stepsX < 1 || stepsY < 1) return null
        val centers = requestedCenters?.filter { (x, y) ->
            x in padX until current.width - padX && y in padY until current.height - padY
        }?.distinct() ?: List(gridWidth * gridHeight) { i ->
            val x = (((i % gridWidth + .5f) * current.width / gridWidth).toInt())
                .coerceIn(padX, current.width - padX - 1)
            val y = (((i / gridWidth + .5f) * current.height / gridHeight).toInt())
                .coerceIn(padY, current.height - padY - 1)
            x to y
        }.distinct()
        if (centers.isEmpty()) return emptyList()
        // Reuse interpolated values across all patches rather than resampling each candidate.
        val previousQuarter = quarterPlane(previous)
        val currentQuarter = quarterPlane(current)
        // Border clamping must not turn one physical patch into several support witnesses.
        return centers.map { (x, y) ->
            val forward = match(previousQuarter, currentQuarter, x * 4, y * 4, stepsX, stepsY, radiusX, radiusY, sampling)
            val backward = match(currentQuarter, previousQuarter,
                x * 4 - (forward.dx * 4).toInt(), y * 4 - (forward.dy * 4).toInt(), stepsX, stepsY, radiusX, radiusY, sampling)
            val consistent = abs(forward.dx + backward.dx) <= sampling.x &&
                abs(forward.dy + backward.dy) <= sampling.y
            Cell(x, y, forward.dx / (searchRadius * sampling.x), forward.dy / (searchRadius * sampling.y),
                if (consistent) minOf(forward.confidence, backward.confidence) else 0f,
                forward.textured && backward.textured, minOf(forward.fit, backward.fit),
                minOf(forward.uniqueness, backward.uniqueness), consistent, radiusX, radiusY)
        }
    }

    private fun match(previous: QuarterPlane, current: QuarterPlane,
                      x: Int, y: Int, stepsX: Int, stepsY: Int, radiusX: Int, radiusY: Int, sampling: Sampling): Match {
        val patchWidth = 2 * radiusX + 1
        val query = FloatArray(patchWidth * (2 * radiusY + 1)) { i ->
            current[x + (i % patchWidth - radiusX) * 4, y + (i / patchWidth - radiusY) * 4] }
        val mean = query.average().toFloat()
        query.indices.forEach { query[it] -= mean }
        val texture = query.sumOf { abs(it).toDouble() }.toFloat() / query.size
        if (texture < .02f) return Match(0f, 0f, 0f, 0f, false)
        val values = FloatArray(query.size)
        fun error(dx: Int, dy: Int): Float {
            var candidateMean = 0f
            for (i in values.indices) {
                values[i] = previous[x + (i % patchWidth - radiusX) * 4 - dx,
                    y + (i / patchWidth - radiusY) * 4 - dy]
                candidateMean += values[i]
            }
            candidateMean /= query.size
            var candidateTexture = 0f
            for (i in values.indices) {
                values[i] -= candidateMean
                candidateTexture += abs(values[i])
            }
            candidateTexture /= query.size
            // Mean removal handles offset only. A bounded positive contrast gain additionally
            // models affine lighting without pretending a flat candidate has spatial evidence.
            if (candidateTexture < .02f) return Float.POSITIVE_INFINITY
            val gain = texture / candidateTexture
            if (gain !in .5f..2f) return Float.POSITIVE_INFINITY
            var error = 0f
            for (i in query.indices) error += abs(query[i] -
                gain * values[i])
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
