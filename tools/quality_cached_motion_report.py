"""Read an existing cache16/17/18 payload; never decode, render or accept a product."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import math
from pathlib import Path
import re
import struct
import subprocess


MAGIC = 0x56414E4C
MOTION_METHOD = "person-adaptive-affine-background-query-fov-v9"
SEMANTICS_PROFILE = "editorial-semantics-v1"
CORRESPONDENCE_PROFILE = "editorial-correspondence-v1"
PROFILES = (SEMANTICS_PROFILE, CORRESPONDENCE_PROFILE)


def f32(value: float) -> float:
    return struct.unpack(">f", struct.pack(">f", value))[0]


# Kotlin compares the stored Float against .18f, not the Python double .18.
MOTION_THRESHOLD = f32(.18)


class Reader:
    def __init__(self, raw: bytes):
        self.stream = gzip.GzipFile(fileobj=io.BytesIO(raw))

    def read(self, fmt: str) -> tuple:
        size = struct.calcsize(">" + fmt)
        data = self.stream.read(size)
        if len(data) != size:
            raise ValueError("truncated cache")
        values = struct.unpack(">" + fmt, data)
        if any(isinstance(value, float) and not math.isfinite(value) for value in values):
            raise ValueError("non-finite cache value")
        return values

    def flag(self) -> bool:
        value, = self.read("B")
        if value not in (0, 1):
            raise ValueError("invalid Boolean/optional flag")
        return bool(value)

    def optional_floats(self, count: int) -> tuple | None:
        return self.read("f" * count) if self.flag() else None

    def count(self, maximum: int) -> int:
        value, = self.read("i")
        if not 0 <= value <= maximum:
            raise ValueError("invalid cache count")
        return value

    def ascii_java_utf(self) -> str:
        # DataOutputStream.writeUTF uses modified UTF-8. The frozen method is ASCII;
        # reject non-ASCII encodings rather than pretending to implement arbitrary MUTF-8.
        size, = self.read("H")
        data = self.stream.read(size)
        if len(data) != size:
            raise ValueError("truncated Java UTF method")
        try:
            return data.decode("ascii")
        except UnicodeDecodeError as error:
            raise ValueError("non-ASCII camera method") from error


def unit_values(values: tuple | list) -> None:
    if not all(0 <= value <= 1 for value in values):
        raise ValueError("invalid unit-range cache value")


def intensity(x: float, y: float) -> float:
    return min(1.0, f32(math.hypot(x, y)))


def read_motion(reader: Reader) -> dict | None:
    if not reader.flag():
        return None
    subject, x, y, confidence, cells, fraction, background, quadrants, interval = reader.read("ffffifiiq")
    if not (0 <= subject <= 1 and -1 <= x <= 1 and -1 <= y <= 1 and
            .5 <= confidence <= 1 and cells >= 4 and .25 <= fraction <= 1 and
            background >= 8 and 3 <= quadrants <= 4 and 100_000 <= interval <= 500_000):
        raise ValueError("invalid body motion support")
    return {"subject_intensity": subject, "camera_x": x, "camera_y": y,
            "camera_intensity": intensity(x, y), "camera_confidence": confidence,
            "subject_cells": cells, "subject_supported_fraction": fraction,
            "camera_cells": background, "camera_quadrants": quadrants, "interval_us": interval}


def read_camera(reader: Reader, pts: int) -> dict | None:
    if not reader.flag():
        return None
    x, y, confidence, cells, quadrants, previous, current, semantic, interval = reader.read("fffiiqqqq")
    method = reader.ascii_java_utf()
    if not (-1 <= x <= 1 and -1 <= y <= 1 and .5 <= confidence <= 1 and
            cells >= 8 and 3 <= quadrants <= 4 and previous >= 0 and current > previous and
            current == semantic == pts and current - previous == interval and
            100_000 <= interval <= 500_000 and method == MOTION_METHOD):
        raise ValueError("invalid camera support/PTS/method")
    return {"x": x, "y": y, "intensity": intensity(x, y), "confidence": confidence,
            "cells": cells, "quadrants": quadrants, "previous_pts_us": previous,
            "current_pts_us": current, "semantic_pts_us": semantic,
            "interval_us": interval, "method": method}


def read_plane(reader: Reader, flow: bool = False) -> bool:
    if not reader.flag():
        return False
    width, height, confidence = reader.read("iif")
    if not (1 <= width <= 1024 and 1 <= height <= 1024 and 0 <= confidence <= 1):
        raise ValueError("invalid attachment plane geometry/confidence")
    remaining = width * height * (2 if flow else 1)
    while remaining:
        chunk = min(remaining, 4096)
        reader.read("f" * chunk)
        remaining -= chunk
    return True


def read_attachment(reader: Reader, refinement: bool) -> int:
    pts, = reader.read("q")
    mask = read_plane(reader)
    depth = read_plane(reader)
    flow = read_plane(reader, flow=True)
    unit_values(reader.read("fff"))
    face = reader.optional_floats(5)
    if face is not None:
        unit_values(face)
        if face[2] <= 0 or face[3] <= 0:
            raise ValueError("invalid attachment face region")
    read_plane(reader)  # optional maskBlendTarget
    progress, = reader.read("f")
    opacity = reader.flag()
    if (pts < 0 or not (mask or depth or flow) or not 0 <= progress <= 1 or
            (opacity and not mask) or (refinement and not mask)):
        raise ValueError("invalid attachment")
    return pts


def fear_summary(samples: list[dict]) -> dict:
    """Exact FearOpeningEvidence OR, null/static and linked-camera run semantics."""
    moving = measured = subject_measured = conclusive = run = longest = longest_span = 0
    previous = -1
    start = 0
    for sample in samples:
        body = sample["motion_measurement"]
        camera = sample["camera_measurement"]
        if body is not None:
            subject_measured += 1
        if body is not None or camera is not None:
            measured += 1
        camera_moving = camera is not None and camera["intensity"] >= MOTION_THRESHOLD
        is_moving = camera_moving or (body is not None and
            max(body["subject_intensity"], body["camera_intensity"]) >= MOTION_THRESHOLD)
        if body is not None or camera_moving:
            conclusive += 1
        if not is_moving:
            run = 0
            previous = -1
            continue
        moving += 1
        pts = sample["pts_us"]
        if run == 0 or pts - previous > 500_000 or (
                camera is not None and camera["previous_pts_us"] != previous):
            run = 1
            start = pts
        else:
            run += 1
        previous = pts
        longest = max(longest, run)
        if run >= 3:
            longest_span = max(longest_span, pts - start)
    return {"moving_samples": moving, "longest_motion_run": longest,
            "longest_motion_run_span_us": longest_span,
            "measured_samples": measured, "unknown_samples": len(samples) - measured,
            "subject_measured_samples": subject_measured, "camera_measured_samples": measured,
            "conclusive_samples": conclusive, "inconclusive_samples": len(samples) - conclusive,
            "cached_fear_opening_motion_supported": longest >= 3 and longest_span >= 500_000}


def cache_file_name(source_sha256: str, cache_version: int, profile: str | None = None) -> str:
    if cache_version not in (16, 17, 18) or not re.fullmatch(r"[0-9a-f]{64}", source_sha256):
        raise ValueError("supported version and lowercase SHA-256 required")
    if cache_version == 18:
        if profile not in PROFILES:
            raise ValueError("cache18 requires an explicit supported profile")
        return f"v18-{profile}-250000-{source_sha256}.bin.gz"
    if profile is not None:
        raise ValueError("historical cache16/17 must not be associated with a profile")
    return f"v{cache_version}-250000-{source_sha256}.bin.gz"


def parse_cache(raw: bytes, source_sha256: str, cache_version: int,
                profile: str | None = None) -> dict:
    file_name = cache_file_name(source_sha256, cache_version, profile)
    reader = Reader(raw)
    magic, version = reader.read("ii")
    if magic != MAGIC or version != cache_version:
        raise ValueError("invalid/unsupported observation cache header")
    assessments = None
    if version == 18:
        header_profile = reader.ascii_java_utf()
        if header_profile not in PROFILES or header_profile != profile:
            raise ValueError("cache18 header profile must match the requested cache key")
        assessments, = reader.read("i")
    duration, semantic, masks, successes, count = reader.read("qiiii")
    if (duration <= 0 or not 1 <= count <= 4000 or
            not 0 <= semantic <= count or not 0 <= masks <= count or successes < 0):
        raise ValueError("invalid/unsupported observation cache header")
    if version == 18:
        expected_assessments = 0 if profile == SEMANTICS_PROFILE else count
        if assessments != expected_assessments:
            raise ValueError("cache18 assessment count contradicts its declared profile")
    samples = []
    for _ in range(count):
        pts, = reader.read("q")
        legacy_camera = reader.read("fff")
        legacy_subject = reader.read("fff")
        face = reader.optional_floats(6)
        if face is not None:
            unit_values(face[:1])
        gesture, occlusion, mask_confidence, mask_iou, quality, luma = reader.read("ffffff")
        unit_values((gesture, occlusion, mask_confidence, mask_iou, quality, luma))
        composition = reader.optional_floats(5)
        if composition is not None:
            unit_values(composition)
        scene_change, human = reader.read("ff")
        unit_values((scene_change, human))
        body = read_motion(reader)
        face_succeeded = reader.flag()
        gesture_available = reader.flag()
        camera = read_camera(reader, pts) if version >= 17 else None
        if version == 18 and profile == SEMANTICS_PROFILE and (body is not None or camera is not None):
            raise ValueError("editorial semantics cache must not contain typed motion records")
        if pts < 0 or pts >= duration or (samples and pts <= samples[-1]["pts_us"]):
            raise ValueError("invalid/non-monotonic observation PTS")
        samples.append({"pts_us": pts, "human_confidence": human,
                        "mask_confidence": mask_confidence, "mask_temporal_iou": mask_iou,
                        "visual_quality": quality, "mean_luma": luma,
                        "face_confidence": face[0] if face is not None else None,
                        "face_inference_succeeded": face_succeeded,
                        "gesture_evidence_available": gesture_available,
                        "gesture_confidence": gesture, "motion_measurement": body,
                        "camera_measurement": camera,
                        "legacy_camera_vector": legacy_camera, "legacy_subject_vector": legacy_subject})
    # Validate the complete payload, not merely an observation prefix of a truncated file.
    attachment_counts = []
    for refinement, maximum in ((False, 4000), (True, 90)):
        attachment_count = reader.count(maximum)
        attachment_counts.append(attachment_count)
        previous = -1
        for _ in range(attachment_count):
            pts = read_attachment(reader, refinement)
            if pts <= previous:
                raise ValueError("non-monotonic attachment PTS")
            previous = pts
    if reader.stream.read(1):
        raise ValueError("unexpected trailing cache payload")
    body_samples = [s["motion_measurement"] for s in samples if s["motion_measurement"] is not None]
    camera_samples = [s["camera_measurement"] for s in samples if s["camera_measurement"] is not None]
    camera_intensities = [m["camera_intensity"] for m in body_samples] + [m["intensity"] for m in camera_samples]
    result = {"schema_version": 1, "method": f"read-existing-v{version}-cache-observations-only",
            "cache_only": True, "fresh_frames_decoded": False, "not_acceptance": True,
            "cache_version": version,
            "cache_file": file_name,
            "cache_sha256": hashlib.sha256(raw).hexdigest(), "source_sha256": source_sha256,
            "source_association": "SHA-keyed Android cache filename; source bytes not freshly verified",
            "duration_us": duration, "observations": count, "semantic_frames": semantic,
            "mask_frames": masks, "semantic_model_successes": successes,
            "attachment_frames": attachment_counts[0], "mask_refinements": attachment_counts[1],
            "motion_method": MOTION_METHOD,
            "threshold_from_frozen_config": MOTION_THRESHOLD,
            "motion_units": "full-frame dx/(3*width/48), dy/(3*height/72); displacement, not velocity",
            "body_measured_samples": len(body_samples), "body_unknown_samples": count - len(body_samples),
            "independent_camera_measured_samples": len(camera_samples),
            "camera_only_measured_samples": sum(s["camera_measurement"] is not None and
                                               s["motion_measurement"] is None for s in samples),
            "measured_camera_intensity_peak": max(camera_intensities, default=None),
            "measured_subject_intensity_peak": max((m["subject_intensity"] for m in body_samples), default=None),
            "fear_evidence_method": "camera-or-subject-independent-v1" if version >= 17 else "whole-body-with-supported-camera-v16",
            "fear": fear_summary(samples), "samples": samples,
            "limitation": "Cached observations only: no fresh frames, semantic inference, pixel replay, physical ground truth, render validation, or human acceptance. Source SHA is filename association, not a fresh source check. v16 body-only records lack explicit previous/current PTS provenance."}
    if version == 18:
        requested = profile == CORRESPONDENCE_PROFILE
        state = "ASSESSED" if requested else "NOT_REQUESTED"
        result.update({"cache_profile": profile,
                       "correspondence_assessments_completed": assessments,
                       "correspondence_state": state,
                       "correspondence_requested": requested})
        if requested:
            result["fear"].update({"correspondence_state": state, "assessed": True,
                                   "not_requested_samples": 0})
        else:
            # Null typed records here mean no assessment was requested, not failed
            # measurement/physical inactivity. Do not emit a false FEAR verdict.
            result["body_unknown_samples"] = None
            result["fear_evidence_method"] = "not-requested-editorial-semantics-v1"
            result["fear"] = dict.fromkeys(result["fear"])
            result["fear"].update({"correspondence_state": state, "assessed": False,
                                   "not_requested_samples": count})
        result["limitation"] += (" Cache18 profile and completed-assessment count are explicit header evidence. "
            "NOT_REQUESTED is not unknown measured motion, physical insufficient motion, or a FEAR refusal. "
            "ASSESSED with null records remains attempted-but-unknown, not static movement.")
    return result


def write_report(report: dict, path: Path) -> None:
    with path.open("x", encoding="utf-8") as destination:
        json.dump(report, destination, indent=2, allow_nan=False)
        destination.write("\n")


def capture_cache(adb: str, serial: str | None, source_sha256: str, cache_version: int,
                  profile: str | None, report_path: Path,
                  snapshot_path: Path | None = None) -> dict:
    """Copy validated device evidence, not a re-analysis or acceptance decision.

    The optional binary snapshot retains exactly the bytes parsed for the report.
    No Android permission change or cache mutation is needed; adb reads as the
    debug app. Every local destination is new and historical bytes are preserved.
    """
    name = cache_file_name(source_sha256, cache_version, profile)
    if snapshot_path is not None and report_path.resolve() == snapshot_path.resolve():
        raise ValueError("snapshot and report must have different destinations")
    for path in (report_path, snapshot_path):
        if path is None:
            continue
        if path.exists():
            raise FileExistsError(f"historical evidence must not be overwritten: {path}")
        if not path.parent.is_dir():
            raise ValueError(f"destination directory does not exist: {path.parent}")
    command = [adb]
    if serial is not None:
        if not serial or serial.strip() != serial:
            raise ValueError("explicit device serial must not be empty or padded")
        command.extend(["-s", serial])
    command.extend(["exec-out", "run-as", "com.veycad.app", "cat",
                    "cache/full-video-analysis/" + name])
    completed = subprocess.run(command, capture_output=True, check=True, timeout=30)
    report = parse_cache(completed.stdout, source_sha256, cache_version, profile)
    if snapshot_path is not None:
        with snapshot_path.open("xb") as destination:
            destination.write(completed.stdout)
        report["snapshot_file"] = str(snapshot_path)
    write_report(report, report_path)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", help="explicit target when several devices are connected")
    parser.add_argument("--source-sha256", required=True)
    parser.add_argument("--cache-version", required=True, type=int, choices=(16, 17, 18))
    parser.add_argument("--profile", choices=PROFILES)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--snapshot", type=Path,
                        help="optional NEW binary copy of the exact cache bytes; never overwritten")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{64}", args.source_sha256):
        parser.error("invalid source SHA-256")
    if args.report.exists():
        parser.error("report already exists; historical evidence must not be overwritten")
    try:
        report = capture_cache(args.adb, args.serial, args.source_sha256, args.cache_version,
                               args.profile, args.report, args.snapshot)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        parser.error(str(error))
    print(json.dumps({key: value for key, value in report.items() if key != "samples"}, allow_nan=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
