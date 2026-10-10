"""Validate v1 raw preview evidence and report independent group metrics (stdlib only)."""

import argparse
from collections import Counter
import json
import math
from pathlib import Path
import re
import sys

EVENTS = {"FIRST_FRAME/cold": (2000, 20), "FIRST_FRAME/warm": (2000, 20),
          "CACHED_SCRUB": (100, 1), "EXACT_SEEK": (750, 100),
          "FORMAT_CHANGE": (150, 20), "PLAY_START": (300, 1)}
APP_CATEGORIES = ("cache", "pages", "header", "track", "scratch", "thumbnail", "fbo")
PROCESS_CATEGORIES = ("rss", "java", "native", "gpu", "codec")
STATUSES = ("completed", "failed", "missing", "incomplete", "obsolete", "superseded")


def _object(value, keys, path, partial=False):
    if not isinstance(value, dict):
        raise ValueError(f"{path}: expected object")
    extra = value.keys() - set(keys)
    missing = set(keys) - value.keys()
    if extra or (missing and not partial):
        raise ValueError(f"{path}: unknown keys {sorted(extra)}, missing keys {sorted(missing)}")


def _int(value, path, nullable=False, positive=False):
    if nullable and value is None:
        return
    if type(value) is not int or value < (1 if positive else 0):
        raise ValueError(f"{path}: expected {'positive' if positive else 'nonnegative'} integer")


def _text(value, path, choices=None, nullable=False):
    if nullable and value is None:
        return
    if not isinstance(value, str) or not value.strip() or (choices and value not in choices):
        raise ValueError(f"{path}: invalid string")


def _bool(value, path):
    if type(value) is not bool:
        raise ValueError(f"{path}: expected boolean")


def _records(value, path, identity="id"):
    if not isinstance(value, list):
        raise ValueError(f"{path}: expected array")
    seen = set()
    for i, item in enumerate(value):
        if not isinstance(item, dict) or identity not in item:
            raise ValueError(f"{path}[{i}]: missing {identity}")
        token = item[identity]
        if type(token) not in (str, int):
            raise ValueError(f"{path}[{i}]: invalid {identity}")
        if token in seen:
            raise ValueError(f"{path}: duplicate {identity} {token}")
        seen.add(token)


def _generation(value, path, nullable=False):
    if nullable and value is None:
        return
    _object(value, ("project", "surface", "seek"), path)
    for key, number in value.items():
        _int(number, f"{path}.{key}")


def _validate_group(group):
    _object(group, ("id", "metadata", "scenario", "sources", "events", "playback", "scrubbing", "memory"), "group")
    _text(group["id"], "group.id")
    metadata = group["metadata"]
    _object(metadata, ("app_version", "commit", "draft_project", "draft_revision", "device", "android", "abi", "codec"), "metadata")
    for key, value in metadata.items():
        (_int if key == "draft_revision" else _text)(value, f"metadata.{key}")
    if not re.fullmatch(r"[0-9a-f]{40}", metadata["commit"]):
        raise ValueError("metadata.commit: expected exact 40-character SHA")
    scenario = group["scenario"]
    _object(scenario, ("style", "aspect", "mode", "input_class", "export_fps"), "scenario")
    for key in ("style", "aspect", "mode", "input_class"):
        _text(scenario[key], f"scenario.{key}")
    _int(scenario["export_fps"], "scenario.export_fps")
    if scenario["export_fps"] not in (30, 60):
        raise ValueError("scenario.export_fps: expected 30 or 60")
    if not isinstance(group["sources"], list) or len(group["sources"]) not in (1, 2):
        raise ValueError("sources: expected one or two source profiles")
    for source in group["sources"]:
        _object(source, ("sha256", "codec", "width", "height", "fps", "dynamic_range", "gop_us", "rotation", "sar_num", "sar_den"), "source")
        for key in ("sha256", "codec", "dynamic_range"):
            _text(source[key], f"source.{key}")
        if not re.fullmatch(r"[0-9a-f]{64}", source["sha256"]):
            raise ValueError("source.sha256: expected SHA256, not a private filename")
        for key in ("width", "height", "fps", "gop_us", "sar_num", "sar_den"):
            _int(source[key], f"source.{key}", positive=True)
        _int(source["rotation"], "source.rotation")
        if source["rotation"] not in (0, 90, 180, 270):
            raise ValueError("source.rotation: invalid rotation")
    _records(group["events"], "events")
    for event in group["events"]:
        _object(event, ("id", "event", "request_ns", "start_ns", "end_ns", "requested_output_us", "shown_output_us", "generation", "completion_generation", "status", "reason", "presented", "canonical_ready", "exact", "temperature", "prepare_ns"), "event")
        _text(event["id"], "event.id")
        _text(event["event"], "event.event", ("FIRST_FRAME", "CACHED_SCRUB", "EXACT_SEEK", "FORMAT_CHANGE", "PLAY_START"))
        _text(event["status"], "event.status", STATUSES)
        _text(event["reason"], "event.reason", nullable=event["status"] == "completed")
        for key in ("request_ns", "start_ns", "requested_output_us"):
            _int(event[key], f"event.{key}")
        for key in ("end_ns", "shown_output_us", "prepare_ns"):
            _int(event[key], f"event.{key}", nullable=True)
        if event["start_ns"] < event["request_ns"] or (event["end_ns"] is not None and event["end_ns"] < event["start_ns"]):
            raise ValueError("event: timestamps out of order")
        for key in ("presented", "canonical_ready", "exact"):
            _bool(event[key], f"event.{key}")
        _generation(event["generation"], "event.generation")
        _generation(event["completion_generation"], "event.completion_generation", nullable=True)
        if event["event"] == "FIRST_FRAME":
            _text(event["temperature"], "event.temperature", ("cold", "warm"))
        elif event["temperature"] is not None or event["prepare_ns"] is not None:
            raise ValueError("event: temperature/prepare_ns belong only to FIRST_FRAME")
    _records(group["playback"], "playback")
    for playback in group["playback"]:
        _object(playback, ("id", "observed_duration_us", "recipe_duration_us", "completed", "frames"), "playback")
        _text(playback["id"], "playback.id")
        for key in ("observed_duration_us", "recipe_duration_us"):
            _int(playback[key], f"playback.{key}", positive=True)
        _bool(playback["completed"], "playback.completed")
        _records(playback["frames"], "frames", "index")
        expected = (playback["observed_duration_us"] * 30 + 999_999) // 1_000_000
        presented_pts = set()
        for frame in playback["frames"]:
            _object(frame, ("index", "status", "shown_output_us", "audio_us"), "frame")
            _int(frame["index"], "frame.index")
            if frame["index"] >= expected:
                raise ValueError("frame.index: outside observed 30FPS grid")
            _text(frame["status"], "frame.status", ("shown", "dropped", "missing"))
            for key in ("shown_output_us", "audio_us"):
                _int(frame[key], f"frame.{key}", nullable=True)
            if frame["status"] != "shown" and any(frame[k] is not None for k in ("shown_output_us", "audio_us")):
                raise ValueError("frame: non-shown slot cannot carry a presentation")
            if frame["status"] == "shown" and frame["shown_output_us"] is not None:
                if frame["shown_output_us"] in presented_pts:
                    raise ValueError("frame: duplicate presentation PTS cannot fill another grid slot")
                presented_pts.add(frame["shown_output_us"])
    _records(group["scrubbing"], "scrubbing")
    intervals = []
    for scrub in group["scrubbing"]:
        _object(scrub, ("id", "start_ns", "end_ns", "observed_duration_us", "completed"), "scrubbing")
        _text(scrub["id"], "scrubbing.id")
        for key in ("start_ns", "end_ns", "observed_duration_us"):
            _int(scrub[key], f"scrubbing.{key}")
        _bool(scrub["completed"], "scrubbing.completed")
        if scrub["end_ns"] < scrub["start_ns"] or scrub["observed_duration_us"] * 1000 > scrub["end_ns"] - scrub["start_ns"]:
            raise ValueError("scrubbing: duration outside timestamps")
        intervals.append((scrub["start_ns"], scrub["end_ns"]))
    intervals.sort()
    if any(a[1] > b[0] for a, b in zip(intervals, intervals[1:])):
        raise ValueError("scrubbing: overlapping intervals would double-count duration")
    _records(group["memory"], "memory", "at_ns")
    for memory in group["memory"]:
        _object(memory, ("at_ns", "app_bytes", "process_bytes"), "memory")
        _int(memory["at_ns"], "memory.at_ns")
        for field, categories in (("app_bytes", APP_CATEGORIES), ("process_bytes", PROCESS_CATEGORIES)):
            _object(memory[field], categories, f"memory.{field}", partial=True)
            for key, value in memory[field].items():
                _int(value, f"memory.{field}.{key}", nullable=True)


def _p95(samples):
    return sorted(samples)[math.ceil(.95 * len(samples)) - 1] if samples else None


def _status(failed=False, incomplete=False):
    return "FAIL" if failed else "INCOMPLETE" if incomplete else "PASS"


def _combined(statuses):
    values = list(statuses)
    return _status("FAIL" in values, not values or "INCOMPLETE" in values)


def _events(group):
    metrics = {}
    accuracy_errors = []
    accuracy_unknown = 0
    for key, (limit, quota) in EVENTS.items():
        kind, _, temperature = key.partition("/")
        raw = [e for e in group["events"] if e["event"] == kind and (not temperature or e["temperature"] == temperature)]
        counts = Counter()
        timings, prepares, issues = [], [], []
        for event in raw:
            status = event["status"]
            reason = event["reason"]
            if (event["presented"] or status == "completed") and event["completion_generation"] is not None and event["generation"] != event["completion_generation"]:
                status, reason = "obsolete", "generation differs at completion/presentation"
            elif status == "superseded" and (event["presented"] or event["shown_output_us"] is not None or kind != "EXACT_SEEK"):
                status, reason = "obsolete", "superseded request presented or is not intermediate EXACT_SEEK"
            elif status == "completed":
                if kind != "CACHED_SCRUB" and (not event["canonical_ready"] or not event["exact"]):
                    status, reason = "failed", "canonical exact frame not ready"
                elif not event["presented"] or event["end_ns"] is None or event["shown_output_us"] is None or event["completion_generation"] is None:
                    status, reason = "incomplete", "completion/presentation evidence missing"
            counts[status] += 1
            if status == "completed":
                begin = event["start_ns"] if kind == "FIRST_FRAME" else event["request_ns"]
                timings.append((event["end_ns"] - begin) / 1_000_000)
                if kind == "FIRST_FRAME" and event["prepare_ns"] is not None:
                    prepares.append(event["prepare_ns"] / 1_000_000)
                if kind != "CACHED_SCRUB":
                    accuracy_errors.append(abs(event["shown_output_us"] - event["requested_output_us"]))
            else:
                issues.append({"id": event["id"], "status": status, "reason": reason})
                if status != "superseded" and kind != "CACHED_SCRUB":
                    accuracy_unknown += 1
        p95 = _p95(timings)
        failed = counts["failed"] + counts["obsolete"] > 0 or (p95 is not None and p95 > limit)
        incomplete = counts["missing"] + counts["incomplete"] > 0 or counts["completed"] < quota
        if kind == "FIRST_FRAME" and len(prepares) != counts["completed"]:
            incomplete = True
        metrics[key] = {s: counts[s] for s in STATUSES}
        metrics[key].update(status=_status(failed, incomplete), requests=len(raw), required=quota,
                            p95_ms=p95, limit_ms=limit, issues=issues)
        if kind == "FIRST_FRAME":
            metrics[key]["prepare_p95_ms"] = _p95(prepares)
    error = max(accuracy_errors, default=None)
    accuracy = {"max_error_us": error, "unknown": accuracy_unknown,
                "status": _status(error is not None and error * group["scenario"]["export_fps"] > 1_000_000,
                                  error is None or accuracy_unknown > 0)}
    return metrics, accuracy


def _playback(group):
    expected = shown = dropped = missing = unknown = full = 0
    drift = []
    passes = []
    for playback in group["playback"]:
        size = (playback["observed_duration_us"] * 30 + 999_999) // 1_000_000
        counts = Counter(frame["status"] for frame in playback["frames"])
        absent = size - len(playback["frames"])
        missing_slots = counts["missing"] + absent
        expected += size
        shown += counts["shown"]
        dropped += counts["dropped"]
        missing += missing_slots
        pass_drift, pass_unknown = [], 0
        for frame in playback["frames"]:
            if frame["status"] == "shown":
                if frame["shown_output_us"] is None or frame["audio_us"] is None:
                    pass_unknown += 1
                else:
                    pass_drift.append(abs(frame["shown_output_us"] - frame["audio_us"]))
        drift.extend(pass_drift)
        unknown += pass_unknown
        pass_drop_percent = 100 * counts["dropped"] / size
        pass_max_drift = max(pass_drift, default=None)
        pass_status = _status(pass_drop_percent > 5 or (pass_max_drift is not None and pass_max_drift > 50_000),
                              missing_slots > 0 or pass_unknown > 0 or pass_max_drift is None)
        is_full = playback["completed"] and playback["observed_duration_us"] >= playback["recipe_duration_us"] and missing_slots == 0
        full += is_full
        passes.append({"id": playback["id"], "observed_duration_us": playback["observed_duration_us"],
                       "recipe_duration_us": playback["recipe_duration_us"], "full": is_full,
                       "expected": size, "shown": counts["shown"], "dropped": counts["dropped"],
                       "missing": missing_slots, "unknown_drift": pass_unknown,
                       "max_av_error_us": pass_max_drift, "drop_percent": pass_drop_percent,
                       "status": pass_status})
    drop_percent = 100 * dropped / expected if expected else None
    max_drift = max(drift, default=None)
    result = {"expected_frames": expected, "shown": shown, "dropped": dropped, "missing": missing,
              "unknown_drift": unknown, "drop_percent": drop_percent, "max_av_error_us": max_drift,
              "passes": passes, "status": _combined([p["status"] for p in passes] + [_status(
                  (drop_percent is not None and drop_percent > 5) or (max_drift is not None and max_drift > 50_000),
                  not expected or missing > 0 or unknown > 0 or max_drift is None)])}
    scrub_us = sum(s["observed_duration_us"] for s in group["scrubbing"] if s["completed"])
    coverage = {"full_plays": full, "required_full_plays": 3, "scrubbing_us": scrub_us,
                "required_scrubbing_us": 600_000_000, "status": _status(incomplete=full < 3 or scrub_us < 600_000_000 or any(not s["completed"] for s in group["scrubbing"]))}
    return result, coverage


def _memory(group):
    totals = []
    peaks = {"app_bytes": {}, "process_bytes": {}}
    unknown = []
    for field, categories in (("app_bytes", APP_CATEGORIES), ("process_bytes", PROCESS_CATEGORIES)):
        for category in categories:
            values = [sample[field].get(category) for sample in group["memory"]]
            peaks[field][category] = max((v for v in values if v is not None), default=None)
            if not values or any(v is None for v in values):
                unknown.append(f"{field}.{category}")
    for sample in group["memory"]:
        values = [sample["app_bytes"].get(k) for k in APP_CATEGORIES]
        if all(v is not None for v in values):
            totals.append(sum(values))
    peak = max(totals, default=None)
    return {"app_peak_bytes": peak, "app_category_peak_bytes": peaks["app_bytes"],
            "process_peak_bytes": peaks["process_bytes"], "unknown_categories": unknown,
            "status": _status(peak is not None and peak > 32 * 1024 * 1024, bool(unknown))}


def evaluate(document):
    """Return grouped calculated metrics; raise ValueError for malformed v1 raw input."""
    _object(document, ("schema_version", "groups"), "document")
    _int(document["schema_version"], "schema_version")
    if document["schema_version"] != 1:
        raise ValueError("schema_version: unsupported version")
    _records(document["groups"], "groups")
    groups = []
    for group in document["groups"]:
        _validate_group(group)
        metrics, accuracy = _events(group)
        playback, coverage = _playback(group)
        memory = _memory(group)
        baseline = all(s["codec"] == "h264" and s["dynamic_range"] == "SDR" and
                       sorted((s["width"], s["height"])) == [1080, 1920] and
                       s["fps"] in (30, 60) and s["gop_us"] <= 2_000_000 for s in group["sources"])
        status = _combined([m["status"] for m in metrics.values()] +
                           [accuracy["status"], playback["status"], coverage["status"], memory["status"]])
        groups.append({"id": group["id"], "metadata": group["metadata"], "scenario": group["scenario"],
                       "sources": group["sources"], "input_profile": "baseline" if baseline else "heavy",
                       "status": status, "events": metrics, "accuracy": accuracy, "playback": playback,
                       "coverage": coverage, "memory": memory, "scrubbing": group["scrubbing"]})
    return {"schema_version": 1, "status": _combined(g["status"] for g in groups),
            "product_approved": False, "groups": groups}


def _cell(value):
    if value is None:
        return "unknown"
    if isinstance(value, int):
        return f"{value:,}"
    if isinstance(value, float):
        return f"{value:.3f}"
    return str(value).replace("&", "&amp;").replace("<", "&lt;").replace(
        ">", "&gt;").replace("|", "&#124;").replace("\r", " ").replace("\n", " ")


def render(report):
    """Render calculated metrics to Markdown with explicit evidence limits."""
    lines = ["# Preview benchmark report", "", f"Recorded metrics: **{report['status']}**", "",
             "Arithmetic on supplied raw evidence is not product or visual approval. "
             "Synthetic fixtures do not establish physical-device performance. Runtime wiring, "
             "geometry/export, human 1× review and feature-gate acceptance remain separate.", ""]
    for group in report["groups"]:
        lines += [f"## {_cell(group['id'])}: {group['input_profile']} / {group['status']}", ""]
        for name in ("metadata", "scenario"):
            lines += [" | ".join(f"{key}={_cell(value)}" for key, value in group[name].items()), ""]
        for i, source in enumerate(group["sources"]):
            lines += [f"Source {i}: " + " | ".join(f"{k}={_cell(v)}" for k, v in source.items()), ""]
        lines += ["| Event | Status | Requests | Completed / required | p95 (ms) / limit | Failed | Missing | Incomplete | Obsolete | Superseded |",
                  "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
        for name, metric in group["events"].items():
            lines.append(f"| {name} | {metric['status']} | {metric['requests']} | {metric['completed']} / {metric['required']} | {_cell(metric['p95_ms'])} / {metric['limit_ms']} | {metric['failed']} | {metric['missing']} | {metric['incomplete']} | {metric['obsolete']} | {metric['superseded']} |")
        lines.append("")
        for name, metric in group["events"].items():
            if "prepare_p95_ms" in metric:
                lines += [f"{name} preparation p95 (ms), separate: {_cell(metric['prepare_p95_ms'])}", ""]
            for issue in metric["issues"]:
                lines.append(f"- {name} / {_cell(issue['id'])}: {issue['status']} — {_cell(issue['reason'])}")
        lines += ["", f"Paused/canonical output PTS accuracy (us): {_cell(group['accuracy'])}", "",
                  f"Playback 30FPS target grid (us, drops %): {_cell({k: v for k, v in group['playback'].items() if k != 'passes'})}", ""]
        for playback in group["playback"]["passes"]:
            lines += [f"Playback pass: {_cell(playback)}", ""]
        lines += [f"Coverage (observed us; ≥3 full plays and ≥600,000,000 us scrubbing): {_cell(group['coverage'])}", ""]
        for scrub in group["scrubbing"]:
            lines += [f"Scrubbing pass: {_cell(scrub)}", ""]
        memory = group["memory"]
        lines += [f"Memory: {memory['status']}; same-time app peak (bytes): {_cell(memory['app_peak_bytes'])} / 33,554,432", "",
                  "Category maxima below occur independently; their sum is not an observed app peak.", "",
                  "| Category | Independent peak (bytes) |", "| --- | --- |"]
        for field in ("app_category_peak_bytes", "process_peak_bytes"):
            for key, value in memory[field].items():
                lines.append(f"| {field}.{key} | {_cell(value)} |")
        lines += ["", f"Unknown/unmeasured categories: {_cell(', '.join(memory['unknown_categories']) or 'none')}", ""]
    return "\n".join(lines) + "\n"


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        document = json.loads(args.input.read_text(encoding="utf-8"), object_pairs_hook=_unique_object,
                              parse_constant=lambda value: (_ for _ in ()).throw(ValueError(f"nonfinite JSON number: {value}")))
        report = evaluate(document)
        args.output.write_text(render(report), encoding="utf-8")
    except (OSError, ValueError) as error:
        print(f"preview benchmark: {error}", file=sys.stderr)
        return 2
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
