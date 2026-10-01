"""Shared synthetic artifacts; never decoded media or human acceptance evidence.

Contract checklists are literal expectations independent from production lists.
Fixtures return fresh mutable objects on every call.
"""
from pathlib import Path

EXPECTED_PRODUCTS = ("SIGMA", "HEARTBEAT", "FEAR_STROBE", "DUALITY_LOOP")
EXPECTED_REVIEW_AREAS = ("shot_choice", "composition_and_face", "transitions",
                         "rhythm", "colour", "music", "finale")
EXPECTED_STYLE_REVIEW_AREAS = {
    "SIGMA": ("foreground_entry_and_mask", "layered_story_arc", "silhouette_finale"),
    "HEARTBEAT": ("recognisable_reprise", "face_readable_under_echo", "pulse_and_black_tail"),
    "FEAR_STROBE": ("continuous_opener_and_title", "varied_pose_cascade", "authored_shutter_and_finale"),
    "DUALITY_LOOP": ("meaningful_two_source_roles", "organic_cuts_not_forced_ab", "live_source_finale"),
}

def review(output_sha256="a" * 64):
    return {
        "output_sha256": output_sha256, "recipe": "HEARTBEAT",
        "graph_generator": "HEARTBEAT_V1:production",
        "reviewer": "human-1", "reviewed_at": "2026-09-25T12:00:00+05:00",
        "playback_1x": True, "playback_half": True,
        "checks": {name: True for name in (
            "shot_choice", "composition_and_face", "transitions", "rhythm",
            "colour", "music", "finale")},
        "blockers": [],
        "style_checks": {name: True for name in EXPECTED_STYLE_REVIEW_AREAS["HEARTBEAT"]},
    }



def heartbeat_result():
    return {
        "source_analysis_profile_method": "explicit-capabilities-v1", "source_analysis_count": "1",
        "source_analysis_0_profile": "editorial-semantics-v1",
        "source_analysis_0_correspondence_state": "NOT_REQUESTED",
        "source_analysis_0_observations": "10",
        "source_analysis_0_correspondence_assessments_completed": "0",
        "source_analysis_0_subject_measured_samples": "0", "source_analysis_0_camera_measured_samples": "0",
        "status": "ok", "recipe": "HEARTBEAT", "acceptance": "true",
        "render_execution_method": "shader-frame-inspector-v1",
        "render_execution_evidence": "true", "render_execution_accepted": "true",
        "render_execution_issues": "",
        "temporal_decoded_evidence_method": "decoded-texture-pts-v1",
        "temporal_layer_policy": "temporal-distinct-pts-v1",
        "static_source": "false",
        "output_sha256": "a" * 64,
        "acceptance_issues": "", "duration_ms": "21166",
        "duration_error_us": "0", "av_drift_us": "0",
        "video_first_pts_us": "0", "audio_first_pts_us": "0",
        "container_issues": "", "encoded_rotation": "0",
        "encoded_video_mime": "video/avc", "encoded_audio_mime": "audio/mp4a-latm",
        "encoded_width": "720", "encoded_height": "1280",
        "expected_width": "720", "expected_height": "1280",
        "encoded_video_samples": "1270", "encoded_audio_samples": "994",
        "audio_decoded_evidence": "true", "audio_decoded_issues": "",
        "audio_unclamped_float": "true", "audio_sample_rate": "48000",
        "audio_channels": "2", "audio_pcm_samples": "2031936",
        "audio_decoded_duration_us": "21166000", "audio_sample_peak": "0.9",
        "audio_rms": "0.1", "audio_non_finite_samples": "0",
        "audio_over_full_scale_samples": "0", "audio_longest_full_scale_run": "0",
        "max_artifact": "0.01", "max_colour_jump": "0.02",
        "max_black_block": "0", "face_expected_samples": "10",
        "face_evidence_method": "decoded-face-exact-pts-v1",
        "face_measured_samples": "10", "face_unknown_samples": "0",
        "face_unknown_times_us": "", "face_empty_samples": "0",
        "face_loss_samples": "0", "face_loss_times_us": "",
        "face_loss_rate": "0", "frames": "1270",
        "graph_generator": "HEARTBEAT_V1:production",
        "source_pool_generator": "heartbeat-source-pool",
        "heartbeat_pulses_measured": "26", "heartbeat_pulses_matched": "26",
        "heartbeat_pulses_missing": "", "heartbeat_pulses_wrong_luma": "",
        "heartbeat_tail_method": "decoded-luma-contiguous-60fps-v1",
        "heartbeat_tail_expected_frames": "82", "heartbeat_tail_measured_frames": "82",
        "heartbeat_tail_black_frames": "82", "heartbeat_tail_boundary_measured_frames": "2",
        "heartbeat_tail_matched": "true", "heartbeat_tail_first_black_us": "19800000",
        "heartbeat_tail_start_offset_us": "0", "heartbeat_tail_missing_us": "",
        "heartbeat_tail_bright_us": "", "heartbeat_tail_unexpected_us": "",
        "heartbeat_tail_pre_boundary_luma": "1.0",
        "clips": "27", "foreground_reentries": "0", "whips": "0",
        "temporal_layer_frames": "200", "beat_hit_rate": "0.95",
        # Native first-phrase audit excludes the intentional authored reprise/tail.
        "repeated_source_ratio": "0.0",
    }



def unknown_headroom_result():
    result = heartbeat_result()
    result.update(output_sha256="a" * 64, acceptance="false",
                  acceptance_issues="audio-unclamped-headroom-unavailable",
                  audio_unclamped_float="false",
                  audio_decoded_issues="audio-unclamped-headroom-unavailable")
    return result



def sigma_result():
    result = heartbeat_result()
    result.update(recipe="SIGMA", duration_ms="18034", frames="542",
                  encoded_video_samples="542", audio_pcm_samples="1731264",
                  audio_decoded_duration_us="18034000",
                  graph_generator="veycad-reference-SUBJECT_REENTRY_PULSE_V2:test",
                  temporal_layer_policy="reference-spatial-v1",
                  beat_hit_rate="0.95", repeated_source_ratio="0.01",
                  reference_grammar_fit="0.8", layered_frames="100",
                  decoded_glitch_peak="0.01", author_accent_hit_rate="1",
                  max_author_accent_offset_us="0", reference_timeline_recall="1",
                  foreground_reentries="1", foreground_mask_samples="10",
                  foreground_mask_measured_samples="10", foreground_mask_source_empty_samples="0",
                  foreground_mask_output_empty_samples="0", foreground_mask_inference_failed_samples="0",
                  max_edge_leak="0.01", min_mask_temporal_iou="0.9")
    return result



def independent_audio_report():
    return {"schema_version": 1, "method": "ffmpeg-unclamped-f32-wav-v1",
            "decoder_version": "ffmpeg version synthetic-unit-test",
            "output_sha256": "a" * 64, "recipe": "HEARTBEAT", "passed": True, "issues": [],
            "sample_rate": 48000, "channels": 2, "pcm_samples": 2031936,
            "pcm_frames": 1015968, "decoded_duration_us": 21166000,
            "sample_peak": .9, "rms": .1, "non_finite_samples": 0,
            "over_full_scale_samples": 0, "longest_full_scale_run": 0}



def synthetic_inspector(output: Path, result: dict) -> dict:
    """Make fake records for aggregation tests, never real montage evidence."""
    count = int(result["frames"])
    last = int(result["duration_ms"]) * 1_000 - int(result["duration_error_us"])
    frames = []
    for index in range(count):
        output_us = last * index // (count - 1) if count > 1 else 0
        source_us = index // 2 * 1_000  # Repeated PTS deliberately remain legal.
        frames.append({"output_us": output_us, "source_us": source_us,
                       "clip": int(result["clips"]) * index // count,
                       "decoded_source_us": source_us, "decoded_secondary_source_us": None,
                       "mask_source_us": None, "secondary_source_us": -1,
                       "source_sampling_error_us": 0, "layer_opacity": 0.0,
                       "layer_kind": "FLASH", "dual_decoder": False})
    return {"format": "veykad-render-inspector-v1", "local_only": True,
            "temporal_decoded_evidence_method": result["temporal_decoded_evidence_method"],
            "mp4_name": output.name, "mp4_bytes": output.stat().st_size,
            "graph": {"generator": result["graph_generator"],
                      "temporal_layer_policy": result["temporal_layer_policy"],
                      "duration_ms": int(result["duration_ms"]), "clips": int(result["clips"])},
            "summary": {"accepted": True, "issues": [], "planned_frames": count, "shader_frames": count},
            "container": {"accepted": True, "issues": [],
                          "width": int(result["encoded_width"]), "height": int(result["encoded_height"]),
                          "rotation": int(result["encoded_rotation"]),
                          "video_samples": int(result["encoded_video_samples"]),
                          "audio_samples": int(result["encoded_audio_samples"]),
                          "video_mime": result["encoded_video_mime"], "audio_mime": result["encoded_audio_mime"],
                          "video_first_pts_us": int(result["video_first_pts_us"]),
                          "audio_first_pts_us": int(result["audio_first_pts_us"]),
                          "video_last_pts_us": last, "audio_last_pts_us": last + int(result["av_drift_us"]),
                          "av_delta_us": int(result["av_drift_us"])}, "frames": frames}


def fear_result():
    result = heartbeat_result()
    result.update(recipe="FEAR_STROBE", duration_ms="18300", frames="549",
                  temporal_layer_policy="no-temporal-layer-v1",
                  source_analysis_0_profile="editorial-correspondence-v1",
                  source_analysis_0_correspondence_state="ASSESSED",
                  source_analysis_0_observations="20",
                  source_analysis_0_correspondence_assessments_completed="20",
                  source_analysis_0_subject_measured_samples="2",
                  source_analysis_0_camera_measured_samples="8",
                  encoded_video_samples="549", audio_pcm_samples="1756800",
                  audio_decoded_duration_us="18300000", clips="30",
                  graph_generator="FEAR_STROBE_V1:exact", source_pool_generator="fear-source-pool",
                  fear_shutter_measured="30", fear_shutter_matched="30",
                  fear_finale_matched="true", fear_pulses_missing="",
                  fear_pulses_wrong_luma="", fear_unexpected_black_us="",
                  fear_opener_motion_samples="5", fear_opener_motion_run="3",
                  fear_opener_motion_span_us="500000",
                  fear_opener_motion_measured_samples="8", fear_opener_motion_unknown_samples="7",
                  fear_opener_subject_measured_samples="2", fear_opener_camera_measured_samples="8",
                  fear_opener_evidence_method="camera-or-subject-independent-v1",
                  fear_opener_motion_conclusive_samples="5", fear_opener_motion_inconclusive_samples="10",
                  fear_first_cascade_sampled_clips="13", fear_second_cascade_sampled_clips="14",
                  fear_first_cascade_witnesses="fear-cascade-2,fear-cascade-3,fear-cascade-4",
                  fear_second_cascade_witnesses="fear-cascade-15,fear-cascade-16,fear-cascade-17")
    return result


def fixture_baseline(root):
    """Synthetic bytes exercise integrity only, never actual product readiness."""
    from quality_holdout_seal import REQUIRED_FILES
    for name in (*REQUIRED_FILES, "app/build.gradle.kts", "gradlew", "gradlew.bat",
                 "tools/quality_holdout.py", "build/test.apk"):
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("synthetic baseline: " + name, encoding="utf-8")

