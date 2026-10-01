"""Measure original video packet PTS without normalising or transcoding input.

CFR compatibility is tested against any constant period with timestamp rounding
of at most one timebase tick. VFR means observed presentation timing does not
fit that model and intervals differ by more than one tick. Sub-tick-range
irregularity is unknown (clock jitter cannot prove a useful VFR stress case).
It does not attest camera origin, content or decoder support.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
from collections import Counter
from pathlib import Path

from quality_holdout import sha256_file


def assess_pts(pts: list[int], timebase: tuple[int, int]) -> dict:
    numerator, denominator = timebase
    if numerator <= 0 or denominator <= 0 or len(pts) < 3:
        raise ValueError("positive timebase and at least three presentation timestamps required")
    pts = sorted(pts)  # Packet decode order may differ from presentation order.
    deltas = [b - a for a, b in zip(pts, pts[1:])]
    if min(deltas) <= 0:
        raise ValueError("duplicate/non-increasing presentation timestamps")
    # For every pair i,j, rounding errors differ by no more than one tick:
    # (PTS[j]-PTS[i]-1)/(j-i) <= period <= (PTS[j]-PTS[i]+1)/(j-i).
    # Intersection of these bounds detects cumulative irregularity as well as
    # large per-frame gaps; simply counting distinct deltas mistakes CFR for VFR.
    lower, upper = 0.0, float("inf")
    for i, start in enumerate(pts[:-1]):
        for j in range(i + 1, len(pts)):
            distance = j - i
            difference = pts[j] - start
            lower = max(lower, (difference - 1) / distance)
            upper = min(upper, (difference + 1) / distance)
            if lower > upper + 1e-10:
                break
        if lower > upper + 1e-10:
            break
    compatible = lower <= upper + 1e-10
    mode = "cfr" if compatible else "vfr" if max(deltas) - min(deltas) > 1 else "unknown"
    return {"packet_count": len(pts), "timebase": [numerator, denominator],
            "first_pts": pts[0], "last_pts": pts[-1],
            "delta_histogram_ticks": {str(k): v for k, v in sorted(Counter(deltas).items())},
            "cfr_rounding_compatible": compatible,
            "measured_frame_rate_mode": mode,
            "mean_presentation_fps": (len(pts) - 1) * denominator /
                                     ((pts[-1] - pts[0]) * numerator),
            "compatible_period_ticks": [lower, upper] if compatible else None}


def measure(source: Path, ffmpeg: Path) -> dict:
    initial_hash = sha256_file(source)
    completed = subprocess.run([str(ffmpeg), "-v", "error", "-copyts", "-copytb", "1",
                                "-i", str(source), "-map", "0:v:0", "-an", "-c:v", "copy",
                                "-f", "framecrc", "-"], capture_output=True, text=True,
                               check=True, timeout=120)
    timebase = None
    pts = []
    for line in completed.stdout.splitlines():
        match = re.fullmatch(r"#tb 0:\s*(\d+)/(\d+)", line)
        if match:
            timebase = (int(match[1]), int(match[2]))
        elif line and not line.startswith("#"):
            fields = line.split(",")
            if len(fields) < 6 or fields[0].strip() != "0":
                raise ValueError("unexpected framecrc packet record")
            pts.append(int(fields[2]))
    if timebase is None or initial_hash != sha256_file(source):
        raise ValueError("missing original timebase or source changed during measurement")
    return {"schema_version": 1, "source_sha256": initial_hash,
            "measurement": "original-video-packet-pts-framecrc-copyts-copytb1",
            "classification_rule": "pairwise-cfr-rounding-one-tick-conservative-v2",
            **assess_pts(pts, timebase)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--ffmpeg", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output already exists; do not overwrite evidence")
    report = measure(args.source.resolve(), args.ffmpeg.resolve())
    with args.output.open("x", encoding="utf-8") as destination:
        json.dump(report, destination, indent=2, allow_nan=False)
        destination.write("\n")
    print(json.dumps(report, indent=2, allow_nan=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
