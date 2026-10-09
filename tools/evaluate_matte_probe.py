"""Local-only RVM diagnostic; never used by the app or its build.

Compares source RGB * predicted alpha against predicted foreground * the SAME
alpha. This isolates foreground-colour recovery from silhouette changes.
Single-frame results do not establish temporal quality or Android performance.
Model and runtime must already exist locally; this script has no network calls.
"""
import argparse
import hashlib
import json
from pathlib import Path
import time

import numpy as np
import onnxruntime as ort
from PIL import Image

ort.disable_telemetry_events()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--sequence", type=Path, help="Directory of source-<PTS_us>.png frames")
    parser.add_argument("--video-source", type=Path, help="Bind exported alpha PNGs to this local MP4 SHA256")
    parser.add_argument("--reset-state", action="store_true",
                        help="Diagnostic control: reset recurrent state on every frame")
    parser.add_argument("--analysis-side", type=int, default=512,
                        help="Maximum internal analysis side; independent of input RGB resolution")
    args = parser.parse_args()
    if not 128 <= args.analysis_side <= 1024:
        parser.error("analysis-side must be between 128 and 1024")
    args.output.mkdir(parents=True, exist_ok=False)
    rgb = np.asarray(Image.open(args.source).convert("RGB"), dtype=np.float32) / 255
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    session = ort.InferenceSession(str(args.model), options,
                                   providers=["CPUExecutionProvider"])
    inputs = {f"r{i}i": np.zeros((1, 1, 1, 1), np.float32) for i in range(1, 5)}
    inputs["src"] = rgb.transpose(2, 0, 1)[None]
    inputs["downsample_ratio"] = np.array([min(1., args.analysis_side / max(rgb.shape[:2]))], np.float32)
    start = time.monotonic()
    foreground, alpha = session.run(["fgr", "pha"], inputs)
    elapsed = time.monotonic() - start
    foreground = foreground[0].transpose(1, 2, 0)
    alpha = alpha[0].transpose(1, 2, 0)
    assert foreground.shape == rgb.shape and alpha.shape == (*rgb.shape[:2], 1)
    assert np.isfinite(foreground).all() and np.isfinite(alpha).all()
    assert alpha.min() >= 0 and alpha.max() <= 1
    products = {"source": rgb, "alpha": np.repeat(alpha, 3, axis=2),
                "source-alpha": rgb * alpha, "recovered-alpha": foreground * alpha}
    for name, pixels in products.items():
        Image.fromarray(np.rint(np.clip(pixels, 0, 1) * 255).astype(np.uint8)).save(
            args.output / f"{name}.png")
    # Display both branches at identical resolution; no sharpening or retouching.
    comparison = np.concatenate(list(products.values()), axis=1)
    Image.fromarray(np.rint(np.clip(comparison, 0, 1) * 255).astype(np.uint8)).save(
        args.output / "comparison.png")
    report = {"source": str(args.source.resolve()),
              "source_sha256": hashlib.sha256(args.source.read_bytes()).hexdigest(),
              "model_sha256": hashlib.sha256(args.model.read_bytes()).hexdigest(),
              "runtime": ort.__version__, "provider": session.get_providers(),
              "seconds": elapsed, "shape": list(rgb.shape),
              "downsample_ratio": float(inputs["downsample_ratio"][0]),
              "recurrent_state": "zero; single independent frame",
              "scope": "diagnostic only; not app render or temporal acceptance",
              "columns": list(products)}
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    if args.sequence:
        frames = sorted(args.sequence.glob("source-*.png"))
        if len(frames) < 2:
            raise ValueError("Sequence requires at least two source frames")
        rec = {f"r{i}i": np.zeros((1, 1, 1, 1), np.float32) for i in range(1, 5)}
        previews = []
        timestamps = []
        alpha_dir = args.output / "alpha"
        if args.video_source:
            alpha_dir.mkdir()
        start = time.monotonic()
        for path in frames:
            if args.reset_state:
                rec = {f"r{i}i": np.zeros((1, 1, 1, 1), np.float32) for i in range(1, 5)}
            pixels = np.asarray(Image.open(path).convert("RGB"), dtype=np.float32) / 255
            if pixels.shape != rgb.shape:
                raise ValueError("Recurrent frames must have the same dimensions")
            timestamps.append(int(path.stem.removeprefix("source-")))
            fgr, pha, *states = session.run(None, {**rec,
                "src": pixels.transpose(2, 0, 1)[None],
                "downsample_ratio": inputs["downsample_ratio"]})
            rec = {f"r{i + 1}i": state for i, state in enumerate(states)}
            fgr, pha = fgr[0].transpose(1, 2, 0), pha[0].transpose(1, 2, 0)
            assert np.isfinite(fgr).all() and np.isfinite(pha).all()
            if args.video_source:
                Image.fromarray(np.rint(np.clip(pha[..., 0], 0, 1) * 255).astype(np.uint8)).save(
                    alpha_dir / f"alpha-{timestamps[-1]:012d}.png")
            pair = np.concatenate([pixels, fgr * pha], axis=1)
            preview = Image.fromarray(np.rint(np.clip(pair, 0, 1) * 255).astype(np.uint8))
            preview.save(args.output / path.name)
            previews.append(preview)
        intervals = np.diff(timestamps)
        if not (intervals > 0).all():
            raise ValueError("Source PTS must advance strictly")
        durations = [max(10, round(int(delta) / 1000)) for delta in intervals]
        previews[0].save(args.output / "sequence.gif", save_all=True,
                         append_images=previews[1:], duration=durations + [durations[-1]], loop=0)
        sequence_report = {"frames": len(frames), "source_pts_us": timestamps,
                           "seconds": time.monotonic() - start,
                           "recurrent_state": ("reset on every frame" if args.reset_state else
                                               "preserved between successive source frames"),
                           "nominal_fps": float(1_000_000 / np.median(intervals)),
                           "scope": "source-time diagnostic; not app acceptance"}
        (args.output / "sequence-report.json").write_text(json.dumps(sequence_report, indent=2) + "\n")
        if args.video_source:
            manifest = {"source_sha256": hashlib.sha256(args.video_source.read_bytes()).hexdigest(),
                        "model_sha256": report["model_sha256"],
                        "inference_mode": "frame_independent" if args.reset_state else "recurrent",
                        "timestamps_us": timestamps, "width": rgb.shape[1], "height": rgb.shape[0],
                        "scope": "external local diagnostic alpha; not production inference"}
            (alpha_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        print(json.dumps(sequence_report))


if __name__ == "__main__":
    main()
