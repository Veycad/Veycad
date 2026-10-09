"""Fail-closed, per-product assessment of a decoded GoldenRenderActivity report.

This is an offline release check, not an in-app quality promise. A missing or
non-finite metric, wrong recipe, or absent human review cannot be a pass.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import subprocess
from pathlib import Path

from quality_review_identity import valid_review_identity
from quality_inspector_report import temporal_contract_issues


FRAME_US = {"SIGMA": 33_334, "HEARTBEAT": 16_667,
            "FEAR_STROBE": 33_334, "DUALITY_LOOP": 33_334}
EXPECTED_DURATION_MS = {"SIGMA": 18_034, "HEARTBEAT": 21_166,
                        "FEAR_STROBE": 18_300, "DUALITY_LOOP": 18_300}
EXPECTED_FRAMES = {"HEARTBEAT": 1270, "FEAR_STROBE": 549,
                   "DUALITY_LOOP": 549}
WINDOW_RE = re.compile(r"([01]):(\d+)-(\d+)@(\d+)")
REVIEW_AREAS = ("shot_choice", "composition_and_face", "transitions",
                "rhythm", "colour", "music", "finale")
STYLE_REVIEW_AREAS = {
    "SIGMA": ("foreground_entry_and_mask", "layered_story_arc", "silhouette_finale"),
    "HEARTBEAT": ("recognisable_reprise", "face_readable_under_echo", "pulse_and_black_tail"),
    "FEAR_STROBE": ("continuous_opener_and_title", "varied_pose_cascade", "authored_shutter_and_finale"),
    "DUALITY_LOOP": ("meaningful_two_source_roles", "organic_cuts_not_forced_ab", "live_source_finale"),
}


def parse_result(raw: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in raw.splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            if key in result:
                raise ValueError(f"duplicate report field: {key}")
            result[key] = value
    return result


def source_profile_issues(result: dict[str, str], recipe: str) -> list[str]:
    """Explicit requested capabilities, not proof that any physical measurement is correct."""
    issues = []
    if result.get("source_analysis_profile_method") != "explicit-capabilities-v1":
        issues.append("source_analysis_profile_method: explicit capability evidence missing")
    expected = 2 if recipe == "DUALITY_LOOP" else 1
    if result.get("source_analysis_count") != str(expected):
        issues.append("source_analysis_count: wrong product source count")

    def integer(key: str) -> int | None:
        value = result.get(key)
        if not isinstance(value, str) or not re.fullmatch(r"0|[1-9][0-9]*", value):
            issues.append(f"{key}: missing or invalid integer capability evidence")
            return None
        return int(value)

    for index in range(expected):
        prefix = f"source_analysis_{index}_"
        profile = result.get(prefix + "profile")
        if profile not in ("editorial-semantics-v1", "editorial-correspondence-v1"):
            issues.append(prefix + "profile: unknown/missing source capability")
        if recipe == "FEAR_STROBE" and profile != "editorial-correspondence-v1":
            issues.append(prefix + "profile: FEAR requires correspondence capability")
        observations = integer(prefix + "observations")
        assessments = integer(prefix + "correspondence_assessments_completed")
        subject = integer(prefix + "subject_measured_samples")
        camera = integer(prefix + "camera_measured_samples")
        if observations is not None and observations < 1:
            issues.append(prefix + "observations: source evidence empty")
        if all(value is not None for value in (observations, subject, camera)):
            if not 0 <= subject <= camera <= observations:
                issues.append(prefix + "camera_measured_samples: inconsistent supported measurement counts")
        if profile == "editorial-semantics-v1":
            if result.get(prefix + "correspondence_state") != "NOT_REQUESTED":
                issues.append(prefix + "correspondence_state: skipped work is not an assessment")
            if assessments != 0 or subject != 0 or camera != 0:
                issues.append(prefix + "correspondence_assessments_completed: editorial must carry no correspondence")
        elif profile == "editorial-correspondence-v1":
            if result.get(prefix + "correspondence_state") != "ASSESSED":
                issues.append(prefix + "correspondence_state: requested assessment missing")
            if observations is None or assessments != observations:
                issues.append(prefix + "correspondence_assessments_completed: every observation must be assessed")
    for key in result:
        match = re.fullmatch(r"source_analysis_([0-9]+)_.*", key)
        if match and int(match.group(1)) >= expected:
            issues.append(key + ": unexpected source capability record")
    return issues


def assess(result: dict[str, str], recipe: str, review: dict | None = None,
           independent_audio: dict | None = None) -> dict:
    if recipe not in FRAME_US:
        raise ValueError(f"unknown recipe: {recipe}")
    issues: list[str] = source_profile_issues(result, recipe)
    independent_audio_verified = False
    if independent_audio is not None:
        from quality_audio_report import validate_report
        audio_issues = validate_report(independent_audio, recipe, result.get("output_sha256"))
        issues.extend(audio_issues)
        independent_audio_verified = not audio_issues
    unknown = "audio-unclamped-headroom-unavailable"
    # Only this exact unknown may be resolved. Do not erase even one confirmed defect.
    resolved_headroom = independent_audio_verified and all((
        result.get("acceptance") == "false",
        result.get("acceptance_issues") == unknown,
        result.get("audio_decoded_evidence") == "true",
        result.get("audio_unclamped_float") == "false",
        result.get("audio_decoded_issues") == unknown,
    ))

    def eq(key: str, expected: str) -> None:
        if result.get(key) != expected:
            issues.append(f"{key}: expected {expected}, got {result.get(key, '<missing>')}")

    def number(key: str) -> float | None:
        value = result.get(key)
        try:
            parsed = float(value) if value is not None else math.nan
        except ValueError:
            parsed = math.nan
        if not math.isfinite(parsed):
            issues.append(f"{key}: missing or non-finite")
            return None
        return parsed

    def at_most(key: str, bound: float) -> None:
        value = number(key)
        if value is not None and value > bound:
            issues.append(f"{key}: {value} > {bound}")

    def at_least(key: str, bound: float) -> None:
        value = number(key)
        if value is not None and value < bound:
            issues.append(f"{key}: {value} < {bound}")

    eq("status", "ok")
    eq("recipe", recipe)
    # The decoded MP4 checks do not replace actual shader-execution evidence.
    # Older immutable reports without this contract remain historical, not passes.
    eq("render_execution_method", "shader-frame-inspector-v1")
    eq("render_execution_evidence", "true")
    eq("render_execution_accepted", "true")
    eq("render_execution_issues", "")
    # This binds native intent, not an offline reconstruction of its texture samples.
    # Release aggregation also validates the retained per-frame inspector records.
    issues.extend(temporal_contract_issues(result, recipe))
    if not resolved_headroom:
        eq("acceptance", "true")
    eq("duration_ms", str(EXPECTED_DURATION_MS[recipe]))
    if result.get("acceptance_issues") is None:
        issues.append("acceptance_issues: missing")
    elif result["acceptance_issues"] and not resolved_headroom:
        issues.append(f"decoded acceptance issues: {result['acceptance_issues']}")
    at_most("duration_error_us", FRAME_US[recipe])
    at_most("av_drift_us", FRAME_US[recipe])
    eq("video_first_pts_us", "0")
    eq("audio_first_pts_us", "0")
    eq("container_issues", "")
    eq("encoded_rotation", "0")
    eq("encoded_video_mime", "video/avc")
    eq("encoded_audio_mime", "audio/mp4a-latm")
    eq("audio_decoded_evidence", "true")
    if not resolved_headroom:
        eq("audio_decoded_issues", "")
        eq("audio_unclamped_float", "true")
    at_least("audio_sample_rate", 1)
    at_least("audio_channels", 1)
    at_most("audio_channels", 8)
    at_least("audio_pcm_samples", 1)
    at_least("audio_rms", 0)
    at_least("audio_sample_peak", 0)
    at_most("audio_sample_peak", 1)
    eq("audio_non_finite_samples", "0")
    eq("audio_over_full_scale_samples", "0")
    at_least("audio_longest_full_scale_run", 0)
    at_most("audio_longest_full_scale_run", 2)
    audio_duration = number("audio_decoded_duration_us")
    sample_rate = number("audio_sample_rate")
    channels = number("audio_channels")
    pcm_samples = number("audio_pcm_samples")
    for key, value in (("audio_sample_rate", sample_rate), ("audio_channels", channels),
                       ("audio_pcm_samples", pcm_samples), ("audio_decoded_duration_us", audio_duration)):
        if value is not None and (value < 0 or not value.is_integer()):
            issues.append(f"{key}: invalid integer evidence")
    if all(value is not None and value > 0 for value in (sample_rate, channels, pcm_samples)):
        if pcm_samples % channels != 0:
            issues.append("audio_pcm_samples: incomplete interleaved frame")
        measured_duration = pcm_samples / channels * 1_000_000 / sample_rate
        if audio_duration is not None and abs(measured_duration - audio_duration) >= 1:
            issues.append("audio_decoded_duration_us: inconsistent with PCM sample clock")
    if audio_duration is not None and sample_rate is not None and sample_rate > 0:
        # One video frame plus one AAC access unit accommodates codec padding, not missing audio.
        tolerance = FRAME_US[recipe] + 1_024_000_000 / sample_rate
        if abs(audio_duration - EXPECTED_DURATION_MS[recipe] * 1000) > tolerance:
            issues.append("audio_decoded_duration_us: incomplete or excessive decoded audio")
    sample_peak = number("audio_sample_peak")
    if sample_peak is not None and sample_peak == 0:
        issues.append("decoded audio is silent")
    rms = number("audio_rms")
    if rms is not None and sample_peak is not None and rms > sample_peak:
        issues.append("audio_rms: exceeds sample peak")
    at_least("encoded_video_samples", 1)
    at_least("encoded_audio_samples", 1)
    for dimension in ("width", "height"):
        actual = number(f"encoded_{dimension}")
        expected = number(f"expected_{dimension}")
        if actual is not None and expected is not None:
            if actual <= 0 or expected <= 0 or actual != expected:
                issues.append(f"encoded_{dimension}: does not match positive export dimension")
    encoded_frames = number("encoded_video_samples")
    planned_frames = number("frames")
    if encoded_frames is not None and planned_frames is not None and encoded_frames != planned_frames:
        issues.append("encoded_video_samples: differs from rendered frame count")
    at_most("max_artifact", .45 if recipe == "DUALITY_LOOP" else .18)
    at_most("max_colour_jump", .25)
    if recipe in ("SIGMA", "HEARTBEAT"):
        at_most("max_black_block", .08)
    elif recipe == "DUALITY_LOOP":
        # Low-key plates can score high without an inserted black frame.
        # The terminal-frame check remains mandatory in human review.
        at_most("max_black_block", .45)
    at_least("face_expected_samples", 1)
    eq("face_evidence_method", "decoded-face-exact-pts-v1")
    eq("face_unknown_samples", "0")
    eq("face_unknown_times_us", "")
    face_expected = number("face_expected_samples")
    face_measured = number("face_measured_samples")
    face_empty = number("face_empty_samples")
    face_lost = number("face_loss_samples")
    if face_expected is not None and (not face_expected.is_integer() or face_expected < 1):
        issues.append("face_expected_samples: invalid positive sample count")
    if face_measured is not None and (not face_measured.is_integer() or face_measured < 1):
        issues.append("face_measured_samples: invalid positive sample count")
    if face_expected is not None and face_measured is not None and face_measured != face_expected:
        issues.append("face_measured_samples: not all expected faces have fresh decoded evidence")
    for key, count in (("face_empty_samples", face_empty), ("face_loss_samples", face_lost)):
        if count is not None and (not count.is_integer() or count < 0 or
                                  (face_expected is not None and count > face_expected)):
            issues.append(f"{key}: invalid face sample count")
    if face_empty is not None and face_lost is not None and face_empty > face_lost:
        issues.append("face_empty_samples: exceeds measured face loss count")
    face_loss_rate = number("face_loss_rate")
    if face_loss_rate is not None and face_expected is not None and face_expected > 0 and face_lost is not None:
        if abs(face_loss_rate - face_lost / face_expected) > 1e-6:
            issues.append("face_loss_rate: inconsistent with expected and lost sample counts")
    face_loss_times = result.get("face_loss_times_us")
    if face_loss_times is None:
        issues.append("face_loss_times_us: missing")
    else:
        tokens = face_loss_times.split(",") if face_loss_times else []
        if any(re.fullmatch(r"\d+", token) is None for token in tokens):
            issues.append("face_loss_times_us: invalid decoded presentation timestamp")
        else:
            timestamps = [int(token) for token in tokens]
            if any(left >= right for left, right in zip(timestamps, timestamps[1:])) or \
                    any(time >= EXPECTED_DURATION_MS[recipe] * 1000 for time in timestamps):
                issues.append("face_loss_times_us: not an ordered decoded sample clock")
            if face_lost is not None and len(timestamps) != face_lost:
                issues.append("face_loss_times_us: inconsistent with face loss count")
    at_most("face_loss_rate", .05 if recipe in ("SIGMA", "HEARTBEAT") else .18)

    if recipe in EXPECTED_FRAMES:
        expected = EXPECTED_FRAMES[recipe]
        value = number("frames")
        if value is not None and value != expected:
            issues.append(f"frames: {value} != {expected}")
    generator = result.get("graph_generator", "")
    prefix = {"SIGMA": "veycad-reference-SUBJECT_REENTRY_PULSE", "HEARTBEAT": "HEARTBEAT",
              "FEAR_STROBE": "FEAR", "DUALITY_LOOP": "DUALITY"}[recipe]
    if not generator.startswith(prefix):
        issues.append(f"graph_generator: expected {prefix} product, got {generator or '<missing>'}")

    if recipe == "SIGMA":
        at_least("beat_hit_rate", .95)
        at_most("repeated_source_ratio", .02)
        at_least("reference_grammar_fit", .55)
        at_least("layered_frames", 1)
        at_least("decoded_glitch_peak", .007)
        at_least("author_accent_hit_rate", 1)
        at_most("max_author_accent_offset_us", 33_334)
        at_least("reference_timeline_recall", .95)
        at_least("foreground_reentries", 1)
        at_least("foreground_mask_samples", 1)
        at_least("foreground_mask_measured_samples", 1)
        eq("foreground_mask_source_empty_samples", "0")
        eq("foreground_mask_output_empty_samples", "0")
        eq("foreground_mask_inference_failed_samples", "0")
        at_most("max_edge_leak", .02)
        at_least("min_mask_temporal_iou", .88)
    elif recipe == "HEARTBEAT":
        at_least("beat_hit_rate", .90)
        at_most("beat_hit_rate", 1)
        # Native repeatedSourceRatio already excludes the authored reprise/tail.
        # Audit its first-phrase accidental overlap, not a Sigma-only penalty.
        at_least("repeated_source_ratio", 0)
        at_most("repeated_source_ratio", .02)
        eq("source_pool_generator", "heartbeat-source-pool")
        eq("heartbeat_pulses_measured", "26")
        eq("heartbeat_pulses_matched", "26")
        eq("heartbeat_pulses_missing", "")
        eq("heartbeat_pulses_wrong_luma", "")
        eq("heartbeat_tail_method", "decoded-luma-contiguous-60fps-v1")
        eq("heartbeat_tail_expected_frames", "82")
        eq("heartbeat_tail_measured_frames", "82")
        eq("heartbeat_tail_boundary_measured_frames", "2")
        eq("heartbeat_tail_matched", "true")
        eq("heartbeat_tail_missing_us", "")
        eq("heartbeat_tail_bright_us", "")
        eq("heartbeat_tail_unexpected_us", "")
        at_least("heartbeat_tail_pre_boundary_luma", .95)
        at_most("heartbeat_tail_pre_boundary_luma", 1)
        tail_black = number("heartbeat_tail_black_frames")
        tail_offset = number("heartbeat_tail_start_offset_us")
        tail_start = number("heartbeat_tail_first_black_us")
        if tail_black is not None and tail_black not in (81, 82):
            issues.append("heartbeat_tail_black_frames: expected 81 or 82 decoded black frames")
        if tail_offset is not None and (not tail_offset.is_integer() or abs(tail_offset) > 16_668):
            issues.append("heartbeat_tail_start_offset_us: outside one 60fps frame plus codec rounding")
        if tail_offset is not None and not any(abs(tail_offset - slot) <= 1 for slot in (-16_667, 0, 16_667)):
            issues.append("heartbeat_tail_start_offset_us: not a measured 60fps clock slot")
        if tail_start is not None and (not tail_start.is_integer() or tail_start < 0):
            issues.append("heartbeat_tail_first_black_us: invalid decoded presentation timestamp")
        if tail_start is not None and tail_offset is not None and tail_start - 19_800_000 != tail_offset:
            issues.append("heartbeat_tail_first_black_us: inconsistent with measured start offset")
        if tail_offset is not None and tail_black is not None and tail_black != (81 if tail_offset > 1 else 82):
            issues.append("heartbeat_tail_black_frames: inconsistent with measured start offset")
        eq("clips", "27")
        eq("foreground_reentries", "0")
        eq("whips", "0")
        at_least("temporal_layer_frames", 1)
    elif recipe == "FEAR_STROBE":
        at_least("beat_hit_rate", .85)
        at_most("beat_hit_rate", 1)
        # The authored white finale is already excluded by the native ratio.
        at_least("repeated_source_ratio", 0)
        at_most("repeated_source_ratio", .02)
        for phrase, lower, upper in (("first", 2, 14), ("second", 15, 28)):
            at_least(f"fear_{phrase}_cascade_sampled_clips", 3)
            witnesses = result.get(f"fear_{phrase}_cascade_witnesses", "").split(",")
            indices = [re.fullmatch(r"fear-cascade-(\d+)", item) for item in witnesses]
            if len(witnesses) != 3 or len(set(witnesses)) != 3 or \
                    any(match is None or not lower <= int(match.group(1)) <= upper for match in indices):
                issues.append(f"fear_{phrase}_cascade_witnesses: three distinct phrase-bound witnesses required")
        at_least("fear_opener_motion_samples", 3)
        at_least("fear_opener_motion_run", 3)
        at_least("fear_opener_motion_span_us", 500_000)
        eq("fear_opener_evidence_method", "camera-or-subject-independent-v1")
        motion_counts = {key: number(key) for key in (
            "fear_opener_motion_measured_samples", "fear_opener_motion_unknown_samples",
            "fear_opener_subject_measured_samples", "fear_opener_camera_measured_samples",
            "fear_opener_motion_conclusive_samples", "fear_opener_motion_inconclusive_samples",
            "fear_opener_motion_samples", "fear_opener_motion_run")}
        for key, value in motion_counts.items():
            if value is not None and (not value.is_integer() or value < 0):
                issues.append(f"{key}: non-negative integer evidence count required")
        if all(value is not None and value.is_integer() and value >= 0 for value in motion_counts.values()):
            measured = motion_counts["fear_opener_motion_measured_samples"]
            subject = motion_counts["fear_opener_subject_measured_samples"]
            camera = motion_counts["fear_opener_camera_measured_samples"]
            moving = motion_counts["fear_opener_motion_samples"]
            longest = motion_counts["fear_opener_motion_run"]
            conclusive = motion_counts["fear_opener_motion_conclusive_samples"]
            inconclusive = motion_counts["fear_opener_motion_inconclusive_samples"]
            opener_motion_unknown_count = motion_counts["fear_opener_motion_unknown_samples"]
            if not max(subject, camera) <= measured <= subject + camera:
                issues.append("fear_opener_motion_measured_samples: inconsistent with "
                              "fear_opener_subject_measured_samples/fear_opener_camera_measured_samples")
            # Body displacement is relative to a validated camera, so no supported body
            # observation may exist without camera support under this method.
            if camera != measured or subject > camera:
                issues.append("fear_opener_camera_measured_samples: must equal "
                              "fear_opener_motion_measured_samples and cover fear_opener_subject_measured_samples")
            if moving > measured:
                issues.append("fear_opener_motion_samples: more moving samples than measured evidence")
            if longest > moving:
                issues.append("fear_opener_motion_run: longer than the measured moving sample count")
            if not max(subject, moving) <= conclusive <= measured:
                issues.append("fear_opener_motion_conclusive_samples: inconsistent with known subject/moving/measurement counts")
            if conclusive + inconclusive != measured + opener_motion_unknown_count:
                issues.append("fear_opener_motion_inconclusive_samples: inconsistent with total observation count")
        eq("source_pool_generator", "fear-source-pool")
        eq("fear_shutter_measured", "30")
        eq("fear_shutter_matched", "30")
        eq("fear_finale_matched", "true")
        eq("fear_pulses_missing", "")
        eq("fear_pulses_wrong_luma", "")
        eq("fear_unexpected_black_us", "")
        eq("foreground_reentries", "0")
        eq("whips", "0")
        if not (generator.endswith(":exact") or generator.endswith(":adaptive")):
            issues.append("FEAR coverage mode missing from graph_generator")
    else:
        eq("source_count", "2")
        eq("source_indices", "0,1")
        eq("clips", "25")
        at_least("beat_hit_rate", .60)
        at_most("repeated_source_ratio", .20)
        windows = result.get("source_windows")
        if not windows:
            issues.append("source_windows: missing")
        else:
            parsed = [WINDOW_RE.fullmatch(part) for part in windows.split(",")]
            if len(parsed) != 25 or any(match is None for match in parsed):
                issues.append("source_windows: malformed or not 25 windows")
                parsed = []
            indices = [match.group(1) for match in parsed]
            if parsed and len({(match.group(1), match.group(2), match.group(3))
                               for match in parsed}) != len(parsed):
                issues.append("source_windows: exact duplicate range")
            if any(indices.count(index) < 8 for index in ("0", "1")):
                issues.append("source_windows: fewer than eight clips from a source")
            if any(indices[i] == indices[i + 1] == indices[i + 2] == indices[i + 3]
                   for i in range(max(0, len(indices) - 3))):
                issues.append("source_windows: more than three consecutive clips from one source")

    if review is None:
        issues.append("human review missing")
    else:
        output_hash = result.get("output_sha256", "")
        if not re.fullmatch(r"[0-9a-f]{64}", output_hash) or \
                review.get("output_sha256") != output_hash:
            issues.append("human review not bound to output SHA-256")
        if review.get("recipe") != recipe or \
                not result.get("graph_generator") or \
                review.get("graph_generator") != result.get("graph_generator"):
            issues.append("human review not bound to product/grammar")
        if not valid_review_identity(review):
            issues.append("human review identity/date missing or invalid")
        if review.get("playback_1x") is not True or review.get("playback_half") is not True:
            issues.append("human review must include 1x and 0.5x playback")
        checks = review.get("checks", {})
        for area in REVIEW_AREAS:
            if checks.get(area) is not True:
                issues.append(f"human review failed or missing: {area}")
        style_checks = review.get("style_checks", {})
        for area in STYLE_REVIEW_AREAS[recipe]:
            if style_checks.get(area) is not True:
                issues.append(f"human style review failed or missing: {area}")
        if review.get("blockers") != []:
            issues.append("human review blockers present or not recorded")

    return {"recipe": recipe, "machine_and_human_pass": not issues,
            "issues": issues, "human_review_present": review is not None,
            "android_acceptance": result.get("acceptance"),
            "independent_audio_verified": independent_audio_verified,
            "resolved_unknowns": [unknown] if resolved_headroom else []}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("result", type=Path, help=".result from GoldenRenderActivity")
    parser.add_argument("--recipe", required=True, choices=FRAME_US)
    parser.add_argument("--review", type=Path, help="signed local human-review JSON")
    parser.add_argument("--output", type=Path, help="actual MP4 for independent audio remeasurement")
    parser.add_argument("--ffmpeg", type=Path, help="independent decoder; requires --output")
    parser.add_argument("--audio-report", type=Path, help="optional saved report, compared with fresh measurement")
    args = parser.parse_args()
    if bool(args.output) != bool(args.ffmpeg) or args.audio_report and not args.output:
        parser.error("independent audio requires both --output and --ffmpeg")
    result = parse_result(args.result.read_text(encoding="utf-8"))
    review = json.loads(args.review.read_text(encoding="utf-8")) if args.review else None
    independent_audio = None
    if args.output:
        from quality_audio_report import measure
        try:
            independent_audio = measure(args.output, args.ffmpeg.resolve(), args.recipe)
            if args.audio_report:
                saved = json.loads(args.audio_report.read_text(encoding="utf-8"))
                if saved != independent_audio:
                    print(json.dumps({"machine_and_human_pass": False,
                                      "issues": ["saved audio report differs from fresh measurement"]}))
                    return 1
        except (OSError, UnicodeError, ValueError, subprocess.SubprocessError) as exc:
            print(json.dumps({"machine_and_human_pass": False,
                              "issues": [f"independent audio measurement unavailable: {exc}"]}))
            return 1
    report = assess(result, args.recipe, review, independent_audio)
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0 if report["machine_and_human_pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
