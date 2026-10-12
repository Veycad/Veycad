package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class SourceTimeMapTest {
    private fun map() = SourceTimeMap(listOf(
        SourceTimeMap.Point(0, 100_000), SourceTimeMap.Point(3, 200_000),
        SourceTimeMap.Point(7, 500_000), SourceTimeMap.Point(10, 560_000)))

    @Test fun interpolatesNonuniformSourcePts() {
        assertEquals(133_333L, map().sample(1))
        assertEquals(275_000L, map().sample(4))
        assertEquals(540_000L, map().sample(9))
        assertEquals(560_000L, map().sample(10))
    }
    @Test fun slicePreservesOriginalSourceTimes() {
        val sliced = map().slice(3, 10)
        assertEquals(listOf(SourceTimeMap.Point(0, 200_000), SourceTimeMap.Point(4, 500_000),
            SourceTimeMap.Point(7, 560_000)), sliced.points)
        for (frame in 0..7) assertEquals(map().sample(frame + 3), sliced.sample(frame))
        assertEquals(listOf(SourceTimeMap.Point(0, 275_000), SourceTimeMap.Point(3, 500_000),
            SourceTimeMap.Point(5, 540_000)), map().slice(4, 9).points)
    }
    @Test fun extensionsUseEdgeSlopeAndPreserveExistingPoints() {
        val left = map().extendLeft(3, 0)
        assertEquals(0L, left.sample(0))
        for (point in map().points) assertEquals(point.sourceTimeUs, left.sample(point.localFrame + 3))
        val right = map().extendRight(2, 600_000)
        assertEquals(600_000L, right.sample(12))
        for (point in map().points) assertEquals(point.sourceTimeUs, right.sample(point.localFrame))
    }
    @Test fun extensionsRejectUnavailableFrames() {
        assertThrows(IllegalArgumentException::class.java) { map().extendLeft(4, 0) }
        assertThrows(IllegalArgumentException::class.java) { map().extendRight(3, 600_000) }
        assertThrows(IllegalArgumentException::class.java) { map().extendRight(Int.MAX_VALUE, Long.MAX_VALUE) }
        val huge = SourceTimeMap(listOf(SourceTimeMap.Point(0, 0), SourceTimeMap.Point(2, Long.MAX_VALUE)))
        assertEquals(4_611_686_018_427_387_904L, huge.sample(1))
        assertThrows(IllegalArgumentException::class.java) { huge.extendRight(1, Long.MAX_VALUE) }
    }
    @Test fun rejectsInvalidMapsAndRequests() {
        val invalid = listOf(emptyList(), listOf(SourceTimeMap.Point(0, 0)),
            listOf(SourceTimeMap.Point(1, 0), SourceTimeMap.Point(2, 10)),
            listOf(SourceTimeMap.Point(0, 10), SourceTimeMap.Point(0, 20)),
            listOf(SourceTimeMap.Point(0, 10), SourceTimeMap.Point(1, 5)),
            listOf(SourceTimeMap.Point(0, -1), SourceTimeMap.Point(1, 5)))
        invalid.forEach { points -> assertThrows(IllegalArgumentException::class.java) { SourceTimeMap(points) } }
        assertThrows(IllegalArgumentException::class.java) { map().sample(11) }
        assertThrows(IllegalArgumentException::class.java) { map().sample(-1) }
        assertThrows(IllegalArgumentException::class.java) { map().slice(3, 3) }
        assertThrows(IllegalArgumentException::class.java) { map().extendLeft(-1, 0) }
    }
    @Test fun snapshotsCallerPointsAndSupportsHolds() {
        val points = mutableListOf(SourceTimeMap.Point(0, 100), SourceTimeMap.Point(2, 100))
        val map = SourceTimeMap(points)
        points.clear()
        assertEquals(100L, map.extendRight(1, 100).sample(3))
    }
}
