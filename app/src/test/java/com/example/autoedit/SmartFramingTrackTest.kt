package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class SmartFramingTrackTest {
    private fun mask(x: Float) = FrameAttachments.Plane(20, 10,
        FloatArray(200) { i -> if (i % 20 in ((x * 20).toInt() - 2)..((x * 20).toInt() + 1) && i / 20 in 3..6) 1f else 0f }, .9f)
    private fun frame(t: Long, x: Float, quality: Float = .9f) =
        FrameAttachments(t, mask(x), subjectQuality = quality)
    private fun observation(t: Long, count: Int? = 1) = VisualEventMap.Observation(t,
        faceInferenceSucceeded = count != null, detectedFaceCount = count)
    private fun track(frames: List<FrameAttachments>, observations: List<VisualEventMap.Observation> =
        frames.map { observation(it.sourceTimeUs) }, windows: List<VisualEventMap.UsableWindow> =
        listOf(VisualEventMap.UsableWindow(0, 2_000_000))) = SmartFramingTrack.build(
        MediaFrameVisualAnalyzer.Result(SourceAnalysisProfile.EDITORIAL_SEMANTICS, 0, 2_000_000,
            observations, FrameAttachmentTimeline(frames), observations.size, frames.size, 0), windows)

    @Test fun same_pts_after_forward_and_backward_seek_has_same_center() {
        val track = track(listOf(frame(0, .2f), frame(250_000, .3f), frame(500_000, .4f)))
        val restored = SmartFramingTrack.fromPoints(track.points, track.sourceWindows)
        for (t in listOf(0L, 100_000L, 400_000L, 500_000L, 100_000L, 0L)) {
            assertEquals(track.sample(t), restored.sample(t))
        }
        assertTrue(track.sample(100_000).centerX < .4f)
    }

    @Test fun loss_holds_500ms_then_centers_in_300ms() {
        val track = track(listOf(frame(0, .2f), frame(250_000, .2f, .1f), frame(1_000_000, .2f, .1f)),
            listOf(observation(0), observation(250_000, 0), observation(1_000_000, 0)))
        assertEquals(.2f, track.sample(500_000).centerX, .0001f)
        assertEquals(SmartFramingStatus.HOLDING, track.sample(500_000).status)
        assertEquals(.35f, track.sample(650_000).centerX, .0001f)
        assertEquals(.5f, track.sample(800_000).centerX, .0001f)
        assertEquals(SmartFramingStatus.NO_PERSON, track.sample(800_000).status)
    }

    @Test fun recovery_blends_for_300ms() {
        val track = track(listOf(frame(0, .2f), frame(250_000, .2f, .1f), frame(1_000_000, .2f), frame(1_500_000, .2f)),
            listOf(observation(0), observation(250_000, 0), observation(1_000_000), observation(1_500_000)))
        assertEquals(.5f, track.sample(1_000_000).centerX, .0001f)
        assertEquals(.35f, track.sample(1_150_000).centerX, .0001f)
        assertEquals(.2f, track.sample(1_300_000).centerX, .0001f)
        assertEquals(SmartFramingStatus.TRACKING, track.sample(1_300_000).status)
    }

    @Test fun multiple_faces_use_center_not_last_person() {
        val track = track(listOf(frame(0, .2f), frame(250_000, .2f)), listOf(observation(0), observation(250_000, 2)))
        assertEquals(.5f, track.sample(250_000).centerX, 0f)
        assertEquals(SmartFramingStatus.AMBIGUOUS, track.sample(250_000).status)
    }

    @Test fun null_face_count_is_unknown_not_zero() {
        val track = track(listOf(frame(0, .2f)), listOf(observation(0, null)))
        assertEquals(.5f, track.sample(0).centerX, 0f)
        assertEquals(SmartFramingStatus.UNKNOWN, track.sample(0).status)
        val afterTracking = track(listOf(frame(0, .2f), frame(250_000, .2f)),
            listOf(observation(0), observation(250_000, null)))
        assertEquals(.5f, afterTracking.sample(250_000).centerX, 0f)
        assertEquals(SmartFramingStatus.UNKNOWN, afterTracking.sample(250_000).status)
    }

    @Test fun zero_faces_can_track_a_body_but_no_mask_is_no_person() {
        val body = track(listOf(frame(0, .2f)), listOf(observation(0, 0)))
        assertEquals(SmartFramingStatus.TRACKING, body.sample(0).status)
        assertEquals(.2f, body.sample(0).centerX, .0001f)
        val absent = track(emptyList(), listOf(observation(0, 0)))
        assertEquals(SmartFramingStatus.NO_PERSON, absent.sample(0).status)
        assertEquals(.5f, absent.sample(0).centerX, 0f)
    }

    @Test fun unreliable_mask_cannot_start_tracking() {
        val baseline = frame(0, .2f)
        for (rejected in listOf(baseline.copy(subjectQuality = .59f), baseline.copy(subjectOcclusion = .36f),
            baseline.copy(mask = baseline.mask!!.copy(confidence = .59f)),
            baseline.copy(mask = FrameAttachments.Plane(20, 10, FloatArray(200) { 1f }, .9f)),
            baseline.copy(mask = FrameAttachments.Plane(20, 10, FloatArray(200) { if (it == 40) 1f else 0f }, .9f)))) {
            val track = track(listOf(rejected))
            assertEquals(SmartFramingStatus.UNKNOWN, track.sample(0).status)
            assertEquals(.5f, track.sample(0).centerX, 0f)
        }
        val accepted = track(listOf(baseline.copy(subjectQuality = .6f, subjectOcclusion = .35f,
            mask = baseline.mask!!.copy(confidence = .6f))))
        assertEquals(SmartFramingStatus.TRACKING, accepted.sample(0).status)
    }

    @Test fun source_pts_delta_controls_ema_and_supported_movement_is_not_an_outlier() {
        val short = track(listOf(frame(0, .2f), frame(250_000, .4f)))
        val long = track(listOf(frame(0, .2f), frame(500_000, .4f)))
        assertEquals(.24914452f, short.sample(0).centerX, .00001f)
        assertEquals(.23064032f, long.sample(0).centerX, .00001f)
        val supported = track(listOf(frame(0, .2f), frame(250_000, .8f), frame(500_000, .8f)))
        assertTrue(supported.sample(250_000).centerX > .5f)
    }

    @Test fun center_combines_alpha_weighted_foreground_and_bounds() {
        val plane = FrameAttachments.Plane(10, 10, FloatArray(100) { i ->
            if (i / 10 in 3..6 && i % 10 in 1..4) { if (i % 10 == 1) .5f else 1f } else 0f
        }, .9f)
        val track = track(listOf(FrameAttachments(0, plane, subjectQuality = .9f)))
        assertEquals(.3107143f, track.sample(0).centerX, .00001f)
        assertEquals(.5f, track.sample(0).centerY, .00001f)
    }

    @Test fun single_outlier_is_rejected() {
        val track = track(listOf(frame(0, .2f), frame(250_000, .8f), frame(500_000, .2f)))
        assertEquals(.2f, track.sample(250_000).centerX, .0001f)
    }

    @Test fun neighbors_need_not_agree_with_each_other_to_reject_an_unconfirmed_jump() {
        val track = track(listOf(frame(0, .2f), frame(250_000, .8f), frame(500_000, .35f)))
        assertEquals(.2f, track.sample(250_000).centerX, .0001f)
    }

    @Test fun no_interpolation_through_omitted_source_window() {
        val track = track(listOf(frame(0, .2f), frame(1_000_000, .8f)), windows = listOf(
            VisualEventMap.UsableWindow(0, 500_000), VisualEventMap.UsableWindow(1_000_000, 2_000_000)))
        assertEquals(.2f, track.sample(499_999).centerX, .0001f)
        assertEquals(.5f, track.sample(750_000).centerX, 0f)
        assertEquals(SmartFramingStatus.UNKNOWN, track.sample(750_000).status)
        assertEquals(.8f, track.sample(1_000_000).centerX, .0001f)
    }

    @Test fun two_separated_large_mask_components_are_ambiguous() {
        val a = frame(0, .2f)
        val b = mask(.8f)
        val both = a.mask!!.copy(values = FloatArray(200) { maxOf(a.mask.values[it], b.values[it]) })
        val track = track(listOf(a.copy(mask = both)))
        assertEquals(SmartFramingStatus.AMBIGUOUS, track.sample(0).status)
        assertEquals(.5f, track.sample(0).centerX, 0f)
    }

    @Test fun restored_contract_copies_lists_and_rejects_unsorted_pts() {
        val points = mutableListOf(SmartFramingTrack.Point(0, .2f, .5f, SmartFramingStatus.TRACKING))
        val windows = mutableListOf(VisualEventMap.UsableWindow(0, 1_000_000))
        val track = SmartFramingTrack.fromPoints(points, windows)
        points.clear(); windows.clear()
        assertEquals(.2f, track.sample(0).centerX, 0f)
        assertThrows(UnsupportedOperationException::class.java) { (track.points as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (track.sourceWindows as MutableList).clear() }
        assertThrows(IllegalArgumentException::class.java) { SmartFramingTrack.fromPoints(
            listOf(SmartFramingTrack.Point(10, .2f, .5f, SmartFramingStatus.TRACKING),
                SmartFramingTrack.Point(0, .2f, .5f, SmartFramingStatus.TRACKING)), track.sourceWindows) }
    }
}
