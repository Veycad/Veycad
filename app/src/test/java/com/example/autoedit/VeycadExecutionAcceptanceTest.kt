package com.veycad.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VeycadExecutionAcceptanceTest {
    private val decodedAudio = DecodedAudioQuality.Report(
        44_100, 1, 44_100, 1_000_000L, .1f, .1, 0, 0, 0, true, emptyList()
    )

    private fun decoded(issues: List<String> = emptyList()) = RenderedMp4Acceptance.Report(
        accepted = issues.isEmpty(),
        metrics = RenderedMp4Acceptance.Metrics(1f, 0f, 1f, 0L, 0f, 0f, 0f),
        reference = RenderedMp4Acceptance.ReferenceMontageCard(),
        issues = issues,
        decodedAudio = decodedAudio
    )

    private fun execution(issues: List<String> = emptyList()) = VeykadRenderInspector.Summary(
        plannedFrames = 30, shaderFrames = 30, authoredTransitionEvents = 0,
        renderedTransitionEvents = 0, renderedTransitionFrames = 0, dualDecoderFrames = 0,
        speedRampFrames = 0, virtualCameraFrames = 0, blackoutFrames = 0,
        transitionFramesByType = emptyMap(), maxBlur = 0f, maxBlackout = 0f,
        maxOcclusion = 0f, maxForegroundReentry = 0f,
        missingTransitionClipIds = emptyList(), issues = issues
    )

    @Test fun rejected_execution_blocks_otherwise_passed_decoded_measurements() {
        val original = decoded()
        val issue = "temporal_layer_sources_too_close_to_read"
        val merged = VeycadAutomaticEditor.withExecutionEvidence(original, execution(listOf(issue)))
        assertTrue(original.accepted)
        assertTrue(original.issues.isEmpty())
        assertFalse(merged.accepted)
        assertEquals(listOf("render-execution:$issue"), merged.issues)
        assertSame(original.metrics, merged.metrics)
        assertSame(original.reference, merged.reference)
        assertSame(decodedAudio, merged.decodedAudio)
    }

    @Test fun accepted_execution_retains_passed_decoded_report() {
        val original = decoded()
        val merged = VeycadAutomaticEditor.withExecutionEvidence(original, execution())
        assertTrue(merged.accepted)
        assertEquals(original, merged)
    }

    @Test fun accepted_execution_never_resolves_audio_visual_or_container_failures() {
        val issues = listOf("audio-unclamped-headroom-unavailable", "face-loss", "container:video_pts_not_monotonic")
        val original = decoded(issues)
        val merged = VeycadAutomaticEditor.withExecutionEvidence(original, execution())
        assertFalse(merged.accepted)
        assertEquals(issues, merged.issues)
        assertSame(original.decodedAudio, merged.decodedAudio)
    }

    @Test fun missing_execution_evidence_is_failure_not_an_empty_success() {
        val merged = VeycadAutomaticEditor.withExecutionEvidence(decoded(), null)
        assertFalse(merged.accepted)
        assertEquals(listOf("render-execution-evidence-missing"), merged.issues)
    }

    @Test fun rejected_execution_keeps_every_original_issue_without_replacing_decoded_failure() {
        val merged = VeycadAutomaticEditor.withExecutionEvidence(
            decoded(listOf("face-loss")), execution(listOf("shader_frame_count_mismatch", "missing_layer"))
        )
        assertFalse(merged.accepted)
        assertEquals(listOf("face-loss", "render-execution:shader_frame_count_mismatch",
            "render-execution:missing_layer"), merged.issues)
    }

    @Test fun accepted_execution_raw_contract_has_explicit_measured_pass() {
        assertEquals(linkedMapOf(
            "render_execution_method" to "shader-frame-inspector-v1",
            "render_execution_evidence" to "true",
            "render_execution_accepted" to "true",
            "render_execution_issues" to ""
        ), VeycadAutomaticEditor.executionArtifactFields(execution()))
    }

    @Test fun rejected_execution_raw_contract_preserves_actual_issue_tokens() {
        val fields = VeycadAutomaticEditor.executionArtifactFields(execution(listOf(
            "temporal_layer_sources_too_close_to_read", "missing_layer"
        )))
        assertEquals("true", fields["render_execution_evidence"])
        assertEquals("false", fields["render_execution_accepted"])
        assertEquals("temporal_layer_sources_too_close_to_read,missing_layer", fields["render_execution_issues"])
    }

    @Test fun absent_execution_raw_contract_explicitly_denies_measured_success() {
        val fields = VeycadAutomaticEditor.executionArtifactFields(null)
        assertEquals("shader-frame-inspector-v1", fields["render_execution_method"])
        assertEquals("false", fields["render_execution_evidence"])
        assertEquals("false", fields["render_execution_accepted"])
        assertEquals("execution-evidence-missing", fields["render_execution_issues"])
    }

    @Test fun previous_inspector_report_cannot_be_reused_as_new_execution_proof() {
        val previous = File("inspector/previous.json")
        assertFalse(VeycadAutomaticEditor.hasFreshExecutionReport(previous, previous))
        assertFalse(VeycadAutomaticEditor.hasFreshExecutionReport(previous, File("inspector/./previous.json")))
        assertFalse(VeycadAutomaticEditor.hasFreshExecutionReport(previous, null))
        assertTrue(VeycadAutomaticEditor.hasFreshExecutionReport(previous, File("inspector/new.json")))
        assertTrue(VeycadAutomaticEditor.hasFreshExecutionReport(null, File("inspector/new.json")))
    }
}
