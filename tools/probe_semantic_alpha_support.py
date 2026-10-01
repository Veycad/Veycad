"""Diagnostic: veto matte outside a dilated semantic person core.

Does not recover missing foreground, decontaminate colour or support accessories.
No model/runtime or algorithm from this probe is installed in the application.
"""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter


def constrain(alpha, core, margin=5):
    if (alpha.ndim != 2 or core.ndim != 2 or not alpha.size or not core.size or
            not np.isfinite(alpha).all() or not np.isfinite(core).all() or
            alpha.min() < 0 or alpha.max() > 1 or core.min() < 0 or core.max() > 1):
        raise ValueError("Expected finite nonempty 2D opacity/confidence in [0,1]")
    if margin < 0 or margin > 32:
        raise ValueError("Margin must be 0..32 semantic pixels")
    binary = Image.fromarray(np.uint8(core >= .5) * 255)
    # A zero margin is the identity dilation. Some Pillow builds crash in
    # native MaxFilter(1), so do not call the filter for that valid boundary.
    support = binary if margin == 0 else binary.filter(ImageFilter.MaxFilter(margin * 2 + 1))
    support = support.filter(ImageFilter.GaussianBlur(1))
    support = np.asarray(support.resize((alpha.shape[1], alpha.shape[0]),
                         Image.Resampling.BILINEAR), dtype=np.float32) / 255
    return alpha * support


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("alpha", type=Path)
    parser.add_argument("channels", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--margin", type=int, default=5)
    args = parser.parse_args()
    source = Image.open(args.source).convert("RGB")
    alpha = np.asarray(Image.open(args.alpha).convert("L"), dtype=np.float32) / 255
    if alpha.shape != (source.height, source.width):
        raise ValueError("Source and alpha dimensions differ")
    core = np.clip(sum(np.asarray(Image.open(args.channels / f"channel-{i}.png").convert("L"),
                                 dtype=np.float32) / 255 for i in range(1, 5)), 0, 1)
    constrained = constrain(alpha, core, args.margin)
    args.output.mkdir(parents=True, exist_ok=False)
    Image.fromarray(np.rint(constrained * 255).astype(np.uint8)).save(args.output / "alpha.png")
    rgb = np.asarray(source, dtype=np.float32)
    comparison = Image.fromarray(np.concatenate([rgb, rgb * alpha[..., None],
                  rgb * constrained[..., None]], axis=1).clip(0, 255).astype(np.uint8))
    comparison.thumbnail((1080, 960))
    comparison.save(args.output / "comparison.png")
    report = {"scope": "single-frame diagnostic; no temporal or app acceptance",
              "margin_semantic_pixels": args.margin,
              "removed_alpha_mass_fraction": float(1 - constrained.sum() / max(alpha.sum(), 1e-9)),
              "source": str(args.source), "alpha": str(args.alpha), "channels": str(args.channels),
              "limitations": "core classes 1..4 only; no accessory handling; no colour recovery"}
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
