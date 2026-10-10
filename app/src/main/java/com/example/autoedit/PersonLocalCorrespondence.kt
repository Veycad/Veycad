package com.veycad.app

import kotlin.math.abs
import kotlin.math.ceil

/** Smaller, denser body patches AFTER an independently supported camera is available.
 * Equal-area full-FOV sampling retains unsupported/soft-mask mass in the denominator.
 */
internal object PersonLocalCorrespondence {
    data class Result(val cells: List<SubjectMotionIntensity.Cell>,
                      val support: PersonMotionEvidence.SupportCounts, val witnesses: Int, val gridCells: Int)

    fun estimate(previous: LumaMotionEstimator.Plane?, current: LumaMotionEstimator.Plane,
                 mask: FrameAttachments.Plane, cameraX: Float, cameraY: Float): Result? {
        val prepared = LocalMotionCorrespondence.prepare(previous, current) ?: return null
        return estimate(prepared, mask, cameraX, cameraY)
    }

    fun estimate(prepared: LocalMotionCorrespondence.PreparedPair, mask: FrameAttachments.Plane,
                 cameraX: Float, cameraY: Float): Result? {
        val sampling = LocalMotionCorrespondence.Sampling(
            prepared.width.toFloat() / MotionAnalysisGeometry.REFERENCE_WIDTH,
            prepared.height.toFloat() / MotionAnalysisGeometry.REFERENCE_HEIGHT)
        val stepX = ceil(.5f * sampling.x).toInt().coerceAtLeast(1)
        val stepY = ceil(.5f * sampling.y).toInt() + 1
        val points = (stepY / 2 until prepared.height step stepY).flatMap { y ->
            (stepX / 2 until prepared.width step stepX).map { x -> x to y }
        }
        val probabilities = points.associateWith { (x, y) ->
            PersonMaskProjection.sample(mask, prepared.width, prepared.height, x, y)
        }
        val personPoints = points.filter { probabilities.getValue(it) >= .5f }
        val small = LocalMotionCorrespondence.estimate(prepared, sampling = sampling,
            patchFraction = .25f, requestedCenters = personPoints) ?: return null
        val pure = small.filter {
            PersonMaskProjection.patchMinimum(mask, prepared.width, prepared.height, it.centerX, it.centerY,
                it.patchRadiusX, it.patchRadiusY) >= .5f
        }
        // Smooth clothing may lack two-dimensional evidence in the smallest query.
        // Check both orientations and two short-axis widths. Every accepted
        // query must itself be person-pure and independently fit/unique/roundtrip reliable.
        // A confident disagreement between orientations is unknown, never score selection.
        val contextPoints = pure.map { it.centerX to it.centerY }
        val elongated = listOf(2f, 4f, 8f).flatMap { length -> listOf(
            LocalMotionCorrespondence.Sampling(1f, length),
            LocalMotionCorrespondence.Sampling(length, 1f),
            LocalMotionCorrespondence.Sampling(2f, length),
            LocalMotionCorrespondence.Sampling(length, 2f)) }.distinct().flatMap { shape ->
            val radiusX = ceil(.5f * sampling.x * shape.x).toInt()
            val radiusY = ceil(.5f * sampling.y * shape.y).toInt()
            val eligible = contextPoints.filter { (x, y) -> PersonMaskProjection.patchMinimum(mask,
                prepared.width, prepared.height, x, y, radiusX, radiusY) >= .5f }
            LocalMotionCorrespondence.estimate(prepared, sampling = sampling,
                patchFraction = .25f, requestedCenters = eligible, patchShape = shape).orEmpty()
                .filter { PersonMaskProjection.patchMinimum(mask, prepared.width, prepared.height,
                    it.centerX, it.centerY, it.patchRadiusX, it.patchRadiusY) >= .5f }
        }.groupBy { it.centerX to it.centerY }
        val adapted = pure.map { cell ->
            val candidates = (listOf(cell) + elongated[cell.centerX to cell.centerY].orEmpty())
                .filter { it.confidence >= .5f }
            if (candidates.isEmpty()) cell
            else if (candidates.any { it.x != candidates.first().x || it.y != candidates.first().y })
                cell.copy(confidence = 0f)
            // The largest actual validated footprint supplies context/owned area. Do not
            // select by activity or maximum confidence; all reliable scales must agree.
            else candidates.maxBy { (2 * it.patchRadiusX + 1) * (2 * it.patchRadiusY + 1) }
        }
        // Check a non-camera local deviation against larger independently reliable context.
        // It is only a contradiction check, never camera/person evidence of its own.
        // Incompatible confident scales are ambiguous,
        // not permission to pick whichever displacement makes the body look more active.
        val widerPoints = adapted.filter { it.confidence >= .5f &&
            (abs(it.x - cameraX) > .125f / (3 * sampling.x) ||
                abs(it.y - cameraY) > .125f / (3 * sampling.y)) }.map { it.centerX to it.centerY }
        val wider = LocalMotionCorrespondence.estimate(prepared, sampling = sampling,
            requestedCenters = widerPoints).orEmpty().filter { it.confidence >= .5f }
            .associateBy { it.centerX to it.centerY }
        val matched = adapted.map { cell ->
            val context = wider[cell.centerX to cell.centerY]
            if (context != null && (context.x != cell.x || context.y != cell.y)) cell.copy(confidence = 0f)
            else cell
        }
        val supported = matched.filter { it.confidence >= .5f }
        // Weight the actual unique person pixels used by validated patches, not just their
        // centers: center-only weighting systematically erodes thin limbs more than torsos.
        // Ownership never creates a new match/confidence; overlap never counts a pixel twice.
        val owner = IntArray(prepared.width * prepared.height) { -1 }
        val distance = FloatArray(owner.size) { Float.POSITIVE_INFINITY }
        for ((index, cell) in supported.withIndex()) {
            for (y in cell.centerY - cell.patchRadiusY..cell.centerY + cell.patchRadiusY)
                for (x in cell.centerX - cell.patchRadiusX..cell.centerX + cell.patchRadiusX) {
                    val dx = (x - cell.centerX) / sampling.x; val dy = (y - cell.centerY) / sampling.y
                    val d = dx * dx + dy * dy; val pixel = y * prepared.width + x
                    if (d < distance[pixel]) { distance[pixel] = d; owner[pixel] = index }
                }
        }
        val area = IntArray(supported.size)
        val mass = DoubleArray(supported.size)
        var unownedArea = 0; var unownedMass = 0.0
        for (y in 0 until prepared.height) for (x in 0 until prepared.width) {
            val probability = PersonMaskProjection.sample(mask, prepared.width, prepared.height, x, y)
            val patch = owner[y * prepared.width + x]
            if (patch < 0) { unownedArea++; unownedMass += probability }
            else { area[patch]++; mass[patch] += probability }
        }
        val cells = supported.mapIndexedNotNull { index, cell ->
            if (area[index] == 0) null else SubjectMotionIntensity.Cell(cell.x, cell.y, cell.confidence,
                (mass[index] / area[index]).toFloat(), area[index].toFloat())
        }.toMutableList()
        if (unownedArea > 0) {
            cells += SubjectMotionIntensity.Cell(0f, 0f, 0f,
                (unownedMass / unownedArea).toFloat(), unownedArea.toFloat())
        }
        // Dense overlapping samples improve localization, but must not multiply one patch into
        // four independent spatial witnesses. Count a deterministic disjoint-patch subset.
        val disjoint = mutableListOf<LocalMotionCorrespondence.Cell>()
        for (cell in supported) {
            if (disjoint.none { other ->
                abs(cell.centerX - other.centerX) <= cell.patchRadiusX + other.patchRadiusX &&
                    abs(cell.centerY - other.centerY) <= cell.patchRadiusY + other.patchRadiusY
            }) disjoint += cell
        }
        return Result(cells, PersonMotionEvidence.SupportCounts(personPoints.size,
            matched.count { it.textured }, matched.count { it.fit >= .5f },
            matched.count { it.uniqueness >= .5f }, matched.count { it.roundtrip },
            matched.count { it.confidence >= .5f }), disjoint.size, points.size)
    }
}
