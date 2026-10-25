"""Compare identical on-device probe runs; timing claims require decoded-media equality."""
import argparse
import hashlib
import json
import statistics
import subprocess
from pathlib import Path


def median_gain(before, after):
    if len(before) < 3 or len(after) < 3 or min(before + after) <= 0:
        raise ValueError("Need at least three positive measured samples per build")
    old, new = statistics.median(before), statistics.median(after)
    return {"baseline_ms": old / 1e6, "optimized_ms": new / 1e6,
            "reduction_percent": (old - new) * 100 / old,
            "baseline_samples_ms": [x / 1e6 for x in before],
            "optimized_samples_ms": [x / 1e6 for x in after]}


def decoded_signature(ffmpeg, path, audio=False):
    command = [ffmpeg, "-v", "error", "-xerror", "-threads", "1", "-i", str(path),
               "-map", "0:a:0" if audio else "0:v:0"]
    command += (["-c:a", "pcm_f32le", "-f", "hash", "-hash", "sha256", "-"] if audio else
                ["-fps_mode", "passthrough", "-f", "framemd5", "-"])
    result = subprocess.run(command, capture_output=True, check=True, timeout=120)
    if result.stderr.strip():
        raise ValueError(f"Decoder diagnostics for {path}: {result.stderr.decode(errors='replace')}")
    lines = result.stdout.decode().splitlines()
    if not audio:
        # Ignore FFmpeg build comments; retain timebase, geometry, every frame's PTS/size/hash.
        lines = [line for line in lines if not line.startswith(("#software:", "#format:", "#version:", "#hash:"))]
        if not any(line and not line.startswith("#") for line in lines):
            raise ValueError(f"No decoded frames: {path}")
    elif not any(line.startswith("SHA256=") for line in lines):
        raise ValueError(f"No decoded audio: {path}")
    return hashlib.sha256("\n".join(lines).encode()).hexdigest()


def compare(baseline, optimized, ffmpeg):
    reports = [[(folder, json.loads((folder / "report.json").read_text(encoding="utf-8")))
                for folder in group] for group in (baseline, optimized)]
    identity = None
    reference = {}
    timings = [{key: [] for key in ("frame_decode_ns", "render_30_ns", "render_60_ns")} for _ in range(2)]
    checked = 0
    inputs = []
    for group, entries in enumerate(reports):
        for folder, report in entries:
            if report.get("status") != "ok":
                raise ValueError(f"Incomplete probe: {folder}")
            current = (report["device"], report["source_sha256"], report["exact_matte_sha256"])
            if identity is None:
                identity = current
            if current != identity:
                raise ValueError(f"Device, input or exact-matte output changed: {folder}")
            inputs.append({"folder": str(folder), "report_sha256": hashlib.sha256((folder / "report.json").read_bytes()).hexdigest()})
            for sample in report["samples"]:
                if sample["warmup"]:
                    continue
                frame_key = (sample["analysis_frames"], sample["frame_sha256"])
                if reference.setdefault("analysis", frame_key) != frame_key:
                    raise ValueError(f"Analysis pixels/PTS differ: {folder}")
                for key in timings[group]:
                    timings[group][key].append(sample[key])
                for fps in (30, 60):
                    path = folder / f"export-{sample['iteration']}-{fps}.mp4"
                    signature = (decoded_signature(ffmpeg, path), decoded_signature(ffmpeg, path, audio=True))
                    if reference.setdefault(fps, signature) != signature:
                        raise ValueError(f"Decoded frames/PTS or PCM differ: {path}")
                    checked += 1
    return {"device": identity[0], "source_sha256": identity[1], "exact_matte_sha256": identity[2],
            "decoded_exports_checked": checked, "decoded_media_identical": True, "reports": inputs,
            "timings": {key: median_gain(timings[0][key], timings[1][key]) for key in timings[0]}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, nargs="+", required=True)
    parser.add_argument("--optimized", type=Path, nargs="+", required=True)
    parser.add_argument("--ffmpeg", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = compare(args.baseline, args.optimized, args.ffmpeg)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
