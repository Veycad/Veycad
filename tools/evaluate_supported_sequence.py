"""Constrain previously exported opacity with exact-PTS semantic masks, locally."""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image

from probe_semantic_alpha_support import constrain


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("alpha_dir", type=Path)
    parser.add_argument("semantic_dir", type=Path)
    parser.add_argument("video_source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    manifest = json.loads((args.alpha_dir / "manifest.json").read_text())
    if hashlib.sha256(args.video_source.read_bytes()).hexdigest() != manifest["source_sha256"]:
        raise ValueError("Source hash mismatch")
    timestamps = manifest["timestamps_us"]
    if len(timestamps) < 2 or any(b <= a for a, b in zip(timestamps, timestamps[1:])):
        raise ValueError("Expected increasing source timestamps")
    records, previews = [], []
    # Validate all files before creating an incomplete output directory.
    for pts in timestamps:
        for path in [args.alpha_dir / f"alpha-{pts:012d}.png",
                     args.semantic_dir / f"person-{pts:012d}.png",
                     args.semantic_dir / f"source-{pts:012d}.png"]:
            if not path.is_file():
                raise ValueError(f"Missing exact-PTS input: {path}")
    args.output.mkdir(parents=True, exist_ok=False)
    alpha_out = args.output / "alpha"
    alpha_out.mkdir()
    for pts in timestamps:
        alpha_path = args.alpha_dir / f"alpha-{pts:012d}.png"
        semantic_path = args.semantic_dir / f"person-{pts:012d}.png"
        alpha = np.asarray(Image.open(alpha_path).convert("L"), dtype=np.float32) / 255
        core = np.asarray(Image.open(semantic_path).convert("L"), dtype=np.float32) / 255
        source_path = args.semantic_dir / f"source-{pts:012d}.png"
        source = np.asarray(Image.open(source_path).convert("RGB"))
        if alpha.shape != (manifest["height"], manifest["width"]) or source.shape[:2] != alpha.shape:
            raise ValueError("Source/alpha/manifest dimensions mismatch")
        new = constrain(alpha, core)
        Image.fromarray(np.rint(new * 255).astype(np.uint8)).save(alpha_out / alpha_path.name)
        preview = Image.fromarray(np.concatenate([source, source * alpha[..., None],
                                                  source * new[..., None]], axis=1).astype(np.uint8))
        preview.save(args.output / f"compare-{pts:012d}.png")
        previews.append(preview)
        records.append({"pts_us": pts, "alpha_sha256": hashlib.sha256(alpha_path.read_bytes()).hexdigest(),
                        "source_frame_sha256": hashlib.sha256(source_path.read_bytes()).hexdigest(),
                        "semantic_sha256": hashlib.sha256(semantic_path.read_bytes()).hexdigest(),
                        "removed_alpha_mass_fraction": float(1 - new.sum() / max(alpha.sum(), 1e-9))})
    durations = [max(10, round((b - a) / 1000)) for a, b in zip(timestamps, timestamps[1:])]
    previews[0].save(args.output / "sequence.gif", save_all=True, append_images=previews[1:],
                     duration=durations + [durations[-1]], loop=0)
    manifest.update(inference_mode="independent_alpha_with_semantic_support",
                    scope="external diagnostic; no production acceptance", support_margin_pixels=5)
    (alpha_out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (args.output / "report.json").write_text(json.dumps(records, indent=2) + "\n")
    print(json.dumps({"frames": len(records), "output": str(args.output)}))


if __name__ == "__main__":
    main()
