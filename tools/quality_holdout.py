"""Verify the independent, local montage-quality corpus before a blind run.

No footage is downloaded or bundled by this script. The checked-in manifest
contains provenance and hashes; media stay under ignored ``artifacts/``.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from quality_product_scope import manifest_scope


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = REPO_ROOT / "docs/quality/holdout-user-only-active-candidate-20260927.json"
ALLOWED_TAGS = {
    "portrait", "full_body", "multiple_people", "no_people", "dark",
    "backlit", "low_quality", "static_camera", "intense_motion",
}
REQUIRED_FPS = {24, 25, 30, 60}
REQUIRED_CONTAINERS = {"mp4", "webm", "mov"}
REQUIRED_FRAME_RATE_MODES = {"cfr", "vfr"}
MINIMUM_CAMERA_MODELS = 2


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parent_identities(entries: list[dict]) -> dict[str, str]:
    """Merge exact parent labels and source/parent hashes, including alias chains."""
    links: dict[tuple[str, str], tuple[str, str]] = {}
    keys_by_id: dict[str, list[tuple[str, str]]] = {}

    def root(key: tuple[str, str]) -> tuple[str, str]:
        while links[key] != key:
            links[key] = links[links[key]]
            key = links[key]
        return key

    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            continue
        keys = {("hash", value.lower()) for field in ("sha256", "parent_sha256")
                if isinstance(value := entry.get(field), str) and value}
        if isinstance(label := entry.get("parent_source"), str) and label:
            keys.add(("parent", label))
        ordered = sorted(keys or {("media", entry["id"])})
        keys_by_id[entry["id"]] = ordered
        for key in ordered:
            links.setdefault(key, key)
        for key in ordered[1:]:
            first, second = sorted((root(ordered[0]), root(key)))
            links[second] = first
    # Choosing the smallest linked key keeps identities independent of row order.
    return {media_id: ":".join(root(keys[0])) for media_id, keys in keys_by_id.items()}


def inspect(manifest: dict, repo_root: Path) -> dict:
    """Return integrity errors and independently derived coverage gaps."""
    _, scope_errors = manifest_scope(manifest)
    errors: list[str] = list(scope_errors)
    gaps: list[str] = []
    if manifest.get("schema_version") != 1:
        errors.append("unsupported schema_version")
    if set(manifest.get("required_tags", [])) != ALLOWED_TAGS:
        errors.append("required_tags must match the release coverage contract")
    if set(manifest.get("required_single_source_fps", [])) != REQUIRED_FPS:
        errors.append("required_single_source_fps must match the release coverage contract")
    if set(manifest.get("required_containers", [])) != REQUIRED_CONTAINERS:
        errors.append("required_containers must match the release coverage contract")
    if set(manifest.get("required_frame_rate_modes", [])) != REQUIRED_FRAME_RATE_MODES:
        errors.append("required_frame_rate_modes must match the release coverage contract")
    if manifest.get("minimum_verified_camera_models") != MINIMUM_CAMERA_MODELS:
        errors.append("minimum_verified_camera_models must match the release coverage contract")
    repo_root = repo_root.resolve()
    media_root = (repo_root / manifest.get("media_root", "")).resolve()
    if not media_root.is_relative_to(repo_root):
        return {"integrity_errors": ["media_root escapes repository"], "coverage_gaps": []}
    media = manifest.get("media", [])
    if not isinstance(media, list) or not media:
        return {"integrity_errors": ["media must be a nonempty list"], "coverage_gaps": []}

    calibration_dir = repo_root / "app/src/testFixtures/golden-mp4"
    calibration_hashes = {
        sha256_file(path)
        for path in calibration_dir.glob("*.mp4")
        if path.is_file()
    }
    seen_ids: set[str] = set()
    seen_hashes: set[str] = set()
    valid: list[dict] = []
    pilots: list[dict] = []
    for entry in media:
        if not isinstance(entry, dict):
            errors.append("media entry is not an object")
            continue
        media_id = entry.get("id", "<missing-id>")
        if not isinstance(media_id, str) or not media_id:
            errors.append("media entry id must be a nonempty string")
            continue
        filename = entry.get("file", "")
        if media_id in seen_ids:
            errors.append(f"{media_id}: duplicate id")
        seen_ids.add(media_id)
        if not isinstance(filename, str) or Path(filename).name != filename or not filename:
            errors.append(f"{media_id}: file must be a simple filename")
            continue
        path = (media_root / filename).resolve()
        if not path.is_relative_to(media_root) or not path.is_file():
            errors.append(f"{media_id}: local media missing or escapes media_root")
            continue
        if path.stat().st_size != entry.get("bytes"):
            errors.append(f"{media_id}: byte length differs from manifest")
            continue
        actual_hash = sha256_file(path)
        if actual_hash != entry.get("sha256"):
            errors.append(f"{media_id}: SHA-256 differs from manifest")
            continue
        if actual_hash in seen_hashes:
            errors.append(f"{media_id}: duplicate file content")
        seen_hashes.add(actual_hash)
        if actual_hash in calibration_hashes or entry.get("parent_sha256") in calibration_hashes:
            errors.append(f"{media_id}: overlaps a calibration Golden MP4")
        role = entry.get("role")
        if role not in {"holdout", "pilot"}:
            errors.append(f"{media_id}: role must be holdout or pilot")
        if role == "pilot" and not entry.get("pilot_note"):
            errors.append(f"{media_id}: pilot requires a reason")
        if not entry.get("source_page") or not entry.get("license") or \
                not isinstance(entry.get("parent_source"), str) or not entry["parent_source"]:
            errors.append(f"{media_id}: provenance or licence missing")
        if not isinstance(entry.get("tags"), list) or set(entry["tags"]) - ALLOWED_TAGS:
            errors.append(f"{media_id}: invalid tags")
        if entry.get("frame_rate_mode") == "vfr" and not entry.get("vfr_evidence"):
            errors.append(f"{media_id}: VFR needs measured timestamp evidence")
        if entry.get("frame_rate_mode") == "vfr":
            report_name = entry.get("timing_report_file")
            report_path = (repo_root / report_name).resolve() if isinstance(report_name, str) else None
            try:
                if report_path is None or not report_path.is_relative_to(repo_root) or \
                        not report_path.is_file() or sha256_file(report_path) != entry.get("timing_report_sha256"):
                    raise ValueError("timing report missing or changed")
                timing = json.loads(report_path.read_text(encoding="utf-8"))
                if timing.get("source_sha256") != actual_hash or \
                        timing.get("classification_rule") != "pairwise-cfr-rounding-one-tick-conservative-v2" or \
                        timing.get("measured_frame_rate_mode") != "vfr":
                    raise ValueError("timing report is not bound to a measured VFR source")
            except (OSError, ValueError, TypeError, AttributeError) as exc:
                errors.append(f"{media_id}: VFR evidence invalid: {exc}")
        if entry.get("camera_model") and not entry.get("camera_evidence"):
            errors.append(f"{media_id}: camera model needs provenance evidence")
        if role == "pilot":
            pilots.append(entry)
        elif role == "holdout":
            valid.append(entry)

    # Coverage for single-source products must come from clips long enough to
    # exercise their real product path; a 10 s DUALITY input cannot fill it.
    single = [item for item in valid if item.get("duration_s", 0) >= 15]
    dual = [item for item in valid if item.get("duration_s", 0) >= 6]
    parents = parent_identities(media)
    pilot_parents = {parents[item["id"]] for item in pilots}
    for item in valid:
        if parents[item["id"]] in pilot_parents:
            errors.append(f"{item['id']}: parent source has been exposed as a pilot")
    if len({parents[item["id"]] for item in single}) < 3:
        gaps.append("fewer than three independent single-source parents")
    if len({parents[item["id"]] for item in dual}) < 2:
        gaps.append("fewer than two independent DUALITY parents")
    tags = set().union(*(set(item.get("tags", [])) for item in single))
    for tag in sorted(ALLOWED_TAGS):
        if tag not in tags:
            gaps.append(f"single-source tag missing: {tag}")
    fps = {item.get("fps_bucket") for item in single}
    for bucket in sorted(REQUIRED_FPS):
        if bucket not in fps:
            gaps.append(f"single-source FPS missing: {bucket}")
    containers = {item.get("container") for item in single}
    for container in sorted(REQUIRED_CONTAINERS):
        if container not in containers:
            gaps.append(f"single-source container missing: {container}")
    modes = {item.get("frame_rate_mode") for item in single}
    for mode in sorted(REQUIRED_FRAME_RATE_MODES):
        if mode not in modes:
            gaps.append(f"single-source frame-rate mode missing: {mode}")
    models = {item.get("camera_model") for item in single if item.get("camera_model")}
    required_models = MINIMUM_CAMERA_MODELS
    if len(models) < required_models:
        gaps.append(f"verified camera models: {len(models)}/{required_models}")
    if manifest.get("state") != "sealed":
        gaps.append("holdout is not sealed")
    else:
        from quality_holdout_seal import verify_seal
        errors.extend(verify_seal(manifest, repo_root))

    return {
        "media_verified": len(valid),
        "pilot_media_verified": len(pilots),
        "single_source_eligible": len(single),
        "duality_eligible": len(dual),
        "integrity_errors": errors,
        "coverage_gaps": gaps,
        "corpus_ready": not errors and not gaps,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--repo-root", type=Path, default=REPO_ROOT)
    parser.add_argument("--allow-incomplete", action="store_true",
                        help="return zero when files are sound but coverage is unfinished")
    args = parser.parse_args()
    report = inspect(json.loads(args.manifest.read_text(encoding="utf-8")), args.repo_root)
    print(json.dumps(report, indent=2, ensure_ascii=False))
    if report["integrity_errors"]:
        return 2
    if report["coverage_gaps"] and not args.allow_incomplete:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
