package com.veycad.app

import com.veycad.app.MontageTimelineGeometry.Edge
import com.veycad.app.MontageTimelineGeometry.Extent
import com.veycad.app.MontageTimelineGeometry.Hit
import com.veycad.app.MontageTimelineGeometry.Mode
import com.veycad.app.MontageTimelineGeometry.Target
import org.junit.Assert.*
import org.junit.Test

class MontageTimelineGeometryTest {
    // Would catch rounding to nearest, source-time conversion, or truncating before scroll/zoom.
    @Test fun frameAtFloorsOutputCoordinatesAndClampsOrigin() {
        assertEquals(0L, MontageTimelineGeometry.frameAt(0f, 0f, 2f))
        assertEquals(0L, MontageTimelineGeometry.frameAt(-3f, 0f, 2f))
        assertEquals(3L, MontageTimelineGeometry.frameAt(6f, 0f, 2f))
        assertEquals(3L, MontageTimelineGeometry.frameAt(7.9f, 0f, 2f))
        assertEquals(5L, MontageTimelineGeometry.frameAt(7f, 3f, 2f))
        assertEquals(20L, MontageTimelineGeometry.frameAt(7f, 3f, .5f))
        assertEquals(1L, MontageTimelineGeometry.frameAt(1_000_000f, 0f, 1_000_000f))
        // Add in Double: adding these finite Floats in Float would overflow.
        assertEquals(2L, MontageTimelineGeometry.frameAt(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE))
    }

    @Test fun frameAtRejectsInvalidInputsAndLongOverflow() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            invalid { MontageTimelineGeometry.frameAt(bad, 0f, 1f) }
            invalid { MontageTimelineGeometry.frameAt(0f, bad, 1f) }
            invalid { MontageTimelineGeometry.frameAt(0f, 0f, bad) }
        }
        for (bad in listOf(0f, -1f)) invalid { MontageTimelineGeometry.frameAt(0f, 0f, bad) }
        invalid { MontageTimelineGeometry.frameAt(Float.MAX_VALUE, 0f, Float.MIN_VALUE) }
        invalid { MontageTimelineGeometry.frameAt(9_223_372_036_854_775_808f, 0f, 1f) }
    }

    @Test fun windowOriginKeepsAdjacentLateFramesExact() {
        val origin = 9_007_199_254_740_993L
        assertEquals(origin, MontageTimelineGeometry.frameAt(0f, 0f, 2f, origin))
        assertEquals(origin + 1, MontageTimelineGeometry.frameAt(2f, 0f, 2f, origin))
        assertEquals(origin - 1, MontageTimelineGeometry.frameAt(-1f, 0f, 2f, origin))
        assertEquals(0L, MontageTimelineGeometry.frameAt(-10f, 0f, 1f, 2L))
        invalid { MontageTimelineGeometry.frameAt(0f, 0f, 1f, -1L) }
        overflow { MontageTimelineGeometry.frameAt(1f, 0f, 1f, Long.MAX_VALUE) }
    }

    @Test fun bodyIsHalfOpenAndExactBoundarySelectsIncomingClip() {
        val layout = layout(listOf(Extent("A", 60), Extent("B", 60)))
        assertEquals(found(Target.Body("A")), hit(layout, 119.9f, Mode.BODY))
        assertEquals(found(Target.Body("B")), hit(layout, 120f, Mode.BODY))
        assertEquals(Hit.None, hit(layout, 240f, Mode.BODY))
        assertEquals(Hit.None, hit(layout, -1f, Mode.BODY))
        assertEquals(Hit.None, hit(layout, 300f, Mode.BODY))
        assertEquals(Hit.None, hit(layout, Float.NaN, Mode.BODY))
    }

    @Test fun layoutsFollowSavedThirtyAndSixtyFpsCounts() {
        for ((fps, expected) in listOf(30 to listOf(0L, 60L, 120L, 180L),
                60 to listOf(0L, 120L, 240L, 360L))) {
            val project = ManualMontageFixtures.linearProject(fps)
            val geometry = layout(project.current.clips.map { Extent(it.id, it.visible.count) }, width = 800f)
            assertEquals(expected.dropLast(1), geometry.clips.map { it.startFrame })
            assertEquals(expected.drop(1), geometry.clips.map { it.endFrameExclusive })
            assertEquals(expected.last(), geometry.totalFrames)
            assertEquals(listOf("A1", "B", "A2"), geometry.clips.map { it.clipId })
            assertEquals(ProjectClock(fps).timeUs(expected[1].toInt()), 2_000_000L)
        }
    }

    @Test fun reorderedRepeatedSourceSelectionsRetainClipIdentityAndCurrentCuts() {
        val project = EditableMontageCompilerTest.revised(ManualMontageFixtures.linearProject(), listOf(1, 0, 2))
        assertEquals(listOf(1, 0, 0), project.current.clips.map { it.sourceIndex })
        val geometry = layout(project.current.clips.map { Extent(it.id, it.visible.count) }, width = 400f)
        assertEquals(listOf("B", "A1", "A2"), geometry.clips.map { it.clipId })
        assertEquals(listOf(0L, 60L, 120L), geometry.clips.map { it.startFrame })
        assertNull(geometry.clips.first().incomingCut)
        assertEquals(found(Target.Cut("A1")), hit(geometry, 120f, Mode.CUTS))
        assertEquals(found(Target.Body("A2")), hit(geometry, 250f, Mode.BODY))
    }

    @Test fun trimShiftsFollowingOutputExtentsWithoutChangingRetainedSourcePhase() {
        val original = ManualMontageFixtures.linearProject()
        val trimmed = EditableMontageCompilerTest.revised(original, listOf(0, 1, 2), "A1", 15)
        val geometry = layout(trimmed.current.clips.map { Extent(it.id, it.visible.count) }, width = 400f)
        assertEquals(listOf(0L, 45L, 105L), geometry.clips.map { it.startFrame })
        assertEquals(listOf(45L, 105L, 165L), geometry.clips.map { it.endFrameExclusive })
        assertEquals(found(Target.Body("B")), hit(geometry, 90f, Mode.BODY))
        assertEquals(15L, trimmed.current.clips.first().visible.start)
        assertEquals(15, trimmed.shared.current.clips.first().originalFrameOffset)
        assertEquals(original.current.clips.first().timeMap.sourceTimeUs(15),
            trimmed.current.clips.first().timeMap.sourceTimeUs(15))
        assertEquals(0L, original.current.clips.first().visible.start)
    }

    @Test fun geometryAgreesWithActualCompiledEditableFramePlanAtBothSavedRates() {
        for (fps in listOf(30, 60)) {
            val base = ManualMontageFixtures.linearProject(fps)
            val project = EditableMontageCompilerTest.revised(base, listOf(1, 0, 2), "B", 7)
            val graph = EditableMontageCompiler.compile(project)
            val timing = graph.editableTiming!!
            val plan = HighQualityFramePlan.build(graph, fps)
            val geometry = layout(project.current.clips.map { Extent(it.id, it.visible.count) }, width = 1000f)
            val expectedStarts = if (fps == 30) listOf(0L, 53L, 113L) else listOf(0L, 113L, 233L)
            assertEquals(expectedStarts, geometry.clips.map { it.startFrame })
            assertEquals(if (fps == 30) 173L else 353L, geometry.totalFrames)
            assertEquals(geometry.totalFrames, plan.frames.size.toLong())
            assertEquals(expectedStarts, timing.clips.map { it.startFrame })
            geometry.clips.forEachIndexed { index, clip ->
                assertEquals(clip.endFrameExclusive - clip.startFrame,
                    plan.frames.count { it.clipIndex == index }.toLong())
                assertEquals(index, plan.frames[clip.startFrame.toInt()].clipIndex)
                assertEquals(ProjectClock(fps).timeUs(clip.startFrame.toInt()),
                    plan.frames[clip.startFrame.toInt()].outputTimeUs)
            }
        }
    }

    @Test fun overlappingShortClipHandlesRequireExplicitEdgeChoice() {
        val geometry = layout(listOf(Extent("short", 5)))
        assertEquals(Hit.Ambiguous(listOf(Target.Handle("short", Edge.START), Target.Handle("short", Edge.END))),
            hit(geometry, 5f, Mode.HANDLES, "short"))
        assertEquals(found(Target.Handle("short", Edge.START)), hit(geometry, 5f, Mode.HANDLES, "short", Edge.START))
        assertEquals(found(Target.Handle("short", Edge.END)), hit(geometry, 5f, Mode.HANDLES, "short", Edge.END))
        assertEquals(found(Target.Body("short")), hit(geometry, 5f, Mode.BODY))
        assertEquals(Hit.None, hit(geometry, 5f, Mode.CUTS))
    }

    @Test fun adjacentHandlesRemainAmbiguousUntilClipAndEdgeAreChosen() {
        val geometry = layout(listOf(Extent("A", 60), Extent("B", 60)))
        assertEquals(Hit.Ambiguous(listOf(Target.Handle("A", Edge.END), Target.Handle("B", Edge.START))),
            hit(geometry, 120f, Mode.HANDLES))
        assertEquals(found(Target.Handle("B", Edge.START)), hit(geometry, 120f, Mode.HANDLES, "B", Edge.START))
        assertEquals(Hit.None, hit(geometry, 120f, Mode.HANDLES, "missing", Edge.START))
    }

    @Test fun cutModeAndBodyModeStaySeparateWithFirstAndLastRules() {
        val geometry = layout(listOf(Extent("A", 60), Extent("B", 60)), width = 300f)
        assertEquals(Hit.None, hit(geometry, 0f, Mode.CUTS))
        assertEquals(found(Target.Body("B")), hit(geometry, 120f, Mode.BODY))
        assertEquals(found(Target.Cut("B")), hit(geometry, 120f, Mode.CUTS))
        assertEquals("B", geometry.clips.first().nextBoundaryIncomingClipId)
        assertNull(geometry.clips.last().nextBoundaryIncomingClipId)
        assertEquals(found(Target.Handle("A", Edge.START)), hit(geometry, 0f, Mode.HANDLES, "A", Edge.START))
        assertEquals(found(Target.Handle("B", Edge.END)), hit(geometry, 239f, Mode.HANDLES, "B", Edge.END))
        assertEquals(Hit.None, hit(geometry, 240f, Mode.HANDLES, "B", Edge.END))
        assertEquals(Hit.None, hit(geometry, 240f, Mode.CUTS))
    }

    @Test fun closeCutTargetsDoNotGuessTheIncomingClip() {
        val geometry = layout(listOf(Extent("A", 5), Extent("B", 5), Extent("C", 5)))
        assertEquals(Hit.Ambiguous(listOf(Target.Cut("B"), Target.Cut("C"))), hit(geometry, 15f, Mode.CUTS))
    }

    @Test fun scrolledOffscreenHandlesCannotBeSelectedThroughExpandedTouchZones() {
        val geometry = layout(listOf(Extent("A", 60), Extent("B", 60)), scroll = 130f, width = 100f)
        assertEquals(2, geometry.clips.size)
        assertNull(geometry.clips[1].startHandle)
        assertNull(geometry.clips[1].endHandle)
        assertNull(geometry.clips[1].incomingCut)
        assertEquals(found(Target.Body("B")), hit(geometry, 0f, Mode.BODY))
        assertEquals(Hit.None, hit(geometry, 0f, Mode.HANDLES, "B", Edge.START))
        assertEquals(Hit.None, hit(geometry, 0f, Mode.CUTS))
        assertEquals(Hit.None, hit(geometry, 99f, Mode.HANDLES, "B", Edge.END))
        assertEquals(Hit.None, hit(geometry, 100f, Mode.BODY))
    }

    @Test fun visibleEdgeTouchZoneIsClippedToViewportAndContent() {
        val geometry = layout(listOf(Extent("A", 60), Extent("B", 60)), scroll = 110f, width = 100f)
        assertEquals(2, geometry.clips.size)
        assertEquals(MontageTimelineGeometry.PixelRange(0.0, 34.0), geometry.clips[1].startHandle)
        assertEquals(found(Target.Cut("B")), hit(geometry, 0f, Mode.CUTS))
        assertEquals(Hit.None, hit(geometry, 34f, Mode.CUTS))
        val leadingSpace = layout(listOf(Extent("A", 60)), scroll = -10f)
        assertEquals(Hit.None, hit(leadingSpace, 9f, Mode.HANDLES, "A", Edge.START))
        assertEquals(found(Target.Handle("A", Edge.START)), hit(leadingSpace, 10f, Mode.HANDLES, "A", Edge.START))
    }

    @Test fun zoomScrollAndTouchWidthUseTheSamePixelSpace() {
        val geometry = layout(listOf(Extent("A", 60), Extent("B", 60)), scale = .5f, scroll = 20f, width = 100f, touch = 96f)
        assertEquals(2, geometry.clips.size)
        assertEquals(MontageTimelineGeometry.PixelRange(0.0, 10.0), geometry.clips[0].body)
        assertEquals(MontageTimelineGeometry.PixelRange(10.0, 40.0), geometry.clips[1].body)
        assertEquals(MontageTimelineGeometry.PixelRange(0.0, 40.0), geometry.clips[1].startHandle)
        assertEquals(found(Target.Body("B")), hit(geometry, 10f, Mode.BODY))
        assertEquals(60L, MontageTimelineGeometry.frameAt(10f, 20f, .5f))
    }

    @Test fun layoutRejectsInvalidDimensionsIdentitiesAndCounts() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f)) {
            invalid { layout(listOf(Extent("A", 1)), width = bad) }
            invalid { layout(listOf(Extent("A", 1)), scale = bad) }
            invalid { layout(listOf(Extent("A", 1)), touch = bad) }
        }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))
            invalid { layout(listOf(Extent("A", 1)), scroll = bad) }
        invalid { layout(listOf(Extent("", 1))) }
        invalid { layout(listOf(Extent("A", 1), Extent("A", 2))) }
        invalid { layout(listOf(Extent("A", 0))) }
        invalid { layout(listOf(Extent("A", -1))) }
        invalid { MontageTimelineGeometry.layout(listOf(Extent("A", 1)), 100f, 0f, 1f, 48f, -1L) }
        overflow { layout(listOf(Extent("A", Long.MAX_VALUE), Extent("B", 1))) }
    }

    @Test fun emptyLayoutHasNoContentOrTargets() {
        val geometry = layout(emptyList())
        assertEquals(0L, geometry.totalFrames)
        assertTrue(geometry.clips.isEmpty())
        for (mode in Mode.entries) assertEquals(Hit.None, hit(geometry, 0f, mode))
    }

    @Test fun sparseLargeTimelineUsesExactWindowBoundsWithoutPerFrameWork() {
        val origin = 9_007_199_254_740_993L
        val extents = listOf(Extent("large", origin), Extent("one", 1), Extent("tail", 3))
        val geometry = MontageTimelineGeometry.layout(extents, 100f, 0f, 2f, 48f, origin)
        assertEquals(3, geometry.clips.size)
        assertEquals(origin + 4, geometry.totalFrames)
        assertEquals(origin, geometry.clips[1].startFrame)
        assertEquals(origin + 1, geometry.clips[1].endFrameExclusive)
        assertEquals(MontageTimelineGeometry.PixelRange(0.0, 2.0), geometry.clips[1].body)
        assertEquals(MontageTimelineGeometry.PixelRange(2.0, 8.0), geometry.clips[2].body)
        assertEquals(found(Target.Body("one")), hit(geometry, 0f, Mode.BODY))
        assertEquals(found(Target.Body("tail")), hit(geometry, 2f, Mode.BODY))
        assertEquals(Hit.None, hit(geometry, 8f, Mode.BODY))
    }

    @Test fun extremeZoomKeepsExposedPixelRangesFinite() {
        val large = layout(listOf(Extent("A", Long.MAX_VALUE)), scale = Float.MAX_VALUE)
        assertEquals(1, large.clips.size)
        assertEquals(MontageTimelineGeometry.PixelRange(0.0, 300.0), large.clips.single().body)
        assertEquals(found(Target.Body("A")), hit(large, 299f, Mode.BODY))
        val small = layout(listOf(Extent("A", Long.MAX_VALUE)), scale = Float.MIN_VALUE)
        assertTrue(small.clips.single().body!!.endExclusive > 0.0)
        assertEquals(found(Target.Body("A")), hit(small, 0f, Mode.BODY))
        assertEquals(Hit.None, hit(small, 1f, Mode.BODY))
    }

    @Test fun layoutOwnsItsSmallInputSnapshot() {
        val extents = mutableListOf(Extent("A", 60), Extent("B", 60))
        val geometry = layout(extents)
        extents.clear()
        assertEquals(found(Target.Body("B")), hit(geometry, 120f, Mode.BODY))
        assertEquals(120L, geometry.totalFrames)
    }

    private fun layout(extents: List<Extent>, width: Float = 300f, scroll: Float = 0f,
        scale: Float = 2f, touch: Float = 48f) = MontageTimelineGeometry.layout(extents, width, scroll, scale, touch)
    private fun hit(layout: MontageTimelineGeometry.Layout, x: Float, mode: Mode,
        clip: String? = null, edge: Edge? = null) = MontageTimelineGeometry.hitTest(layout, x, mode, clip, edge)
    private fun found(target: Target) = Hit.Found(target)
    private fun invalid(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }
    private fun overflow(block: () -> Unit) { assertThrows(ArithmeticException::class.java, block) }
}
