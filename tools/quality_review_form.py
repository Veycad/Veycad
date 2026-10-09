"""Create an UNREVIEWED, output-bound human form; never supply human judgements."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path

from quality_audio_report import sha256
from quality_render_report import REVIEW_AREAS, STYLE_REVIEW_AREAS, parse_result


def prepare(result_path: Path, output_path: Path) -> dict:
    result = parse_result(result_path.read_text(encoding="utf-8"))
    recipe = result.get("recipe")
    if result.get("status") != "ok" or recipe not in STYLE_REVIEW_AREAS:
        raise ValueError("form requires a completed, recognised product export")
    graph = result.get("graph_generator", "")
    digest = result.get("output_sha256", "")
    if not graph.strip() or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("export report lacks grammar or output SHA-256")
    if sha256(output_path) != digest:
        raise ValueError("actual MP4 does not match export report SHA-256")
    # Acceptance may be false: a rejected export still needs an honest human review.
    # No pass flag, reviewer, date, playback confirmation or judgement is inferred.
    return {
        "output_sha256": digest, "recipe": recipe, "graph_generator": graph,
        "reviewer": "", "reviewed_at": "", "playback_1x": False,
        "playback_half": False, "checks": {area: False for area in REVIEW_AREAS},
        "style_checks": {area: False for area in STYLE_REVIEW_AREAS[recipe]},
        "blockers": [],
    }


def create(result_path: Path, output_path: Path, destination: Path) -> dict:
    form = prepare(result_path, output_path)
    with destination.open("x", encoding="utf-8") as target:
        json.dump(form, target, indent=2, ensure_ascii=False)
        target.write("\n")
    return form


def prepare_negative(result_path: Path) -> dict:
    """Bind a blank refusal review to exact run bytes, never infer human answers."""
    from quality_release_matrix import NEGATIVE_CODES
    payload = result_path.read_bytes()
    result = parse_result(payload.decode("utf-8"))
    recipe = result.get("recipe")
    code = result.get("rejection_code")
    if result.get("status") != "material_rejected" or recipe not in STYLE_REVIEW_AREAS or \
            code not in NEGATIVE_CODES:
        raise ValueError("negative form requires a recognised material rejection")
    if not result_path.name.endswith(".mp4.result") or result_path.with_suffix("").exists():
        raise ValueError("negative result must be paired with an absent MP4")
    source = result.get("source_sha256")
    secondary = result.get("secondary_source_sha256")
    if not re.fullmatch(r"[0-9a-f]{64}", source or "") or \
            (recipe == "DUALITY_LOOP" and not re.fullmatch(r"[0-9a-f]{64}", secondary or "")) or \
            (recipe != "DUALITY_LOOP" and secondary is not None) or \
            result.get("static_source") != "false" or result.get("render_source_sha256") != source:
        raise ValueError("negative report lacks exact, untransformed source identities")
    return {
        "recipe": recipe, "source_sha256": source, "secondary_source_sha256": secondary,
        "rejection_code": code, "result_sha256": hashlib.sha256(payload).hexdigest(),
        "reviewer": "", "reviewed_at": "", "material_case_confirmed": False,
        "message_specific": False,
    }


def create_negative(result_path: Path, destination: Path) -> dict:
    form = prepare_negative(result_path)
    with destination.open("x", encoding="utf-8") as target:
        json.dump(form, target, indent=2, ensure_ascii=False)
        target.write("\n")
    return form


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("result", type=Path)
    parser.add_argument("output_or_destination", type=Path)
    parser.add_argument("destination", type=Path, nargs="?",
                        help="NEW JSON form; existing review is never overwritten")
    parser.add_argument("--negative", action="store_true",
                        help="use RESULT NEW_REVIEW, without an output MP4")
    args = parser.parse_args()
    if args.negative and args.destination is not None or not args.negative and args.destination is None:
        parser.error("use RESULT OUTPUT NEW_REVIEW, or --negative RESULT NEW_REVIEW")
    try:
        form = create_negative(args.result, args.output_or_destination) if args.negative else \
            create(args.result, args.output_or_destination, args.destination)
    except (OSError, UnicodeError, ValueError) as exc:
        print(json.dumps({"created": False, "error": str(exc)}))
        return 1
    identity = {"result_sha256": form["result_sha256"]} if args.negative else \
        {"output_sha256": form["output_sha256"]}
    print(json.dumps({"created": True, "reviewed": False, "recipe": form["recipe"], **identity}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
