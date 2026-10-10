"""Package three-second excerpts of real Android exports; never synthesize montage effects."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess

PREVIEWS = {
    "heartbeat": ("style_preview_heartbeat", "heartbeat", 12.7),
    "fear": ("style_preview_fear", "fear_strobe", 9.5),
    "duality": ("style_preview_duality", "duality_loop", 3.0),
}
RECIPES = {"heartbeat": "HEARTBEAT", "fear": "FEAR_STROBE", "duality": "DUALITY_LOOP"}


def completed_export(directory, name):
    source = directory / f"{name}.mp4"
    marker = source.with_suffix(".mp4.result")
    evidence = {}
    if marker.is_file():
        evidence = dict(line.split("=", 1) for line in marker.read_text(encoding="utf-8").splitlines()
                        if "=" in line)
    if not source.is_file() or evidence.get("status") != "ok" or evidence.get("recipe") != RECIPES[name]:
        raise ValueError(f"A completed Android export and its result are required: {source}")
    if hashlib.sha256(source.read_bytes()).hexdigest() != evidence.get("output_sha256"):
        raise ValueError(f"The export does not match its Android result: {source}")
    return source, evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", required=True, type=Path)
    parser.add_argument("--exports", required=True, type=Path)
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    # Validate all evidence before overwriting any packaged resource.
    exports = {name: completed_export(args.exports, name) for name in PREVIEWS}
    raw = args.project / "app/src/main/res/raw"
    posters = args.project / "app/src/main/res/drawable-nodpi"
    raw.mkdir(parents=True, exist_ok=True)
    posters.mkdir(parents=True, exist_ok=True)
    report = []
    for name, (resource, style_id, start) in PREVIEWS.items():
        source, evidence = exports[name]
        target = raw / f"{resource}.mp4"
        poster = posters / f"{resource}.png"
        subprocess.run([str(args.ffmpeg), "-hide_banner", "-loglevel", "error", "-y",
                        "-ss", str(start), "-i", str(source), "-t", "3", "-map", "0:v:0", "-an",
                        "-vf", "scale=320:-2", "-c:v", "libx264", "-profile:v", "baseline",
                        "-crf", "25", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(target)], check=True)
        subprocess.run([str(args.ffmpeg), "-hide_banner", "-loglevel", "error", "-y",
                        "-i", str(target), "-frames:v", "1", str(poster)], check=True)
        report.append({"style_id": style_id, "resource": resource, "start_seconds": start,
                       "duration_seconds": 3, "audio": False,
                       "recipe": evidence["recipe"],
                       "renderer_sha256": evidence.get("runtime_apk_sha256"),
                       "export_acceptance": evidence.get("acceptance") == "true",
                       "export_acceptance_issues": evidence.get("acceptance_issues", "").split(",") if evidence.get("acceptance_issues") else [],
                       "export_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                       "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
                       "bytes": target.stat().st_size})
    report_path = args.project / "docs/design/style-preview-assets.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
