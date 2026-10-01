"""Bind a blind corpus to the exact engine, criteria and APK before disclosure.

This is integrity evidence, not a signature, proof of human review or a detector
of undisclosed tuning. A disclosed parent still must be retired explicitly.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from quality_product_scope import manifest_scope

SHA = re.compile(r"[0-9a-f]{64}")
COMMIT = re.compile(r"[0-9a-f]{40}")
REQUIRED_FILES = (
    "app/src/main/java/com/example/autoedit/VeycadAutomaticEditor.kt",
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
    "docs/quality/README.md", "docs/quality/human-review-template.json",
)


def file_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def manifest_hash(manifest: dict) -> str:
    payload = {key: value for key, value in manifest.items() if key != "seal"}
    return hashlib.sha256(json.dumps(payload, sort_keys=True, ensure_ascii=False,
                                     separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def baseline_files(root: Path) -> dict[str, str]:
    root = root.resolve()
    paths = {root / name for name in REQUIRED_FILES}
    for name in ("app/src/main", "app/src/debug", "gradle"):
        paths.update(path for path in (root / name).rglob("*") if path.is_file())
    paths.update((root / "tools").glob("quality_*.py"))
    paths.update(root / name for name in ("app/build.gradle.kts", "gradlew", "gradlew.bat"))
    result = {}
    for path in sorted(paths):
        resolved = path.resolve()
        if not resolved.is_relative_to(root) or not resolved.is_file():
            raise ValueError(f"baseline file missing or outside repository: {path}")
        result[path.relative_to(root).as_posix()] = file_hash(resolved)
    return result


def make_seal(manifest: dict, root: Path, apk_file: str, engine_commit: str) -> dict:
    _, scope_errors = manifest_scope(manifest)
    if scope_errors:
        raise ValueError("; ".join(scope_errors))
    root = root.resolve()
    apk = (root / apk_file).resolve()
    if not COMMIT.fullmatch(engine_commit):
        raise ValueError("engine_commit must be a full Git commit hash")
    if not apk.is_relative_to(root) or not apk.is_file():
        raise ValueError("APK missing or outside repository")
    if manifest.get("state") != "sealed":
        raise ValueError("seal payload must have state=sealed")
    # Only NEW sealing checks known prior disclosures. Existing sealed series may
    # legitimately acquire their first results after sealing; verification must
    # not invalidate that historical fact merely because those results now exist.
    results = root / "artifacts/quality/runs"
    if results.is_dir():
        from quality_holdout_next import prepare, scan_results
        candidate = dict(manifest, state="candidate_not_release_ready")
        retired = prepare(candidate, scan_results(results, root), datetime.now(timezone.utc).isoformat())
        changed = [before["id"] for before, after in zip(manifest["media"], retired["media"])
                   if before.get("role") == "holdout" and after["role"] == "pilot"]
        if changed:
            raise ValueError("known exposed parents cannot enter a new blind seal: " + ", ".join(changed))
    return {"schema_version": 1, "sealed_at": datetime.now(timezone.utc).isoformat(),
            "manifest_sha256": manifest_hash(manifest), "engine_commit": engine_commit,
            "apk_file": apk.relative_to(root).as_posix(), "apk_sha256": file_hash(apk),
            "baseline_files": baseline_files(root)}


def verify_seal(manifest: dict, root: Path) -> list[str]:
    seal = manifest.get("seal")
    if not isinstance(seal, dict) or seal.get("schema_version") != 1:
        return ["sealed holdout requires a baseline seal"]
    _, scope_errors = manifest_scope(manifest)
    errors = list(scope_errors)
    try:
        sealed_at = datetime.fromisoformat(seal.get("sealed_at", ""))
        if sealed_at.tzinfo is None or sealed_at > datetime.now(timezone.utc):
            raise ValueError("missing timezone or future date")
    except (TypeError, ValueError):
        errors.append("holdout seal: invalid sealed_at")
    if not COMMIT.fullmatch(str(seal.get("engine_commit", ""))):
        errors.append("holdout seal: engine commit missing")
    if seal.get("manifest_sha256") != manifest_hash(manifest):
        errors.append("holdout seal: corpus or metadata changed after sealing")
    root = root.resolve()
    apk_name = seal.get("apk_file")
    if not isinstance(apk_name, str) or not apk_name:
        errors.append("holdout seal: APK path missing")
    else:
        apk = (root / apk_name).resolve()
        if not apk.is_relative_to(root) or not apk.is_file() or \
                not SHA.fullmatch(str(seal.get("apk_sha256", ""))) or \
                file_hash(apk) != seal.get("apk_sha256"):
            errors.append("holdout seal: APK missing or changed")
    try:
        if seal.get("baseline_files") != baseline_files(root):
            errors.append("holdout seal: engine, build inputs or quality criteria changed")
    except ValueError as exc:
        errors.append(f"holdout seal: {exc}")
    return errors


def review_after_seal(review: dict, seal: dict) -> bool:
    """Reject undated, future or already-disclosed acceptance records."""
    try:
        reviewed_at = datetime.fromisoformat(review.get("reviewed_at", ""))
        sealed_at = datetime.fromisoformat(seal.get("sealed_at", ""))
        return reviewed_at.tzinfo is not None and sealed_at.tzinfo is not None and \
            sealed_at <= reviewed_at <= datetime.now(timezone.utc)
    except (AttributeError, TypeError, ValueError):
        return False


def main() -> int:
    from quality_holdout import DEFAULT_MANIFEST, REPO_ROOT, inspect
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--repo-root", type=Path, default=REPO_ROOT)
    parser.add_argument("--apk", required=True, help="repository-relative APK path")
    parser.add_argument("--output", type=Path, required=True, help="new sealed manifest, never overwritten")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output already exists; preserve the previous seal")
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    report = inspect(manifest, args.repo_root)
    gaps = [gap for gap in report["coverage_gaps"] if gap != "holdout is not sealed"]
    if report["integrity_errors"] or gaps:
        print(json.dumps({"errors": report["integrity_errors"], "gaps": gaps}, indent=2))
        return 1
    manifest.pop("seal", None)
    manifest["state"] = "sealed"
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=args.repo_root,
                                     text=True).strip()
    try:
        manifest["seal"] = make_seal(manifest, args.repo_root, args.apk, commit)
    except ValueError as exc:
        print(json.dumps({"errors": [str(exc)]}, indent=2))
        return 2
    with args.output.open("x", encoding="utf-8") as destination:
        json.dump(manifest, destination, indent=2, ensure_ascii=False, allow_nan=False)
        destination.write("\n")
    print("Sealed manifest created; no review or quality acceptance implied.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
