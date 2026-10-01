"""Retained inspector artifact integrity and self-consistency, not pixel proof.

The hash binds the saved bytes; their native-format records must agree with the
paired MP4 and raw report. Decoded texture timestamps are retained draw-call
evidence, not independent pixel forensics or evidence of human viewing.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
from pathlib import Path


SHA256 = re.compile(r"[0-9a-f]{64}")
TEMPORAL_METHOD = "decoded-texture-pts-v1"
TEMPORAL_POLICY = "temporal-distinct-pts-v1"
REFERENCE_POLICY = "reference-spatial-v1"
NO_TEMPORAL_POLICY = "no-temporal-layer-v1"
TEMPORAL_KINDS = frozenset(("DOUBLE_EXPOSURE", "MIRROR_SLICE"))
LAYER_KINDS = frozenset(("FLASH", "GLOW", "VIGNETTE", "DOUBLE_EXPOSURE",
                         "MIRROR_SLICE", "BLACK_FADE", "SUBJECT_STAGE"))


def temporal_contract_issues(result: dict, recipe: str) -> list[str]:
    """Explicit product intent only; never infer missing policy from old records."""
    issues = []
    if result.get("temporal_decoded_evidence_method") != TEMPORAL_METHOD:
        issues.append("temporal_decoded_evidence_method: explicit decoded texture evidence missing or unknown")
    if not isinstance(recipe, str):
        return issues + ["temporal_layer_policy: missing or invalid product recipe"]
    policy = result.get("temporal_layer_policy")
    expected = {"HEARTBEAT": TEMPORAL_POLICY, "FEAR_STROBE": NO_TEMPORAL_POLICY,
                "DUALITY_LOOP": NO_TEMPORAL_POLICY, "SIGMA": REFERENCE_POLICY}.get(recipe)
    if expected is None or policy != expected:
        issues.append("temporal_layer_policy: missing, unknown or inconsistent with product")
    generator = result.get("graph_generator")
    prefixes = {"HEARTBEAT": "HEARTBEAT_V1", "FEAR_STROBE": "FEAR_STROBE_V1",
                "DUALITY_LOOP": "DUALITY_LOOP_V1"}
    if not isinstance(generator, str) or not generator or \
            recipe in prefixes and not generator.startswith(prefixes[recipe]) or \
            recipe == "SIGMA" and "SUBJECT_REENTRY_PULSE_V2" not in generator:
        issues.append("temporal_layer_policy: product graph does not establish the declared intent")
    return issues


def _unique_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate inspector field: {key}")
        result[key] = value
    return result


def validate_report(report: object, result: dict, output: Path) -> list[str]:
    """Check persisted records; repeated successive PTS are legal, unlike a temporal self-copy."""
    issues: list[str] = []
    if not isinstance(report, dict):
        return ["inspector report must be an object"]

    def eq(record: dict, key: str, expected: object, label: str) -> None:
        value = record.get(key)
        if type(value) is not type(expected) or value != expected:
            issues.append(f"inspector {label}{key}: missing or inconsistent")

    def integer(record: dict, key: str, label: str, minimum: int = 0) -> int | None:
        value = record.get(key)
        if type(value) is not int or value < minimum:
            issues.append(f"inspector {label}{key}: invalid integer")
            return None
        return value

    def raw_integer(key: str, minimum: int = 0) -> int | None:
        value = result.get(key)
        if not isinstance(value, str) or not re.fullmatch(r"0|[1-9][0-9]*", value):
            issues.append(f"inspector raw {key}: invalid integer binding")
            return None
        parsed = int(value)
        if parsed < minimum:
            issues.append(f"inspector raw {key}: invalid integer binding")
            return None
        return parsed

    def raw_match(record: dict, key: str, raw_key: str, label: str, minimum: int = 0) -> int | None:
        actual = integer(record, key, label, minimum)
        expected = raw_integer(raw_key, minimum)
        if actual is not None and expected is not None and actual != expected:
            issues.append(f"inspector {label}{key}: differs from raw {raw_key}")
        return actual

    eq(report, "format", "veykad-render-inspector-v1", "")
    eq(report, "local_only", True, "")
    eq(report, "mp4_name", output.name, "")
    eq(report, "mp4_bytes", output.stat().st_size, "")
    graph = report.get("graph")
    summary = report.get("summary")
    container = report.get("container")
    for name, record in (("graph", graph), ("summary", summary), ("container", container)):
        if not isinstance(record, dict):
            issues.append(f"inspector {name}: must be an object")
    if not all(isinstance(record, dict) for record in (graph, summary, container)):
        return issues
    generator = result.get("graph_generator")
    if not isinstance(generator, str) or not generator.strip():
        issues.append("inspector raw graph_generator: missing")
    else:
        eq(graph, "generator", generator, "graph.")
    issues.extend(temporal_contract_issues(result, result.get("recipe")))
    eq(report, "temporal_decoded_evidence_method", TEMPORAL_METHOD, "")
    eq(graph, "temporal_layer_policy", result.get("temporal_layer_policy"), "graph.")
    policy = result.get("temporal_layer_policy")
    duration = raw_match(graph, "duration_ms", "duration_ms", "graph.", 1)
    clips = raw_match(graph, "clips", "clips", "graph.", 1)
    eq(summary, "accepted", True, "summary.")
    eq(summary, "issues", [], "summary.")
    planned = raw_match(summary, "planned_frames", "frames", "summary.", 1)
    shader = raw_match(summary, "shader_frames", "frames", "summary.", 1)
    eq(container, "accepted", True, "container.")
    eq(container, "issues", [], "container.")
    for key, raw_key, minimum in (("width", "encoded_width", 1), ("height", "encoded_height", 1),
                                  ("rotation", "encoded_rotation", 0),
                                  ("video_samples", "encoded_video_samples", 1),
                                  ("audio_samples", "encoded_audio_samples", 1),
                                  ("video_first_pts_us", "video_first_pts_us", 0),
                                  ("audio_first_pts_us", "audio_first_pts_us", 0),
                                  ("av_delta_us", "av_drift_us", 0)):
        raw_match(container, key, raw_key, "container.", minimum)
    for key in ("video_mime", "audio_mime"):
        expected = result.get("encoded_" + key)
        if not isinstance(expected, str) or not expected.strip():
            issues.append(f"inspector raw encoded_{key}: missing")
        else:
            eq(container, key, expected, "container.")
    video_last = integer(container, "video_last_pts_us", "container.")
    audio_last = integer(container, "audio_last_pts_us", "container.")
    if video_last is not None and audio_last is not None:
        eq(container, "av_delta_us", abs(video_last - audio_last), "container.")
    error = raw_integer("duration_error_us")
    if video_last is not None and duration is not None and error is not None and \
            abs(video_last - duration * 1_000) != error:
        issues.append("inspector container.video_last_pts_us: differs from raw duration error")
    frames = report.get("frames")
    if not isinstance(frames, list):
        return issues + ["inspector frames: must be an array"]
    if len(frames) != planned or len(frames) != shader:
        issues.append("inspector frames: count differs from summary/raw")
    previous_output = None
    previous_clip = None
    previous_source = None
    previous_decoded = None
    for index, frame in enumerate(frames):
        label = f"frames[{index}]."
        if not isinstance(frame, dict):
            issues.append(f"inspector {label}must be an object")
            continue
        output_us = integer(frame, "output_us", label)
        source_us = integer(frame, "source_us", label)
        clip = integer(frame, "clip", label)
        if clip is not None and clips is not None and clip >= clips:
            issues.append(f"inspector {label}clip: outside graph")
        if output_us is not None:
            if previous_output is not None and output_us <= previous_output:
                issues.append(f"inspector {label}output_us: not strictly chronological")
            previous_output = output_us
        decoded = frame.get("decoded_source_us")
        for key, minimum in (("decoded_source_us", 0), ("decoded_secondary_source_us", 0),
                             ("mask_source_us", 0), ("secondary_source_us", -1),
                             ("source_sampling_error_us", None)):
            if key not in frame:
                issues.append(f"inspector {label}{key}: missing")
            elif frame[key] is not None and (type(frame[key]) is not int or
                                             minimum is not None and frame[key] < minimum):
                issues.append(f"inspector {label}{key}: invalid source timestamp")
        if clip is not None and clip == previous_clip:
            if source_us is not None and previous_source is not None and source_us < previous_source:
                issues.append(f"inspector {label}source_us: decreases inside clip")
            if type(decoded) is int and type(previous_decoded) is int and decoded < previous_decoded:
                issues.append(f"inspector {label}decoded_source_us: decreases inside clip")
        previous_clip, previous_source, previous_decoded = clip, source_us, decoded
        kind = frame.get("layer_kind")
        if not isinstance(kind, str) or kind not in LAYER_KINDS:
            issues.append(f"inspector {label}layer_kind: missing or unknown")
        opacity = frame.get("layer_opacity")
        valid_opacity = type(opacity) in (int, float) and math.isfinite(opacity) and 0 <= opacity <= 1
        if not valid_opacity:
            issues.append(f"inspector {label}layer_opacity: missing or invalid visibility evidence")
        if isinstance(kind, str) and kind in TEMPORAL_KINDS and valid_opacity and opacity >= .25:
            if policy == NO_TEMPORAL_POLICY:
                issues.append(f"inspector {label}layer_kind: visible temporal layer contradicts no-temporal policy")
            elif policy == TEMPORAL_POLICY:
                if frame.get("dual_decoder") is not True:
                    issues.append(f"inspector {label}dual_decoder: visible temporal layer requires two decoded textures")
                secondary_decoded = frame.get("decoded_secondary_source_us")
                if type(decoded) is not int or decoded < 0 or \
                        type(secondary_decoded) is not int or secondary_decoded < 0:
                    issues.append(f"inspector {label}decoded_source_us: visible temporal layer needs valid decoded primary and secondary PTS")
                elif decoded == secondary_decoded:
                    issues.append(f"inspector {label}decoded_secondary_source_us: temporal layer is a decoded self-copy")
        for key in ("speed", "scale", "x", "y", "blur", "blackout", "occlusion", "foreground_reentry",
                    "glow", "glitch", "lens_blur", "defocus", "layer_opacity", "mask_confidence",
                    "subject_quality", "subject_occlusion", "mask_temporal_iou", "effective_source_speed"):
            if key in frame and frame[key] is not None and \
                    (type(frame[key]) not in (int, float) or not math.isfinite(frame[key])):
                issues.append(f"inspector {label}{key}: invalid finite number")
    if frames and isinstance(frames[0], dict):
        eq(frames[0], "output_us", 0, "frames[0].")
    if frames and video_last is not None and isinstance(frames[-1], dict):
        shader_last = frames[-1].get("output_us")
        # Native 30 fps shader PTS rounds 18,266,666.666... to 18,266,667 us;
        # the MP4 integer timebase may truncate it to 18,266,666 us. Permit only
        # this one-microsecond quantisation, not a whole-frame duration tolerance.
        if type(shader_last) is not int or abs(shader_last - video_last) > 1:
            issues.append("inspector frames[last].output_us: differs from container clock by more than 1us")
    return issues


def inspect_saved_inspector(path: Path, expected_sha256: object, output: Path, result: dict) -> list[str]:
    """The caller additionally confines both paths to its repository boundary."""
    if not isinstance(expected_sha256, str) or not SHA256.fullmatch(expected_sha256):
        return ["inspector SHA-256 missing or invalid"]
    try:
        payload = path.read_bytes()
        if hashlib.sha256(payload).hexdigest() != expected_sha256:
            return ["inspector SHA-256 differs from saved bytes"]
        report = json.loads(payload.decode("utf-8"), object_pairs_hook=_unique_object)
        return validate_report(report, result, output)
    except (OSError, UnicodeError, ValueError, TypeError, OverflowError) as exc:
        return [f"inspector report unreadable: {exc}"]
