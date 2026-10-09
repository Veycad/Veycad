"""Measure opening shader silhouette change with source/mask held fixed.

Entrance translation, visibility fade and frame motion are intentionally excluded.
This isolates the time-dependent erosion/threshold from legitimate animation.
"""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image


def smooth(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def sample(mask, dx, dy):
    y, x = np.indices(mask.shape, dtype=float)
    x, y = np.clip(x + dx, 0, mask.shape[1] - 1), np.clip(y + dy, 0, mask.shape[0] - 1)
    x0, y0 = np.floor(x).astype(int), np.floor(y).astype(int)
    x1, y1 = np.minimum(x0 + 1, mask.shape[1] - 1), np.minimum(y0 + 1, mask.shape[0] - 1)
    fx, fy = x - x0, y - y0
    return (mask[y0, x0] * (1 - fx) + mask[y0, x1] * fx) * (1 - fy) + (
        mask[y1, x0] * (1 - fx) + mask[y1, x1] * fx) * fy


def shader_alpha(mask, progress):
    blur = (4 * mask + sum(sample(mask, x, y) for x, y in [(1, 0), (-1, 0), (0, 1), (0, -1)])
            + .5 * sum(sample(mask, x, y) for x, y in [(1, 1), (-1, -1), (1, -1), (-1, 1)])) / 10
    tightening = smooth(.62, .78, progress)
    radius = .65 + (2.88 - .65) * tightening
    core = np.minimum.reduce([mask] + [sample(mask, x, y) for x, y in
                                      [(radius, 0), (-radius, 0), (0, radius), (0, -radius)]])
    refined = blur + (core - blur) * (.38 + (.97 - .38) * tightening)
    return smooth(.42 + .24 * tightening, .74 + .15 * tightening, refined)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mask", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    mask = np.asarray(Image.open(args.mask).convert("L"), dtype=float) / 255
    previous = shader_alpha(mask, .62)
    baseline = previous.copy()
    samples = []
    for progress in np.linspace(.62, .78, 9):
        alpha = shader_alpha(mask, progress)
        samples.append({"progress": float(progress), "alpha_area": float(alpha.sum()),
                        "changed_pixels_from_start": int((abs(alpha - baseline) > .1).sum()),
                        "maximum_step_change": float(abs(alpha - previous).max())})
        previous = alpha
    report = {"source_mask": str(args.mask.resolve()), "shape": list(mask.shape),
              "source_and_mask_fixed": True, "samples": samples,
              "area_loss_percent": float(100 * (1 - previous.sum() / baseline.sum())),
              "fixed_settled_filter_area": float(shader_alpha(mask, 1).sum())}
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
