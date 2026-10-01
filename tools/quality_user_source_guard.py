"""Read-only preflight for this project's user-only montage QA workflow.

This is not a production-app restriction, a runtime export lock, or a quality
acceptance gate. It checks the current bytes before a separately launched run.
Renaming/copying an unchanged approved original does not change its identity.
Adding authorization requires explicit owner provenance, not a public license.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from types import MappingProxyType


KNOWN_RECIPES = frozenset({"SIGMA", "HEARTBEAT", "FEAR_STROBE", "DUALITY_LOOP"})
RECIPE_ALIASES = MappingProxyType({"DUALITY": "DUALITY_LOOP"})
ALL_STYLE_AUTHORIZATION = (
    "User explicitly authorized the same two DUALITY originals for the other "
    "styles, alternating them: 'Используй те же что и для Duality, чередуй их'."
)
USER_MONTAGE_SOURCES = MappingProxyType({
    "8e65b237e48e8fd4b3d50079c85c64034fb592f5881a389deaa9485dc43aafca": {
        "owner_file": "19348088228456.mp4",
        "owner_original": r"C:\Users\rexar\Downloads\19348088228456.mp4",
        "workspace_copy": "artifacts/quality/user-sources/19348088228456.mp4",
        "provenance": "User attached the original specifically for DUALITY.",
        "authorization_update": ALL_STYLE_AUTHORIZATION,
        "recipes": ("SIGMA", "HEARTBEAT", "FEAR_STROBE", "DUALITY_LOOP"),
    },
    "81629968d2a0b41b355ec729d900f9b1cd2aec244a543f785796e5aa55d616b2": {
        "owner_file": "19348085803624.mp4",
        "owner_original": r"C:\Users\rexar\Downloads\19348085803624.mp4",
        "workspace_copy": "artifacts/quality/user-sources/19348085803624.mp4",
        "provenance": "User attached the original specifically for DUALITY.",
        "authorization_update": ALL_STYLE_AUTHORIZATION,
        "recipes": ("SIGMA", "HEARTBEAT", "FEAR_STROBE", "DUALITY_LOOP"),
    },
    "0e2d39036a6eeb520196905a04d6d4fa1385a09e80124d98f3c4a3faf48e9c23": {
        "owner_file": "IMG_2249.MOV",
        "owner_original": r"C:\Users\rexar\Downloads\IMG_2249.MOV",
        "workspace_copy": "artifacts/quality/user-sources/IMG_2249.MOV",
        "provenance": (
            "User attached this additional test original: 'Вот еще тестовое видео, "
            "тут я в полный рост'. Owner subsequently confirmed: 'Это моя отдельная "
            "новая съемка, снята на Iphone 16 Pro'. This is owner provenance, not EXIF."
        ),
        "authorization_update": (
            "User supplied the separate new recording for application testing. "
            "Authorization covers active products only; Sigma remains paused. "
            "This preflight does not imply a sealed corpus or quality acceptance."
        ),
        "recipes": ("HEARTBEAT", "FEAR_STROBE", "DUALITY_LOOP"),
    },
})
RENDERER_ONLY_FIXTURES = frozenset({
    "04ad6d45bf28ca7ea46bae49d46595f947a38d9bbf0d7811b5d91874caa342ab",
    "2fea69fde1e26702e07bd04a05468993b397601eb64555cb784b5910699809fd",
    "e0bab7b5c6063fcd4054eb05922717acc213c1cddb5e5b12442d4ff27740ee51",
})


def hash_file(path: Path) -> str:
    """Hash actual file bytes and reject an observable concurrent modification."""
    before = path.stat()
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    after = path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise ValueError(f"source changed during preflight: {path}")
    return digest.hexdigest()


def preflight(recipe: str, sources: list[str | Path],
              previous_source_sha256: str | None = None) -> dict:
    """Authorize ordered bytes; optionally check single-source run alternation.

    Prior-run identity must come from the caller's actual run record. This
    helper does not infer prior runs or keep mutable history. DUALITY remains
    two-source; single-source runs must differ from the supplied previous
    approved original. Existing A/B alternation is unchanged.
    """
    canonical = RECIPE_ALIASES.get(recipe, recipe)
    if canonical not in KNOWN_RECIPES:
        raise ValueError(f"unknown montage recipe: {recipe}")
    expected_count = 2 if canonical == "DUALITY_LOOP" else 1
    if len(sources) != expected_count:
        raise ValueError(f"{canonical} requires exactly {expected_count} source(s)")
    if previous_source_sha256 is not None:
        if canonical == "DUALITY_LOOP":
            raise ValueError("single-source run alternation is not a DUALITY ordering check")
        if previous_source_sha256 not in USER_MONTAGE_SOURCES:
            raise ValueError("previous source identity has no user montage authorization")
        if canonical not in USER_MONTAGE_SOURCES[previous_source_sha256]["recipes"]:
            raise ValueError(f"previous source is not authorized for {canonical}")
    identities = []
    seen_hashes = set()
    for source in sources:
        try:
            path = Path(source).resolve(strict=True)
        except (OSError, RuntimeError) as error:
            raise ValueError(f"source is unavailable: {source}") from error
        if not path.is_file():
            raise ValueError(f"source is not a regular file: {path}")
        try:
            digest = hash_file(path)
        except OSError as error:
            raise ValueError(f"source cannot be read: {path}") from error
        if digest in RENDERER_ONLY_FIXTURES:
            raise ValueError(f"renderer-validation fixture is not a montage source: {path}")
        authorization = USER_MONTAGE_SOURCES.get(digest)
        if authorization is None:
            raise ValueError(f"source bytes have no user montage authorization: {path}")
        if canonical not in authorization["recipes"]:
            raise ValueError(f"source is not authorized for {canonical}: {path}")
        if digest in seen_hashes:
            raise ValueError("DUALITY requires two distinct approved source identities")
        seen_hashes.add(digest)
        identities.append({
            "path": str(path),
            "sha256": digest,
            "owner_file": authorization["owner_file"],
            "provenance": authorization["provenance"],
            "authorization_update": authorization["authorization_update"],
        })
    if previous_source_sha256 == identities[0]["sha256"]:
        raise ValueError("single-source runs must alternate approved originals")
    return {
        "method": "user-only-montage-source-preflight-v1",
        "scope": "local_qa_only",
        "recipe": canonical,
        "ordered_sources": identities,
        "source_authorization_passed": True,
        "alternation": (
            "two_source_order_preserved" if canonical == "DUALITY_LOOP" else
            "checked_against_supplied_prior_run" if previous_source_sha256 is not None else
            "not_checked_first_or_unspecified_run"
        ),
        "previous_source_sha256": previous_source_sha256,
        "quality_acceptance": "not_assessed",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--recipe", required=True)
    parser.add_argument("--source", action="append", required=True,
                        help="Ordered original source path; repeat twice for DUALITY.")
    parser.add_argument("--previous-source-sha256",
                        help="Actual previous single-source run identity; current must differ.")
    args = parser.parse_args()
    try:
        result = preflight(args.recipe, args.source, args.previous_source_sha256)
    except ValueError as error:
        parser.exit(2, f"Source preflight rejected: {error}\n")
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
