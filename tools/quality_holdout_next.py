"""Prepare a NEW candidate cohort, retiring known exposed parents without resealing them.

Prior manifests/results/media are read-only. Absence from local runner results is
not proof of blindness: provenance/duplicate/human exposure review remains required.
"""
from __future__ import annotations

import argparse
import copy
import datetime as dt
import hashlib
import json
import re
from pathlib import Path

from quality_holdout import DEFAULT_MANIFEST, REPO_ROOT, inspect


SOURCE_KEYS = {"source_sha256", "secondary_source_sha256", "render_source_sha256"}


def scan_results(directory: Path, repo_root: Path) -> dict[str, list[dict]]:
    """Bind positive/negative source disclosure to immutable local result bytes."""
    directory = directory.resolve()
    repo_root = repo_root.resolve()
    if not directory.is_relative_to(repo_root) or not directory.is_dir():
        raise ValueError("result directory missing or outside repository")
    exposures: dict[str, list[dict]] = {}
    for path in sorted(directory.glob("*.result")):
        if not path.resolve().is_relative_to(repo_root):
            raise ValueError("result file escapes repository")
        payload = path.read_bytes()
        sources = set()
        for line in payload.decode("utf-8").splitlines():
            key, separator, value = line.partition("=")
            if separator and key in SOURCE_KEYS:
                if not re.fullmatch("[0-9a-f]{64}", value):
                    raise ValueError(f"invalid source identity in {path.name}")
                sources.add(value)
        if sources:
            evidence = {"result_file": path.relative_to(repo_root).as_posix(),
                        "result_sha256": hashlib.sha256(payload).hexdigest()}
            for source in sources:
                exposures.setdefault(source, []).append(evidence)
    return exposures


def prepare(manifest: dict, exposures: dict[str, list[dict]], generated_at: str) -> dict:
    if manifest.get("schema_version") != 1 or manifest.get("state") == "sealed":
        raise ValueError("use an unsealed source manifest; never rewrite a sealed cohort")
    media = manifest.get("media")
    if not isinstance(media, list) or not media:
        raise ValueError("media must be a nonempty list")
    candidate = copy.deepcopy(manifest)
    candidate["state"] = "candidate_not_release_ready"
    candidate["updated_on"] = generated_at[:10]
    entries = candidate["media"]
    identities = []
    for entry in entries:
        if entry.get("role") not in {"holdout", "pilot"}:
            raise ValueError("unsupported source role")
        keys = {("hash", value) for value in
                (entry.get("sha256"), entry.get("parent_sha256")) if value}
        if entry.get("parent_source"):
            keys.add(("parent", entry["parent_source"]))
        identities.append(keys)
    unseen = set(range(len(entries)))
    retired = []
    while unseen:
        component = {min(unseen)}
        keys = set(identities[next(iter(component))])
        while True:
            linked = {i for i in unseen if identities[i] & keys}
            if linked <= component:
                break
            component |= linked
            keys.update(key for i in linked for key in identities[i])
        unseen -= component
        explicit_pilots = [entries[i]["id"] for i in sorted(component) if entries[i]["role"] == "pilot"]
        evidence_by_path = {}
        exposed_ids = []
        for i in sorted(component):
            entry = entries[i]
            evidence = [item for key in (entry.get("sha256"), entry.get("parent_sha256"))
                        for item in exposures.get(key, [])]
            if evidence:
                exposed_ids.append(entry["id"])
                for item in evidence:
                    path = item["result_file"]
                    if path in evidence_by_path and evidence_by_path[path] != item:
                        raise ValueError("conflicting result identity")
                    evidence_by_path[path] = item
        if not explicit_pilots and not exposed_ids:
            continue
        evidence = [evidence_by_path[path] for path in sorted(evidence_by_path)]
        for i in sorted(component):
            entry = entries[i]
            entry["role"] = "pilot"
            if not entry.get("pilot_note"):
                entry["pilot_note"] = (
                    "Retired from the next blind cohort: shared parent with prior pilots " +
                    ", ".join(explicit_pilots + exposed_ids) + ". See cohort_retirement evidence.")
            retired.append(entry["id"])
        candidate.setdefault("cohort_retirement", []).append({
            "media_ids": [entries[i]["id"] for i in sorted(component)],
            "prior_pilot_ids": explicit_pilots, "exposed_media_ids": exposed_ids,
            "result_evidence": evidence})
    candidate["next_cohort_preparation"] = {
        "prepared_at": generated_at, "retired_media_ids": sorted(retired),
        "scope": "Known local runner source identities and parent links, not proof of no prior exposure",
        "release_ready": False,
        "remaining_review": "Provenance/near-duplicate/exposure review, coverage, new engine seal, all product machine and human acceptance"}
    return candidate


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--results-dir", type=Path, default=REPO_ROOT / "artifacts/quality/runs")
    parser.add_argument("--repo-root", type=Path, default=REPO_ROOT)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = args.repo_root.resolve()
    output = args.output.resolve()
    if not output.is_relative_to(root) or output.exists():
        parser.error("output must be a new repository file; no overwrite")
    source_manifest_bytes = args.source_manifest.read_bytes()
    manifest = json.loads(source_manifest_bytes.decode("utf-8"))
    source_report = inspect(manifest, root)
    if source_report["integrity_errors"]:
        parser.error("source manifest integrity errors: " + "; ".join(source_report["integrity_errors"]))
    candidate = prepare(manifest, scan_results(args.results_dir, root), dt.datetime.now(dt.timezone.utc).isoformat())
    candidate["next_cohort_preparation"]["source_manifest_sha256"] = hashlib.sha256(source_manifest_bytes).hexdigest()
    report = inspect(candidate, root)
    if report["integrity_errors"]:
        parser.error("prepared manifest integrity errors: " + "; ".join(report["integrity_errors"]))
    with output.open("x", encoding="utf-8") as handle:
        json.dump(candidate, handle, indent=2, ensure_ascii=False)
        handle.write("\n")
    print(json.dumps({"output": output.relative_to(root).as_posix(), **report}, indent=2))
    return 0  # Preparation is allowed to be incomplete; this is NEVER a release gate.


if __name__ == "__main__":
    raise SystemExit(main())
