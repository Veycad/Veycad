"""Independently measure unclamped float audio from the actual exported MP4.

This report supplements Android evidence; it never supplies human acceptance or
turns the existing release gate green by itself. No source media are modified.
"""

from __future__ import annotations

import argparse
from array import array
import hashlib
import json
import math
from pathlib import Path
import struct
import subprocess
import sys
import re

from quality_render_report import EXPECTED_DURATION_MS, FRAME_US


METHOD = "ffmpeg-unclamped-f32-wav-v1"
MAX_WAV_BYTES = 128 * 1024 * 1024
FLOAT_GUID = bytes.fromhex("0300000000001000800000aa00389b71")


def validate_report(report: dict, recipe: str, output_sha256: str | None) -> list[str]:
    """Validate a measured report, not just its 'passed' assertion.

    File-level callers must remeasure the export; this pure validator has no file authority.
    """
    if not isinstance(report, dict):
        return ["independent audio report is not an object"]
    if recipe not in FRAME_US:
        return ["independent audio product unknown"]
    issues = []
    if type(report.get("schema_version")) is not int or report.get("schema_version") != 1 or report.get("method") != METHOD:
        issues.append("independent audio method/schema mismatch")
    if not isinstance(report.get("decoder_version"), str) or not report["decoder_version"].startswith("ffmpeg version "):
        issues.append("independent audio decoder identity missing")
    if not isinstance(output_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", output_sha256) or \
            report.get("output_sha256") != output_sha256:
        issues.append("independent audio is not bound to this MP4 hash")
    if report.get("recipe") != recipe:
        issues.append("independent audio belongs to another product")
    if report.get("passed") is not True or report.get("issues") != []:
        issues.append("independent audio measurement failed")

    def number(key, *, integer=False):
        value = report.get(key)
        try:
            finite = math.isfinite(value) if type(value) in (int, float) else False
        except OverflowError:
            finite = False
        if not finite or value < 0 or \
                (integer and (type(value) is not int)):
            issues.append(f"independent audio {key}: invalid numeric evidence")
            return None
        return value

    rate = number("sample_rate", integer=True)
    channels = number("channels", integer=True)
    samples = number("pcm_samples", integer=True)
    frames = number("pcm_frames", integer=True)
    duration = number("decoded_duration_us", integer=True)
    peak = number("sample_peak")
    rms = number("rms")
    nonfinite = number("non_finite_samples", integer=True)
    over = number("over_full_scale_samples", integer=True)
    longest = number("longest_full_scale_run", integer=True)
    if rate is not None and rate <= 0 or channels is not None and not 1 <= channels <= 8:
        issues.append("independent audio rate/channel range invalid")
    if samples is not None and samples <= 0:
        issues.append("independent audio PCM is empty")
    if peak is not None and not 0 < peak <= 1 or rms is not None and rms <= 0:
        issues.append("independent audio silent/over-full-scale evidence")
    if peak is not None and rms is not None and rms > peak:
        issues.append("independent audio RMS exceeds peak")
    if nonfinite != 0 or over != 0 or longest is None or longest > 2:
        issues.append("independent audio invalid samples or plateau")
    if rate and channels and frames is not None and samples is not None and duration is not None:
        if samples != frames * channels or duration != frames * 1_000_000 // rate:
            issues.append("independent audio sample clock inconsistent")
        tolerance = FRAME_US[recipe] + 1_024_000_000 // rate
        if abs(duration - EXPECTED_DURATION_MS[recipe] * 1000) > tolerance:
            issues.append("independent audio duration mismatch")
    return issues


def parse_float_wav(raw: bytes) -> tuple[int, int, array]:
    """Read float WAV, including FFmpeg's unseekable RIFF/data size markers."""
    if len(raw) > MAX_WAV_BYTES:
        raise ValueError("decoded WAV exceeds size limit")
    if len(raw) < 12 or raw[:4] != b"RIFF" or raw[8:12] != b"WAVE":
        raise ValueError("not a RIFF WAVE")
    fmt = None
    payload = None
    cursor = 12
    while cursor < len(raw):
        if cursor + 8 > len(raw):
            raise ValueError("truncated WAV chunk header")
        tag = raw[cursor:cursor + 4]
        size = struct.unpack_from("<I", raw, cursor + 4)[0]
        cursor += 8
        if size == 0xFFFFFFFF:
            if tag != b"data":
                raise ValueError("unknown streaming chunk")
            size = len(raw) - cursor
        if cursor + size > len(raw):
            raise ValueError("truncated WAV chunk")
        chunk = raw[cursor:cursor + size]
        if tag == b"fmt ":
            if fmt is not None or len(chunk) < 16:
                raise ValueError("duplicate or truncated WAV format")
            encoding, channels, rate, byte_rate, block, bits = struct.unpack_from("<HHIIHH", chunk)
            if encoding == 0xFFFE:
                if len(chunk) < 40 or struct.unpack_from("<H", chunk, 16)[0] < 22 or chunk[24:40] != FLOAT_GUID:
                    raise ValueError("not extensible float WAV")
                if struct.unpack_from("<H", chunk, 18)[0] != 32:
                    raise ValueError("invalid float valid-bit count")
            elif encoding != 3:
                raise ValueError("integer/clamped PCM is not independent headroom evidence")
            if not (1 <= channels <= 8 and rate > 0 and bits == 32 and block == channels * 4
                    and byte_rate == rate * block):
                raise ValueError("invalid float WAV format")
            fmt = (rate, channels)
        elif tag == b"data":
            if payload is not None:
                raise ValueError("duplicate WAV data")
            payload = chunk
        cursor += size + size % 2
    if fmt is None or payload is None or len(payload) % (fmt[1] * 4):
        raise ValueError("missing format/data or incomplete PCM frame")
    samples = array("f")
    samples.frombytes(payload)
    if sys.byteorder != "little":
        samples.byteswap()
    return fmt[0], fmt[1], samples


def assess_pcm(samples, rate: int, channels: int, recipe: str) -> dict:
    if recipe not in FRAME_US or rate <= 0 or not 1 <= channels <= 8 or len(samples) % channels:
        raise ValueError("invalid PCM contract")
    peak = square_sum = 0.0
    nonfinite = over = longest = 0
    runs = [0] * channels
    signs = [0] * channels
    for index, value in enumerate(samples):
        channel = index % channels
        if not math.isfinite(value):
            nonfinite += 1
            runs[channel] = signs[channel] = 0
            continue
        magnitude = abs(value)
        peak = max(peak, magnitude)
        square_sum += value * value
        over += magnitude > 1
        sign = 1 if value >= 0 else -1
        if magnitude >= 1:
            runs[channel] = runs[channel] + 1 if signs[channel] == sign else 1
            signs[channel] = sign
            longest = max(longest, runs[channel])
        else:
            runs[channel] = signs[channel] = 0
    frames = len(samples) // channels
    duration_us = frames * 1_000_000 // rate
    tolerance = FRAME_US[recipe] + 1_024_000_000 // rate
    issues = []
    if not samples:
        issues.append("audio-pcm-empty")
    if nonfinite:
        issues.append("audio-non-finite-samples")
    if over:
        issues.append("audio-over-full-scale")
    if longest >= 3:
        issues.append("audio-full-scale-plateau")
    if samples and not nonfinite and peak == 0:
        issues.append("audio-silent")
    if abs(duration_us - EXPECTED_DURATION_MS[recipe] * 1000) > tolerance:
        issues.append("audio-decoded-duration-mismatch")
    return {"sample_rate": rate, "channels": channels, "pcm_samples": len(samples),
            "pcm_frames": frames, "decoded_duration_us": duration_us, "sample_peak": peak,
            "rms": math.sqrt(square_sum / len(samples)) if samples else 0.0,
            "non_finite_samples": nonfinite, "over_full_scale_samples": over,
            "longest_full_scale_run": longest, "issues": issues, "passed": not issues}


def measure(output: Path, ffmpeg: Path, recipe: str) -> dict:
    # Bind bytes before AND after decoding so a changing file cannot produce a valid report.
    before = sha256(output)
    version = subprocess.run([str(ffmpeg), "-version"], capture_output=True, check=True,
                             timeout=10).stdout.decode("utf-8", "replace").splitlines()[0]
    maximum_s = EXPECTED_DURATION_MS[recipe] / 1000 + 1
    decoded = subprocess.run([str(ffmpeg), "-v", "error", "-xerror", "-i", str(output),
                              "-map", "0:a:0", "-vn", "-t", str(maximum_s),
                              "-c:a", "pcm_f32le", "-f", "wav", "-rf64", "never",
                              "-fs", str(MAX_WAV_BYTES), "pipe:1"],
                             capture_output=True, check=True, timeout=60)
    rate, channels, samples = parse_float_wav(decoded.stdout)
    after = sha256(output)
    if before != after:
        raise ValueError("export changed while decoding")
    return {"schema_version": 1, "method": METHOD, "decoder_version": version,
            "output_sha256": after, "recipe": recipe,
            **assess_pcm(samples, rate, channels, recipe)}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--ffmpeg", type=Path, required=True)
    parser.add_argument("--recipe", required=True, choices=FRAME_US)
    parser.add_argument("--report", type=Path, help="new local JSON artifact; never overwrite an existing report")
    args = parser.parse_args()
    try:
        report = measure(args.output, args.ffmpeg.resolve(), args.recipe)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(json.dumps({"passed": False, "issues": [f"independent audio measurement failed: {error}"]}))
        return 2
    rendered = json.dumps(report, indent=2, allow_nan=False)
    if args.report:
        with args.report.open("x", encoding="utf-8") as destination:
            destination.write(rendered + "\n")
    print(rendered)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
