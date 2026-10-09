"""Fail-closed cross-product release matrix over sealed independent media.

The corpus check proves candidate inputs exist; this gate additionally requires
actual, source-bound exports and signed human reviews for every product.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

from quality_holdout import (DEFAULT_MANIFEST, REPO_ROOT, inspect as inspect_corpus,
                             parent_identities, sha256_file)
from quality_render_report import FRAME_US, assess, parse_result
from quality_product_scope import manifest_scope, validate_scope
from quality_review_identity import valid_review_identity


DEFAULT_MATRIX = REPO_ROOT / "docs/quality/release-matrix-active-candidate-20260927.json"
NEGATIVE_CODES = {"insufficient_duration", "insufficient_human_evidence",
                  "insufficient_motion", "insufficient_motion_evidence", "insufficient_distinct_moments"}
SHA256 = re.compile(r"[0-9a-f]{64}")
COMMIT = re.compile(r"[0-9a-f]{40}")
MUSIC_FILE = {"SIGMA": "leonid_reentry_phonk.m4a",
              "HEARTBEAT": "heartbeat_author.m4a",
              "FEAR_STROBE": "fear_strobe_author.m4a",
              "DUALITY_LOOP": "duality_loop_author.m4a"}


def inspect(matrix: dict, manifest: dict, repo_root: Path, ffmpeg: Path | None = None) -> dict:
    corpus = inspect_corpus(manifest, repo_root)
    errors: list[str] = list(corpus["integrity_errors"])
    gaps: list[str] = list(corpus["coverage_gaps"])
    media = {item["id"]: item for item in manifest.get("media", [])
             if isinstance(item, dict) and isinstance(item.get("id"), str)}
    parent_ids = parent_identities(list(media.values()))
    scope, scope_errors = manifest_scope(manifest)
    errors.extend(error for error in scope_errors if error not in errors)
    paused = [recipe for recipe, record in scope["products"].items()
              if record["state"] == "paused"]
    schema = matrix.get("schema_version")
    if type(schema) is not int or schema not in (1, 2):
        errors.append("invalid release matrix schema")
    elif schema == 1:
        if "product_scope" in matrix or "product_scope" in manifest:
            errors.append("explicit product scope requires matrix schema_version=2")
    else:
        matrix_scope_errors = validate_scope(matrix.get("product_scope"))
        errors.extend(matrix_scope_errors)
        if "product_scope" not in manifest or matrix.get("product_scope") != manifest.get("product_scope"):
            errors.append("matrix product scope differs from pre-disclosure manifest scope")
    cases = matrix.get("cases")
    if not isinstance(cases, list):
        errors.append("invalid release matrix cases")
        cases = []
    scope_binding_valid = not errors
    seen_ids: set[str] = set()
    seen_results: set[Path] = set()
    positive: dict[str, set[tuple[str, ...]]] = {recipe: set() for recipe in FRAME_US}
    duality_orders: dict[tuple[str, ...], set[tuple[str, ...]]] = {}
    negative: dict[str, set[str]] = {recipe: set() for recipe in FRAME_US}
    used_media: set[str] = set()
    valid_case_count = 0
    root = repo_root.resolve()

    def local_file(value: object, label: str) -> Path | None:
        if not isinstance(value, str) or not value:
            errors.append(f"{label}: path missing")
            return None
        path = (root / value).resolve()
        if not path.is_relative_to(root) or not path.is_file():
            errors.append(f"{label}: file missing or outside repository")
            return None
        return path

    for case in cases:
        if not isinstance(case, dict):
            errors.append("case is not an object")
            continue
        case_id = case.get("id", "<missing-id>")
        if not isinstance(case_id, str) or not case_id or case_id in seen_ids:
            errors.append(f"{case_id}: missing or duplicate id")
            continue
        seen_ids.add(case_id)
        recipe = case.get("recipe")
        if recipe not in FRAME_US:
            errors.append(f"{case_id}: unknown recipe")
            continue
        if recipe in paused:
            errors.append(f"{case_id}: paused product cannot supply a new release case")
            continue
        ids = case.get("media_ids")
        expected_count = 2 if recipe == "DUALITY_LOOP" else 1
        if not isinstance(ids, list) or len(ids) != expected_count or \
                not all(isinstance(item, str) for item in ids) or len(set(ids)) != len(ids):
            errors.append(f"{case_id}: wrong source count or repeated source")
            continue
        if any(item not in media or media[item].get("role") != "holdout" for item in ids):
            errors.append(f"{case_id}: unknown or exposed pilot source")
            continue
        if not COMMIT.fullmatch(str(case.get("engine_commit", ""))) or not \
                SHA256.fullmatch(str(case.get("apk_sha256", ""))) or not \
                case.get("device_model") or not isinstance(case.get("android_api"), int):
            errors.append(f"{case_id}: build or device identity missing")
            continue
        if manifest.get("state") == "sealed":
            seal = manifest.get("seal", {})
            if not isinstance(seal, dict) or \
                    case.get("engine_commit") != seal.get("engine_commit") or \
                    case.get("apk_sha256") != seal.get("apk_sha256"):
                errors.append(f"{case_id}: build differs from pre-disclosure holdout seal")
                continue
        result_path = local_file(case.get("result_file"), f"{case_id} result")
        if result_path is None:
            continue
        if result_path in seen_results:
            errors.append(f"{case_id}: result reused in another case")
            continue
        seen_results.add(result_path)
        try:
            result_bytes = result_path.read_bytes()
            result_sha256 = hashlib.sha256(result_bytes).hexdigest()
            result = parse_result(result_bytes.decode("utf-8"))
        except (OSError, UnicodeError, ValueError) as exc:
            errors.append(f"{case_id}: unreadable result: {exc}")
            continue
        hashes = [result.get("source_sha256"), result.get("secondary_source_sha256")]
        if hashes[:expected_count] != [media[item]["sha256"] for item in ids] or \
                (expected_count == 1 and hashes[1] is not None):
            errors.append(f"{case_id}: decoded run is not bound to declared source hashes")
            continue
        if result.get("static_source") != "false" or \
                result.get("render_source_sha256") != result.get("source_sha256"):
            errors.append(f"{case_id}: transformed or unverified render source cannot supply independent case")
            continue
        if result.get("recipe") != recipe:
            errors.append(f"{case_id}: wrong product in result")
            continue
        if result.get("runtime_apk_sha256") != case.get("apk_sha256") or \
                result.get("runtime_split_apk_count") != "0" or \
                result.get("runtime_device_model") != case.get("device_model") or \
                result.get("runtime_android_api") != str(case.get("android_api")):
            errors.append(f"{case_id}: running APK/device differs from declared build or is unverified")
            continue
        authored_music = root / "app/src/main/res/raw" / MUSIC_FILE[recipe]
        if not authored_music.is_file() or result.get("music_sha256") != sha256_file(authored_music):
            errors.append(f"{case_id}: authored score hash missing or mismatched")
            continue
        review_path = local_file(case.get("review_file"), f"{case_id} review")
        if review_path is None:
            continue
        try:
            review = json.loads(review_path.read_text(encoding="utf-8"))
        except (UnicodeError, ValueError) as exc:
            errors.append(f"{case_id}: unreadable review: {exc}")
            continue
        if not isinstance(review, dict):
            errors.append(f"{case_id}: review must be an object")
            continue
        if not valid_review_identity(review):
            errors.append(f"{case_id}: human review identity/date missing or invalid")
            continue
        if manifest.get("state") == "sealed":
            from quality_holdout_seal import review_after_seal
            if not review_after_seal(review, manifest.get("seal", {})):
                errors.append(f"{case_id}: review predates seal or has an invalid/future date")
                continue
        if case.get("outcome") == "positive":
            parents = tuple(sorted(parent_ids[item] for item in ids))
            if recipe == "DUALITY_LOOP" and len(set(parents)) != 2:
                errors.append(f"{case_id}: DUALITY sources share one parent recording")
                continue
            output = local_file(case.get("output_file"), f"{case_id} output")
            if output is not None and result_path != Path(str(output) + ".result"):
                errors.append(f"{case_id}: result is not paired with this MP4")
                continue
            if output is None or not SHA256.fullmatch(str(case.get("output_sha256", ""))) or \
                    sha256_file(output) != case.get("output_sha256") or \
                    result.get("output_sha256") != case.get("output_sha256"):
                errors.append(f"{case_id}: output hash missing or mismatched")
                continue
            inspector_path = local_file(case.get("inspector_file"), f"{case_id} inspector")
            if inspector_path is None:
                continue
            from quality_inspector_report import inspect_saved_inspector
            inspector_issues = inspect_saved_inspector(inspector_path, case.get("inspector_sha256"),
                                                       output, result)
            if inspector_issues:
                errors.extend(f"{case_id}: {issue}" for issue in inspector_issues)
                continue
            audio_path = local_file(case.get("audio_report_file"), f"{case_id} audio report")
            if audio_path is None:
                continue
            if ffmpeg is None:
                errors.append(f"{case_id}: independent audio decoder required for remeasurement")
                continue
            try:
                from quality_audio_report import measure
                saved_audio = json.loads(audio_path.read_text(encoding="utf-8"))
                measured_audio = measure(output, ffmpeg.resolve(), recipe)
                if saved_audio != measured_audio:
                    errors.append(f"{case_id}: saved audio report differs from actual MP4 remeasurement")
                    continue
            except (OSError, UnicodeError, ValueError, subprocess.SubprocessError) as exc:
                errors.append(f"{case_id}: independent audio measurement unavailable: {exc}")
                continue
            report = assess(result, recipe, review, measured_audio)
            if not report["machine_and_human_pass"]:
                errors.append(f"{case_id}: positive run failed: {'; '.join(report['issues'])}")
                continue
            positive[recipe].add(parents)
            if recipe == "DUALITY_LOOP":
                duality_orders.setdefault(tuple(sorted(ids)), set()).add(tuple(ids))
        elif case.get("outcome") == "negative":
            code = case.get("expected_rejection_code")
            if not result_path.name.endswith(".mp4.result") or \
                    result_path.with_suffix("").exists():
                errors.append(f"{case_id}: rejected material has a rendered MP4 or unpaired result")
                continue
            minimum_ms = 6_000 if recipe == "DUALITY_LOOP" else 15_000
            if code == "insufficient_duration" and all(
                    media[item].get("duration_s", 0) * 1_000 >= minimum_ms for item in ids):
                errors.append(f"{case_id}: declared input is not too short")
                continue
            if code == "insufficient_human_evidence" and not all(
                    "no_people" in media[item].get("tags", []) for item in ids):
                errors.append(f"{case_id}: input is not tagged no_people")
                continue
            if code not in NEGATIVE_CODES or result.get("status") != "material_rejected" or \
                    result.get("rejection_code") != code or case.get("output_file"):
                errors.append(f"{case_id}: negative case lacks its exact material rejection")
                continue
            # A human refusal confirmation cannot be carried from another input,
            # product or message. Hash the SAME bytes that supplied all run fields.
            binding = {"recipe": recipe, "source_sha256": hashes[0],
                       "secondary_source_sha256": hashes[1], "rejection_code": code,
                       "result_sha256": result_sha256}
            if any(key not in review or review[key] != value for key, value in binding.items()):
                errors.append(f"{case_id}: negative review not bound to exact result/product/sources/rejection")
                continue
            if review.get("material_case_confirmed") is not True or \
                    review.get("message_specific") is not True:
                errors.append(f"{case_id}: negative material and message review missing")
                continue
            negative[recipe].add(code)
        else:
            errors.append(f"{case_id}: outcome must be positive or negative")
            continue
        used_media.update(ids)
        valid_case_count += 1

    product_gaps: dict[str, list[str]] = {recipe: [] for recipe in FRAME_US}
    for recipe in FRAME_US:
        if len(positive[recipe]) < 3:
            product_gaps[recipe].append(f"{recipe}: {len(positive[recipe])}/3 independent positive parents or pairs")
        for code in ("insufficient_duration", "insufficient_human_evidence"):
            if code not in negative[recipe]:
                product_gaps[recipe].append(f"{recipe}: negative {code} not verified")
    if "insufficient_motion" not in negative["FEAR_STROBE"]:
        product_gaps["FEAR_STROBE"].append("FEAR_STROBE: negative insufficient_motion not verified")
    if "insufficient_distinct_moments" not in negative["FEAR_STROBE"]:
        product_gaps["FEAR_STROBE"].append("FEAR_STROBE: negative insufficient_distinct_moments not verified")
    for pair in positive["DUALITY_LOOP"]:
        orders = [value for key, value in duality_orders.items()
                  if tuple(sorted(parent_ids[item] for item in key)) == pair]
        if not any(len(order_set) == 2 for order_set in orders):
            product_gaps["DUALITY_LOOP"].append(f"DUALITY_LOOP: both import orders missing for pair {pair}")
    used_single = [media[item] for item in used_media if media[item].get("duration_s", 0) >= 15]
    used_tags = set().union(*(set(item.get("tags", [])) for item in used_single))
    for tag in sorted(manifest.get("required_tags", [])):
        if tag not in used_tags:
            gaps.append(f"unexercised material tag: {tag}")
    for field, required in (
        ("fps_bucket", (24, 25, 30, 60)),
        ("container", ("mp4", "webm", "mov")),
        ("frame_rate_mode", ("cfr", "vfr")),
    ):
        seen = {item.get(field) for item in used_single}
        for value in required:
            if value not in seen:
                gaps.append(f"unexercised {field}: {value}")
    models = {item.get("camera_model") for item in used_single if item.get("camera_model")}
    if len(models) < 2:
        gaps.append(f"unexercised verified camera models: {len(models)}/2")
    # Shared corpus/exercised coverage is identical for full and active gates.
    # Pausing Sigma defers its original requirements, never marks them passed.
    shared_gaps = list(gaps)
    active_gaps = shared_gaps + [gap for recipe in FRAME_US if recipe not in paused
                                for gap in product_gaps[recipe]]
    gaps.extend(gap for recipe in FRAME_US for gap in product_gaps[recipe])
    gaps.extend(f"{recipe}: paused_not_release_ready" for recipe in paused)
    products = {
        recipe: {
            "status": "paused_not_release_ready" if recipe in paused else
                      "release_ready" if not errors and not shared_gaps and not product_gaps[recipe]
                      else "not_release_ready",
            "requirements_passed": recipe not in paused and not errors and not shared_gaps
                                   and not product_gaps[recipe],
            "independent_positive_count": len(positive[recipe]),
            "verified_negative_codes": sorted(negative[recipe]),
            "gaps": product_gaps[recipe],
            "pause_basis": scope["products"][recipe] if recipe in paused else None,
        } for recipe in FRAME_US
    }
    return {"errors": errors, "gaps": gaps, "verified_case_count": valid_case_count,
            "products": products, "paused_products": paused, "active_gaps": active_gaps,
            "scope_bound_before_disclosure": manifest.get("state") == "sealed"
                and scope_binding_valid,
            "active_products_ready": manifest.get("state") == "sealed" and not errors and not active_gaps,
            "ready": not paused and not errors and not gaps}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, default=DEFAULT_MATRIX)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--repo-root", type=Path, default=REPO_ROOT)
    parser.add_argument("--ffmpeg", type=Path, help="required to verify positive audio cases")
    args = parser.parse_args()
    report = inspect(json.loads(args.matrix.read_text(encoding="utf-8")),
                     json.loads(args.manifest.read_text(encoding="utf-8")), args.repo_root, args.ffmpeg)
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0 if report["ready"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
