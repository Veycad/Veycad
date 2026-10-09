"""Compare inspector evidence without mistaking changed footage for an effect change."""
import argparse
import json
from pathlib import Path


def compare(left, right):
    def keyed(report):
        frames = report["frames"]
        result = {frame["output_us"]: frame for frame in frames}
        if len(result) != len(frames):
            raise ValueError("Duplicate output PTS in inspector")
        return result
    a, b = keyed(left), keyed(right)
    same, changed, missing = [], [], []
    for time in sorted(a.keys() | b.keys()):
        x, y = a.get(time), b.get(time)
        if x is None or y is None or x.get("decoded_source_us") is None or y.get("decoded_source_us") is None:
            missing.append(time)
        elif x["decoded_source_us"] == y["decoded_source_us"] and x.get("decoded_secondary_source_us") == y.get("decoded_secondary_source_us"):
            same.append(time)
        else:
            changed.append(time)
    return {"same_texture_pts": same, "changed_texture_pts": changed, "missing_evidence_pts": missing}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    args = parser.parse_args()
    result = compare(json.loads(args.before.read_text()), json.loads(args.after.read_text()))
    print(json.dumps({"counts": {k: len(v) for k, v in result.items()}, **result}, indent=2))
