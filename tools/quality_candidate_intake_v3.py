"""Exclusive source-only intake of six prospective related-action candidates.

No product inference, director, edit, holdout promotion, seal or human acceptance.
Published identities come from Commons file pages/imageinfo inspected 2026-09-27.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.error
import urllib.request

from quality_source_timing import measure

ROOT = Path(__file__).resolve().parents[1]
MAX_BYTES = 250_000_000
SOURCES = (
    dict(id="v3-fabiola-fan", file="fabiola_fan.webm", title="Coregrafía con abanico.webm",
         author="Fabiola Mastache", parent="Fabiola Mastache ITESM Mexico City choreography session2017-04-21",
         date="2017-04-21", license="CC BY-SA 4.0", revision=884126517,
         size=24498551, sha1="82897d4b53c1fbc10d21f80fc051e3d344d778bf", duration=96.041,
         url="https://upload.wikimedia.org/wikipedia/commons/2/28/Coregraf%C3%ADa_con_abanico.webm",
         page="https://commons.wikimedia.org/wiki/File:Coregraf%C3%ADa_con_abanico.webm"),
    dict(id="v3-fabiola-veil", file="fabiola_veil.webm", title="Coreografía con velo.webm",
         author="Fabiola Mastache", parent="Fabiola Mastache ITESM Mexico City choreography session2017-04-21",
         date="2017-04-21", license="CC BY-SA 4.0", revision=868352113,
         size=7316524, sha1="038ecb3028c87a02d97b84e8f909c7fa86bc3f5c", duration=26.296,
         url="https://upload.wikimedia.org/wikipedia/commons/5/52/Coreograf%C3%ADa_con_velo.webm",
         page="https://commons.wikimedia.org/wiki/File:Coreograf%C3%ADa_con_velo.webm"),
    dict(id="v3-r16-steve", file="r16_steve.webm", title="B-boy Steve from Knucklehead Zoo.webm",
         author="Gbern3", parent="Gbern3 R16 Korea2014 cypher session2014-07-06",
         date="2014-07-06", license="CC BY-SA 3.0", revision=809847354,
         size=23286021, sha1="79112d67452713078ba412376d6c8ef3c7a166a6", duration=21.435,
         url="https://upload.wikimedia.org/wikipedia/commons/0/0f/B-boy_Steve_from_Knucklehead_Zoo.webm",
         page="https://commons.wikimedia.org/wiki/File:B-boy_Steve_from_Knucklehead_Zoo.webm"),
    dict(id="v3-r16-chris", file="r16_chris.webm", title="B-boy Chris 'Cristyle' Gatdula R16 freestyle.webm",
         author="Gbern3", parent="Gbern3 R16 Korea2014 cypher session2014-07-06",
         date="2014-07-06", license="CC BY-SA 3.0", revision=809847351,
         size=33089447, sha1="25c0101e6c7627f75f475cc034ebf8673d427b38", duration=20.857,
         url="https://upload.wikimedia.org/wikipedia/commons/2/25/B-boy_Chris_%27Cristyle%27_Gatdula_R16_freestyle.webm",
         page="https://commons.wikimedia.org/wiki/File:B-boy_Chris_%27Cristyle%27_Gatdula_R16_freestyle.webm"),
    dict(id="v3-uae-01", file="uae_01.webm", title="Belly dancer UAE 2012 01.webm",
         author="Aumars", parent="Aumars UAE belly dance series2012-04-07 (event/takes unconfirmed)",
         date="2012-04-07", license="CC BY-SA 4.0", revision=820732006,
         size=18453161, sha1="92e0a0f8a5d463a237f8bc44e835a57a93e7ae18", duration=67.619,
         url="https://upload.wikimedia.org/wikipedia/commons/8/8b/Belly_dancer_UAE_2012_01.webm",
         page="https://commons.wikimedia.org/wiki/File:Belly_dancer_UAE_2012_01.webm"),
    dict(id="v3-uae-02", file="uae_02.webm", title="Belly dancer UAE 2012 02.webm",
         author="Aumars", parent="Aumars UAE belly dance series2012-04-07 (event/takes unconfirmed)",
         date="2012-04-07", license="CC BY-SA 4.0", revision=820732010,
         size=48062236, sha1="7ea26a9f91a8392e67fad664230a4e869ceb57e4", duration=176.803,
         url="https://upload.wikimedia.org/wikipedia/commons/5/5f/Belly_dancer_UAE_2012_02.webm",
         page="https://commons.wikimedia.org/wiki/File:Belly_dancer_UAE_2012_02.webm"),
)


def identities(path: Path) -> dict:
    digests = {name: hashlib.new(name) for name in ("sha1", "sha256")}
    size = 0
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            size += len(chunk)
            for digest in digests.values():
                digest.update(chunk)
    return dict(bytes=size, **{name: digest.hexdigest() for name, digest in digests.items()})


def write_json(path: Path, payload: dict) -> None:
    with path.open("x", encoding="utf-8") as stream:
        json.dump(payload, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")


def download(source: dict, directory: Path) -> dict:
    target = directory / source["file"]
    partial = target.with_suffix(target.suffix + ".download-part")
    if target.exists() or partial.exists():
        raise ValueError(f"existing download target; never overwrite: {target}")
    request = urllib.request.Request(source["url"], headers={
        "User-Agent": "AutoEdit-local-QA-source-intake/1.0 (six licensed candidate originals)"})
    try:
        response = urllib.request.urlopen(request, timeout=60)
    except urllib.error.HTTPError as error:
        print(json.dumps(dict(step="http_download_refused", id=source["id"], code=error.code,
                              retry_after=error.headers.get("Retry-After"))), flush=True)
        raise
    with response, partial.open("xb") as destination:
        declared = response.headers.get("Content-Length")
        if declared is not None and int(declared) != source["size"]:
            raise ValueError(f"publisher Content-Length changed: {source['id']}")
        size = 0
        while chunk := response.read(1024 * 1024):
            size += len(chunk)
            if size > source["size"]:
                raise ValueError(f"download exceeded expected size: {source['id']}")
            destination.write(chunk)
    found = identities(partial)
    if found["bytes"] != source["size"] or found["sha1"] != source["sha1"]:
        raise ValueError(f"download identity mismatch; partial preserved: {source['id']}")
    partial.rename(target)
    return found


def analyze(source: dict, directory: Path, ffmpeg: Path) -> dict:
    target = directory / source["file"]
    found = identities(target)
    if found["bytes"] != source["size"] or found["sha1"] != source["sha1"]:
        raise ValueError(f"source identity mismatch: {source['id']}")
    timing = measure(target, ffmpeg)
    timing_file = directory / (target.stem + ".timing.json")
    write_json(timing_file, timing)
    command = [str(ffmpeg), "-hide_banner", "-nostdin", "-xerror", "-threads", "1",
               "-i", str(target), "-map", "0:v:0", "-an", "-fps_mode", "passthrough",
               "-progress", "pipe:1", "-f", "null", "-"]
    decoded = subprocess.run(command, capture_output=True, text=True, timeout=300)
    progress = dict(line.split("=", 1) for line in decoded.stdout.splitlines() if "=" in line)
    full_decode = decoded.returncode == 0 and progress.get("progress") == "end" and \
        int(progress.get("frame", "0")) > 0
    if not full_decode:
        raise ValueError(f"full video decode failed: {source['id']} ({decoded.returncode}): {decoded.stderr[-2000:]}")
    contact = directory / (target.stem + "_source-contact.jpg")
    # Sparse source-only illustration, not exact-PTS witnesses or artistic acceptance.
    # DAR is honoured before square pixels so anamorphic publishers are not squeezed.
    contact_filter = "fps=1/8,scale=240:trunc(240/dar/2)*2:flags=lanczos,setsar=1,tile=5x5:padding=4:margin=4"
    contact_command = [str(ffmpeg), "-hide_banner", "-v", "error", "-nostdin", "-n",
                       "-threads", "1", "-i", str(target), "-map", "0:v:0", "-an",
                       "-vf", contact_filter, "-frames:v", "1", "-q:v", "3", str(contact)]
    subprocess.run(contact_command, capture_output=True, text=True, timeout=300, check=True)
    if identities(target) != found:
        raise ValueError("source changed during read-only analysis")
    return dict(id=source["id"], role="prospective_candidate_not_holdout",
                file=target.relative_to(ROOT).as_posix(), published=source,
                local_identity=found, source_timing_file=timing_file.relative_to(ROOT).as_posix(),
                timing=timing, full_video_decode_passed=True,
                decoded_frames=int(progress["frame"]), decode_command=command,
                decode_input_metadata=decoded.stderr,
                source_contact_file=contact.relative_to(ROOT).as_posix(),
                source_contact_rule="fps=1/8, DAR-correct square pixels, first25samples/blank tile padding; approximate sampling, not exactPTS",
                product_inference_run=False, director_run=False, montage_run=False,
                confirmed_positive=False, camera_model=None, lighting_condition="unreviewed",
                duality_relationship="unknown_pending_source_review" if source["id"].startswith("v3-uae") else
                    "publisher-linked candidate; independent takes/musical roles pending source review")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", type=Path, required=True)
    parser.add_argument("--analyze-only", action="store_true", help="use already verified original files; never redownload")
    parser.add_argument("--resume", action="store_true", help="verify completed original downloads and fetch only absent files")
    parser.add_argument("--source-id", action="append", help="source-only subset; never claim a complete six-file intake")
    parser.add_argument("--report-name", default="intake-v3-related-actions-20260927.json")
    parser.add_argument("--aggregate-only", action="store_true", help="combine completed subset evidence after rehashing all originals")
    args = parser.parse_args()
    directory = ROOT / "artifacts/quality/candidates/v3-related-actions"
    chosen = [source for source in SOURCES if not args.source_id or source["id"] in args.source_id]
    if args.source_id and set(args.source_id) != {source["id"] for source in chosen}:
        raise ValueError("unknown source selection")
    if Path(args.report_name).name != args.report_name or not args.report_name.endswith(".json"):
        raise ValueError("report name must be a local new JSON filename")
    output = directory / args.report_name
    if output.exists():
        raise ValueError("intake report exists; never overwrite")
    if sum(source["size"] for source in SOURCES) > MAX_BYTES:
        raise ValueError("combined download exceeds approved 250MB")
    if not args.ffmpeg.is_file():
        raise ValueError("FFmpeg missing")
    if args.aggregate_only:
        reports_by_id = {}
        subset_paths = [directory / "intake-v3-related-actions-20260927-fabiola-only.json",
                        directory / "intake-v3-related-actions-20260927-remaining-four.json"]
        for path in subset_paths:
            subset = json.loads(path.read_text(encoding="utf-8"))
            if subset.get("status") != "research_source_intake_not_release_acceptance" or \
                    subset.get("promoted_to_holdout") is not False or subset.get("sealed") is not False:
                raise ValueError("unexpected subset evidence")
            for report in subset["sources"]:
                if report["id"] in reports_by_id:
                    raise ValueError("duplicate source evidence")
                reports_by_id[report["id"]] = report
        if set(reports_by_id) != {source["id"] for source in SOURCES}:
            raise ValueError("six source records required")
        reports = []
        for source in SOURCES:
            report = reports_by_id[source["id"]]
            source_file = directory / source["file"]
            if report["published"] != source or identities(source_file) != report["local_identity"] or \
                    report["local_identity"]["sha1"] != source["sha1"] or \
                    report["local_identity"]["bytes"] != source["size"] or \
                    report["full_video_decode_passed"] is not True or \
                    report["timing"]["source_sha256"] != report["local_identity"]["sha256"] or \
                    report["decoded_frames"] != report["timing"]["packet_count"]:
                raise ValueError("source-bound subset evidence inconsistent")
            reports.append(report)
        write_json(output, dict(schema_version=1, created_at=datetime.now(timezone.utc).isoformat(),
                               status="research_source_intake_not_release_acceptance",
                               complete_six_file_intake=True,
                               local_bytes=sum(item["local_identity"]["bytes"] for item in reports),
                               original_sources_unchanged=True, promoted_to_holdout=False,
                               sealed=False, human_accepted=False,
                               aggregation_method="reuse completed source-only reports; freshly rehash six originals, no new decode/inference",
                               subset_reports=[dict(file=path.relative_to(ROOT).as_posix(), **identities(path))
                                               for path in subset_paths], sources=reports))
        print(json.dumps(dict(output=output.relative_to(ROOT).as_posix(), **identities(output))), flush=True)
        return
    if args.analyze_only or args.resume:
        if not directory.is_dir():
            raise ValueError("source directory missing")
    else:
        directory.mkdir(parents=True, exist_ok=False)
    if not args.analyze_only:
        for source in chosen:
            existing = directory / source["file"]
            if args.resume and existing.is_file():
                found = identities(existing)
                if found["bytes"] != source["size"] or found["sha1"] != source["sha1"]:
                    raise ValueError("completed download changed; never replace")
                print(json.dumps(dict(step="existing_download_verified", id=source["id"], **found)), flush=True)
                continue
            found = download(source, directory)
            print(json.dumps(dict(step="download_verified", id=source["id"], **found)), flush=True)
    reports = []
    for source in chosen:
        reports.append(analyze(source, directory, args.ffmpeg.resolve()))
        print(json.dumps(dict(step="source_only_analysis_complete", id=source["id"],
                              mode=reports[-1]["timing"]["measured_frame_rate_mode"],
                              decoded_frames=reports[-1]["decoded_frames"])), flush=True)
    write_json(output, dict(schema_version=1, created_at=datetime.now(timezone.utc).isoformat(),
                           status="research_source_intake_not_release_acceptance",
                           complete_six_file_intake=len(reports) == len(SOURCES),
                           local_bytes=sum(item["local_identity"]["bytes"] for item in reports),
                           original_sources_unchanged=True, promoted_to_holdout=False,
                           sealed=False, human_accepted=False, ffmpeg_file=str(args.ffmpeg.resolve()),
                           sources=reports))
    print(json.dumps(dict(output=output.relative_to(ROOT).as_posix(), **identities(output))), flush=True)


if __name__ == "__main__":
    main()
