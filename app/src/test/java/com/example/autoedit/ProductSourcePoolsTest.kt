package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductSourcePoolsTest {
    @Test fun heartbeat_selects_its_own_fifteen_distinct_moments() {
        val visual = visual()
        val pool = HeartbeatSourcePool.select(visual, FrameAttachmentTimeline())
        assertEquals("heartbeat-source-pool", pool.metadata.generator)
        assertEquals(15, pool.clips.size)
        assertFalse(pool.clips.any { it.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY })
        assertTrue(pool.clips.indices.all { index ->
            pool.clips.drop(index + 1).none { other ->
                overlap(pool.clips[index], other) > 0L
            }
        })
        val graph = HeartbeatDirector.build(pool, "heartbeat-audio", visualMap = visual)
        assertTrue(HeartbeatMontageProfile.appliesTo(graph))
        assertEquals(27, graph.clips.size)
        assertEquals(0f, RenderedMp4Acceptance.repeatedSourceRatio(graph), 0f)
        graph.clips.take(26).forEach { clip ->
            assertEquals(clip.outputDurationMs, clip.sourceEndMs - clip.sourceStartMs)
        }
    }

    @Test fun fear_reserves_continuous_opener_and_close_title_without_sigma() {
        val visual = visual()
        val pool = FearSourcePool.select(visual, FrameAttachmentTimeline())
        assertEquals("fear-source-pool", pool.metadata.generator)
        assertEquals(16, pool.clips.size)
        assertEquals(3_600L, pool.clips.first().sourceEndMs - pool.clips.first().sourceStartMs)
        assertTrue(FearOpeningEvidence.evaluate(visual.observations.filter {
            it.sourceTimeUs in pool.clips.first().sourceStartMs * 1_000L until pool.clips.first().sourceEndMs * 1_000L
        }).supported)
        assertEquals(MontageGraph.ShotRole.CLOSE, pool.clips[1].role)
        assertEquals(0L, overlap(pool.clips[0], pool.clips[1]))
        val graph = FearDirector.build(pool, "fear-audio", visual)
        assertTrue(FearStrobeProfile.appliesTo(graph))
        assertEquals(30, graph.clips.size)
        assertEquals(pool.clips[0].sourceStartMs, graph.clips[0].sourceStartMs)
        assertEquals(pool.clips[1].sourceStartMs, graph.clips[1].sourceStartMs)
        assertFalse(graph.clips.any { it.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY })
    }

    @Test fun minimum_fifteen_second_input_can_supply_both_single_source_products() {
        val visual = visual(15)
        val heartbeatPool = HeartbeatSourcePool.select(visual, FrameAttachmentTimeline())
        assertEquals(15, heartbeatPool.clips.size)
        val heartbeat = HeartbeatDirector.build(heartbeatPool, "heartbeat-audio", visualMap = visual)
        assertEquals(0f, RenderedMp4Acceptance.repeatedSourceRatio(heartbeat), 0f)
        heartbeat.clips.take(26).forEach { clip ->
            assertEquals(clip.outputDurationMs, clip.sourceEndMs - clip.sourceStartMs)
        }
        val fearPool = FearSourcePool.select(visual, FrameAttachmentTimeline())
        assertEquals(16, fearPool.clips.size)
        val fear = FearDirector.build(fearPool, "fear-audio", visual)
        assertEquals(0f, RenderedMp4Acceptance.repeatedSourceRatio(fear), .001f)
    }

    @Test fun sparse_samples_report_material_shortfall_not_a_codec_failure() {
        val source = VisualEventMap(15_000_000L, emptyList(), listOf(
            VisualEventMap.Observation(0L),
            VisualEventMap.Observation(14_000_000L)))
        val rejection = try {
            HeartbeatSourcePool.select(source, FrameAttachmentTimeline())
            throw AssertionError("Expected material rejection")
        } catch (error: MaterialRejectedException) { error }
        assertEquals("insufficient_distinct_moments", rejection.code)
    }

    private fun overlap(first: MontageGraph.Clip, second: MontageGraph.Clip): Long =
        (minOf(first.sourceEndMs, second.sourceEndMs) -
            maxOf(first.sourceStartMs, second.sourceStartMs)).coerceAtLeast(0L)

    private fun visual(seconds: Int = 30): VisualEventMap {
        val observations = (0 until seconds * 4).map { index ->
            val motion = .05f + (index % 9) * .055f
            VisualEventMap.Observation(
                sourceTimeUs = index * 250_000L,
                subjectMotion = VisualEventMap.Vector(x = motion),
                motionMeasurement = VisualEventMap.MotionMeasurement(
                    motion, 0f, 0f, 1f, 12, .8f, 40, 4, 250_000L),
                face = VisualEventMap.Face(.88f, (index % 11 - 5) * 5f),
                gestureConfidence = (index % 7) * .12f,
                visualQuality = .68f + (index % 5) * .05f,
                meanLuma = .20f + (index % 9) * .055f,
                composition = VisualEventMap.Composition(
                    .20f + (index % 8) * .09f, .8f, .8f, .8f, .8f),
                humanPresenceConfidence = .9f
            )
        }
        return VisualEventMap(seconds * 1_000_000L, emptyList(), observations)
    }
}
