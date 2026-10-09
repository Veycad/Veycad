package com.example.autoedit

import org.junit.Assert.*
import org.junit.Test

class CameraMotionConsensusTest {
    private fun cells(dx: Float = 0f, dy: Float = 0f) = List(16) { i ->
        CameraMotionConsensus.Cell((i % 4 + .5f) / 4, (i / 4 + .5f) / 4, dx, dy, 1f, 0f) }
    @Test fun static_background_confirms_zero_camera_instead_of_assuming_it() {
        val result = requireNotNull(CameraMotionConsensus.measure(cells()))
        assertEquals(0f, result.x, 0f)
        assertEquals(4, result.coveredQuadrants)
    }
    @Test fun coherent_pan_is_measured() {
        for ((x, y) in listOf(2f / 3f to 0f, -2f / 3f to 0f, 0f to .5f, .3f to -.4f)) {
            val measured = requireNotNull(CameraMotionConsensus.measure(cells(x, y)))
            assertEquals(x, measured.x, .00001f)
            assertEquals(y, measured.y, .00001f)
            assertEquals(16, measured.agreeingCells)
            assertEquals(4, measured.coveredQuadrants)
        }
    }
    @Test fun person_cells_cannot_vote_for_camera() {
        assertNull(CameraMotionConsensus.measure(cells(.8f).map { it.copy(personSupport = 1f) }))
    }
    @Test fun conflicting_background_is_unknown_not_zero() {
        assertNull(CameraMotionConsensus.measure(cells().mapIndexed { i, c -> c.copy(x = if (i < 8) .8f else -.8f) }))
    }
    @Test fun spatially_local_patch_cannot_confirm_whole_camera() {
        assertNull(CameraMotionConsensus.measure(cells().map { it.copy(centerX = .1f, centerY = .1f) }))
    }
    @Test fun low_confidence_matches_are_unknown() {
        assertNull(CameraMotionConsensus.measure(cells().map { it.copy(confidence = .1f) }))
    }

    @Test fun minimum_witness_and_confidence_boundaries_require_distributed_background() {
        val supported = cells(.2f).filterIndexed { index, _ -> index % 4 == 0 || index % 4 == 3 }
            .map { it.copy(confidence = .5f, personSupport = .15f) }
        val accepted = requireNotNull(CameraMotionConsensus.measure(supported))
        assertEquals(8, accepted.agreeingCells)
        assertEquals(4, accepted.coveredQuadrants)
        assertEquals(.2f, accepted.x, .000001f)
        assertEquals(.5f, accepted.confidence, 0f)
        assertNull(CameraMotionConsensus.measure(supported.dropLast(1)))
        assertNull(CameraMotionConsensus.measure(supported.map { it.copy(confidence = .499f) }))
        assertNull(CameraMotionConsensus.measure(supported.map { it.copy(personSupport = .151f) }))
    }

    @Test fun a_consensus_requires_seventy_percent_of_background_confidence_mass() {
        val majority = cells(.2f).mapIndexed { index, cell -> cell.copy(x = if (index < 12) .2f else -.8f) }
        val measured = requireNotNull(CameraMotionConsensus.measure(majority))
        assertEquals(.2f, measured.x, .000001f)
        assertEquals(12, measured.agreeingCells)
        assertEquals(.75f, measured.confidence, 0f)
        assertNull(CameraMotionConsensus.measure(majority.mapIndexed { index, cell ->
            if (index == 11) cell.copy(x = -.8f) else cell
        }))
    }
}
