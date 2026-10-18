package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class PreviewPlaybackPolicyTest {
    // Removing generation checks lets a stale audio callback restart an obsolete project/surface.
    @Test fun obsoleteAudioAndFrameCallbacksCannotAuthorizePlayback() {
        val policy = PreviewPlaybackPolicy()
        policy.play()
        val old = policy.seek(1_000)
        policy.frameReady(old); policy.audioReady(old)
        assertTrue(policy.canStart(old))
        policy.seeks.invalidateProject()
        val project = policy.seek(2_000)
        assertFalse(policy.audioReady(old))
        assertFalse(policy.pauseIfCurrent(old))
        policy.frameReady(project)
        assertFalse(policy.canStart(project))
        policy.seeks.invalidateSurface()
        val surface = policy.seek(3_000)
        assertFalse(policy.audioReady(project))
        assertFalse(policy.frameReady(project))
        policy.audioReady(surface); policy.frameReady(surface)
        assertTrue(policy.canStart(surface))
        assertEquals(surface to 3_000L, policy.seeks.takeLatest())
        assertNull(policy.seeks.takeLatest())
    }

    @Test fun pausedScrubRemainsPausedAndPlayingScrubResumesOnlyAfterBothReadinessSignals() {
        val policy = PreviewPlaybackPolicy()
        policy.beginScrub(); policy.endScrub()
        val paused = policy.seek(0)
        policy.frameReady(paused); policy.audioReady(paused)
        assertFalse(policy.canStart(paused))
        policy.play(); policy.beginScrub()
        val dragging = policy.seek(10)
        policy.frameReady(dragging); policy.audioReady(dragging)
        assertFalse(policy.canStart(dragging))
        policy.endScrub()
        assertTrue(policy.canStart(dragging))
        policy.pause()
        assertFalse(policy.canStart(dragging))
    }

    @Test fun savedSixtyGridUsesExistingFramesAndEarlierTie() {
        val graph = MontageGraph(1000, 1000, clips = listOf(MontageGraph.Clip("c", 0, 1000, 1000,
            MontageGraph.ShotRole.ACTION, MontageGraph.Transition.HARD_CUT, MontageGraph.Motion.HOLD, 1f, 0)))
        val plan = HighQualityFramePlan.build(graph, 60)
        assertSame(plan.frames[1], PreviewFrameSelection.nearest(plan, 17_000))
        assertSame(plan.frames[1], PreviewFrameSelection.nearest(plan, 25_000))
        assertSame(plan.frames.last(), PreviewFrameSelection.nearest(plan, Long.MAX_VALUE))
    }

    @Test fun attachmentResetClearsAbandonedScrubButDoesNotResumeIt() {
        val policy = PreviewPlaybackPolicy()
        policy.play(); policy.beginScrub(); policy.reset()
        val attached = policy.seek(0)
        policy.frameReady(attached); policy.audioReady(attached)
        assertFalse(policy.canStart(attached))
        policy.play()
        assertTrue(policy.canStart(attached))
    }

    @Test fun loopingClockRetainsGlobalTimeAndUsesMonotonicPredictionWhenPositionMissing() {
        assertEquals(250_000L, PreviewMusicPosition.seekPosition(2_250_000, 1_000_000))
        assertEquals(3_020_000L, PreviewMusicPosition.globalPosition(20_000, 1_000_000, 3_010_000))
        assertEquals(3_010_000L, PreviewMusicPosition.globalPosition(null, 1_000_000, 3_010_000))
        assertEquals(2_990_000L, PreviewMusicPosition.globalPosition(990_000, 1_000_000, 3_010_000))
    }
}
