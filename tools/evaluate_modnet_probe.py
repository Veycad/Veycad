"""Offline diagnostic only; no app dependency or network access.

MODNet official ONNX normalization/size policy, with Pillow BOX resizing instead
of OpenCV INTER_AREA. This is not a bit-exact reproduction of its demo.
"""
import argparse
import hashlib
import json
from pathlib import Path
import time

def input_size(width, height):
    if max(width, height) < 512 or min(width, height) > 512:
        scale = 512 / min(width, height)
        width, height = int(width * scale), int(height * scale)
    return max(32, width - width % 32), max(32, height - height % 32)


def main():
    # Heavy diagnostic dependencies are required only for an actual model run. Keeping them
    # local lets the pure size-policy unit tests run on a clean development Python installation.
    import numpy as np
    import onnxruntime as ort
    from PIL import Image

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    ort.disable_telemetry_events()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    session = ort.InferenceSession(str(args.model), options, providers=["CPUExecutionProvider"])
    source = Image.open(args.source).convert("RGB")
    pixels = np.asarray(source, dtype=np.float32)
    size = input_size(*source.size)
    # Float channel resize avoids quantizing normalized input before inference.
    resized = np.stack([np.asarray(Image.fromarray(pixels[..., i]).resize(size, Image.Resampling.BOX))
                        for i in range(3)], axis=-1)
    tensor = ((resized - 127.5) / 127.5).transpose(2, 0, 1)[None]
    start = time.monotonic()
    prediction = session.run(None, {session.get_inputs()[0].name: tensor})[0][0, 0]
    elapsed = time.monotonic() - start
    if not np.isfinite(prediction).all() or prediction.min() < 0 or prediction.max() > 1:
        raise ValueError("Invalid opacity output")
    alpha = np.asarray(Image.fromarray(prediction).resize(source.size, Image.Resampling.BILINEAR))
    args.output.mkdir(parents=True, exist_ok=False)
    Image.fromarray(np.rint(alpha * 255).astype(np.uint8)).save(args.output / "alpha.png")
    cutout = Image.fromarray(np.rint(pixels * alpha[..., None]).astype(np.uint8))
    cutout.save(args.output / "source-alpha.png")
    preview = Image.new("RGB", (source.width * 2, source.height))
    preview.paste(source, (0, 0))
    preview.paste(cutout, (source.width, 0))
    preview.thumbnail((1080, 960))
    preview.save(args.output / "comparison.png")
    report = {"source": str(args.source.resolve()), "input_size": size,
              "source_sha256": hashlib.sha256(args.source.read_bytes()).hexdigest(),
              "model_sha256": hashlib.sha256(args.model.read_bytes()).hexdigest(),
              "seconds": elapsed, "runtime": ort.__version__,
              "scope": "single-frame local diagnostic; not temporal or Android acceptance",
              "resampling": "Pillow float BOX input / bilinear float alpha output"}
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
