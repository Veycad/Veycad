"""Evaluate immutable RU word evidence. No recognition, downloads, or timing repair."""

import argparse
from collections import defaultdict
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import statistics
import tempfile
import unicodedata
import wave


CONDITIONS = ("clean", "quiet", "music_noise", "boundaries")
SAMPLE_RATE = 16_000
PINNED_BACKEND_COMMIT = "48f628a84833905ee4a0658ee6d4a5c915ce1997"
PROTOCOL_VERSION = 1
VALIDATOR_PATH = Path(__file__).resolve()
VALIDATOR_SHA256 = hashlib.sha256(VALIDATOR_PATH.read_bytes()).hexdigest()
PROTOCOL_PATH = VALIDATOR_PATH.parents[1] / "docs/testing/speech-word-quality.md"
CEILINGS = {"wer_max": 0.20, "start_median_max_us": 200_000,
            "start_p95_max_us": 500_000, "end_median_max_us": 200_000,
            "end_p95_max_us": 500_000}


class ValidationError(ValueError):
    """Evidence is structurally invalid; partial metrics cannot satisfy the gate."""


def _object(value, name):
    if not isinstance(value, dict):
        raise ValidationError(f"{name}: expected object")
    return value


def _list(value, name, nonempty=False):
    if not isinstance(value, list) or (nonempty and not value):
        raise ValidationError(f"{name}: expected {'nonempty ' if nonempty else ''}list")
    return value


def _string(value, name):
    if not isinstance(value, str) or not value.strip():
        raise ValidationError(f"{name}: expected nonempty string")
    return value


def _integer(value, name, minimum=0):
    if type(value) is not int or value < minimum:
        raise ValidationError(f"{name}: expected integer >= {minimum}")
    return value


def _sha(value, name, length=64):
    if not isinstance(value, str) or not re.fullmatch(f"[0-9a-f]{{{length}}}", value) or value == "0" * length:
        raise ValidationError(f"{name}: expected nonzero lowercase SHA")
    return value


def _timestamp(value, name):
    try:
        parsed = datetime.fromisoformat(_string(value, name).replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError("missing timezone")
        return parsed.astimezone(timezone.utc)
    except ValueError as error:
        raise ValidationError(f"{name}: invalid timezone-aware timestamp") from error


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValidationError(f"JSON duplicate key: {key}")
        result[key] = value
    return result


class EvidenceReader:
    """Reads evidence, checks actual bytes, and detects changes during this run."""
    def __init__(self):
        self.hashes = {}
        self.protected_paths = set()

    def digest(self, path):
        path = Path(path).resolve()
        self.protected_paths.add(path)
        try:
            digest = hashlib.sha256()
            size = 0
            with path.open("rb") as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b""):
                    size += len(block)
                    digest.update(block)
            if not size:
                raise ValidationError(f"empty artifact: {path}")
        except OSError as error:
            raise ValidationError(f"unreadable artifact: {path}") from error
        value = digest.hexdigest()
        previous = self.hashes.setdefault(path, value)
        if previous != value:
            raise ValidationError(f"artifact changed during evaluation: {path}")
        return value

    def json(self, path):
        self.digest(path)
        try:
            value = json.loads(Path(path).read_text(encoding="utf-8"), object_pairs_hook=_unique_object,
                               parse_constant=lambda value: (_ for _ in ()).throw(ValidationError(f"JSON nonfinite: {value}")))
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            raise ValidationError(f"invalid JSON: {path}") from error
        self.digest(path)
        value = _object(value, str(path))
        self._protect_declared_paths(value, Path(path).resolve().parent)
        return value

    def _protect_declared_paths(self, value, base):
        if isinstance(value, dict):
            declared = value.get("path")
            if isinstance(declared, str) and declared.strip():
                self.protected_paths.add((base / declared).resolve())
            for child in value.values():
                self._protect_declared_paths(child, base)
        elif isinstance(value, list):
            for child in value:
                self._protect_declared_paths(child, base)

    def artifact(self, descriptor, base, name):
        descriptor = _object(descriptor, name)
        path = (base / _string(descriptor.get("path"), name + ".path")).resolve()
        expected = _sha(descriptor.get("sha256"), name + ".sha256")
        if self.digest(path) != expected:
            raise ValidationError(f"SHA mismatch: {name}")
        return path

    def unchanged(self):
        for path in list(self.hashes):
            self.digest(path)


def _normal_word(text):
    text = unicodedata.normalize("NFC", _string(text, "word.text")).casefold()
    if any(unicodedata.category(character) == "Cs" for character in text):
        raise ValidationError("word.text contains invalid Unicode surrogate")
    if any(character.isspace() for character in text):
        raise ValidationError("word.text contains whitespace; tokenization must be explicit")
    while text and unicodedata.category(text[0]).startswith("P"):
        text = text[1:]
    while text and unicodedata.category(text[-1]).startswith("P"):
        text = text[:-1]
    if not any(unicodedata.category(character)[0] in ("L", "N") for character in text):
        raise ValidationError("word.text has no lexical characters")
    return text


def _validate_words(words, duration_us, nonempty=False):
    words = _list(words, "words", nonempty)
    previous_start = -1
    normalized = []
    for index, value in enumerate(words):
        value = _object(value, f"words[{index}]")
        text = _normal_word(value.get("text"))
        start = _integer(value.get("start_us"), f"words[{index}].start_us")
        end = _integer(value.get("end_us"), f"words[{index}].end_us")
        if not 0 <= start < end <= duration_us or start < previous_start:
            raise ValidationError(f"words[{index}]: invalid interval/order (duration_us={duration_us})")
        previous_start = start
        normalized.append(text)
    return normalized


def _percentiles(errors):
    if not errors:
        return {"median_us": None, "p95_us": None, "count": 0}
    ordered = sorted(errors)
    return {"median_us": statistics.median(ordered),
            "p95_us": ordered[math.ceil(0.95 * len(ordered)) - 1], "count": len(ordered)}


def _summarize(reference_count, hypothesis_count, matched, substitutions, deletions, insertions,
               start_errors, end_errors):
    if not reference_count:
        raise ValidationError("zero reference words")
    return {"reference_count": reference_count, "hypothesis_count": hypothesis_count,
            "matched": matched, "substitutions": substitutions, "deletions": deletions,
            "insertions": insertions, "wer": (substitutions + deletions + insertions) / reference_count,
            "matched_coverage": matched / reference_count,
            "start": _percentiles(start_errors), "end": _percentiles(end_errors),
            "start_errors_us": start_errors, "end_errors_us": end_errors}


def score_words(reference, hypothesis, duration_us):
    """Deterministic word Levenshtein; only exact normalized matches receive timing errors."""
    duration_us = _integer(duration_us, "duration_us", 1)
    expected = _validate_words(reference, duration_us, nonempty=True)
    observed = _validate_words(hypothesis, duration_us)
    rows, columns = len(expected), len(observed)
    distance = [[(0, 0)] * (columns + 1) for _ in range(rows + 1)]
    for index in range(rows + 1):
        distance[index][0] = (index, 0)
    for index in range(columns + 1):
        distance[0][index] = (index, 0)
    for row in range(1, rows + 1):
        for column in range(1, columns + 1):
            equal = expected[row - 1] == observed[column - 1]
            diagonal = distance[row - 1][column - 1]
            deletion = distance[row - 1][column]
            insertion = distance[row][column - 1]
            # First minimize edit cost; equal-cost paths maximize lexical matches.
            distance[row][column] = min((diagonal[0] + (not equal), diagonal[1] - equal),
                                        (deletion[0] + 1, deletion[1]), (insertion[0] + 1, insertion[1]))
    row, column = rows, columns
    matches, misses_reference, misses_hypothesis = [], [], []
    substitutions = deletions = insertions = 0
    while row or column:
        # Remaining tie order: diagonal, deletion, insertion. Timing never influences matching.
        equal = bool(row and column and expected[row - 1] == observed[column - 1])
        diagonal = distance[row - 1][column - 1] if row and column else None
        deletion = distance[row - 1][column] if row else None
        if diagonal is not None and distance[row][column] == (diagonal[0] + (not equal), diagonal[1] - equal):
            row, column = row - 1, column - 1
            if expected[row] == observed[column]:
                matches.append((row, column))
            else:
                substitutions += 1
                misses_reference.append(row)
                misses_hypothesis.append(column)
        elif deletion is not None and distance[row][column] == (deletion[0] + 1, deletion[1]):
            row -= 1
            deletions += 1
            misses_reference.append(row)
        else:
            column -= 1
            insertions += 1
            misses_hypothesis.append(column)
    matches.reverse()
    start_errors = [abs(reference[r]["start_us"] - hypothesis[h]["start_us"]) for r, h in matches]
    end_errors = [abs(reference[r]["end_us"] - hypothesis[h]["end_us"]) for r, h in matches]
    return {**_summarize(rows, columns, len(matches), substitutions, deletions, insertions, start_errors, end_errors),
            "matched_indices": [list(pair) for pair in matches],
            "unmatched_reference_indices": sorted(misses_reference),
            "unmatched_hypothesis_indices": sorted(misses_hypothesis)}


def _pooled(reports):
    reports = list(reports)
    return _summarize(*(sum(row[key] for row in reports) for key in
                        ("reference_count", "hypothesis_count", "matched", "substitutions", "deletions", "insertions")),
                      [value for row in reports for value in row["start_errors_us"]],
                      [value for row in reports for value in row["end_errors_us"]])


def _audio(reader, descriptor, base, name):
    path = reader.artifact(descriptor, base, name)
    try:
        with wave.open(str(path), "rb") as stream:
            actual = (stream.getframerate(), stream.getnchannels(), stream.getsampwidth(), stream.getnframes())
            expected = (descriptor.get("sample_rate"), descriptor.get("channels"),
                        descriptor.get("sample_width"), descriptor.get("input_frames"))
            for value in expected:
                _integer(value, name, 1)
            if actual != expected or actual[:3] != (SAMPLE_RATE, 1, 2) or stream.getcomptype() != "NONE":
                raise ValidationError(f"{name}: must be actual mono PCM16 WAV at 16kHz with exact frame count")
            count = 0
            for block in iter(lambda: stream.readframes(16_000), b""):
                count += len(block)
            if count != actual[3] * 2:
                raise ValidationError(f"{name}: truncated PCM frames")
    except (OSError, EOFError, wave.Error) as error:
        raise ValidationError(f"{name}: invalid WAV") from error
    return actual[3] * 1_000_000 // SAMPLE_RATE


def _approved(reader, evidence, base, name):
    if evidence is None:
        return False
    evidence = _object(evidence, name)
    if type(evidence.get("approved")) is not bool:
        raise ValidationError(f"{name}.approved: expected boolean")
    if evidence.get("artifact") is not None:
        reader.artifact(evidence["artifact"], base, name)
    elif evidence["approved"]:
        raise ValidationError(f"{name}: approval requires a real hashed artifact")
    return evidence["approved"]


def _manifest(reader, manifest, base):
    if manifest.get("schema_version") != 1 or type(manifest.get("schema_version")) is not int:
        raise ValidationError("unsupported manifest schema_version")
    if manifest.get("corpus_kind") != "human_annotated_ru":
        raise ValidationError("corpus_kind must be human_annotated_ru, not synthetic/template")
    created = _timestamp(manifest.get("created_at"), "created_at")
    first_access = _timestamp(manifest.get("holdout_first_access_at"), "holdout_first_access_at")
    if created >= first_access:
        raise ValidationError("manifest must be fixed before holdout access")
    candidate = _object(manifest.get("candidate"), "candidate")
    _sha(candidate.get("backend_commit"), "candidate.backend_commit", 40)
    if candidate["backend_commit"] != PINNED_BACKEND_COMMIT:
        raise ValidationError("unsupported backend commit; protocol requires reviewed pinned whisper.cpp")
    for name in ("backend_patch", "model", "apk"):
        reader.artifact(candidate.get(name), base, "candidate." + name)
    if candidate.get("calibration") is not None:
        reader.artifact(candidate["calibration"], base, "candidate.calibration")
    corpus_reviewed = _approved(reader, manifest.get("corpus_review"), base, "corpus_review")
    recordings = {}
    audio_paths, audio_hashes = set(), set()
    speaker_splits, conditions_by_speaker = {}, defaultdict(set)
    for row in _list(manifest.get("recordings"), "recordings", nonempty=True):
        row = _object(row, "recording")
        recording_id = _string(row.get("id"), "recording.id")
        if recording_id in recordings:
            raise ValidationError("duplicate recording id")
        speaker = _string(row.get("speaker_id"), "speaker_id")
        split, condition = row.get("split"), row.get("condition")
        if split not in ("pilot", "train", "holdout") or condition not in CONDITIONS:
            raise ValidationError("invalid split/condition")
        if speaker in speaker_splits and speaker_splits[speaker] != split:
            raise ValidationError("speaker appears in multiple splits; holdout is not disjoint")
        speaker_splits[speaker] = split
        conditions_by_speaker[speaker].add(condition)
        audio = _object(row.get("audio"), recording_id + ".audio")
        duration = _audio(reader, audio, base, recording_id + ".audio")
        audio_path = (base / audio["path"]).resolve()
        if audio_path in audio_paths or audio["sha256"] in audio_hashes:
            raise ValidationError("duplicate audio path or bytes; recordings must be distinct")
        audio_paths.add(audio_path)
        audio_hashes.add(audio["sha256"])
        rights = _object(row.get("rights"), recording_id + ".rights")
        if rights.get("kind") not in ("license", "consent"):
            raise ValidationError("rights.kind must be license or consent")
        reader.artifact(rights.get("artifact"), base, recording_id + ".rights")
        annotations = _list(row.get("annotations"), recording_id + ".annotations")
        if len(annotations) != 2:
            raise ValidationError("two independent annotators required")
        identities, paths = set(), set()
        for annotation in annotations:
            annotation = _object(annotation, "annotation")
            identity = _string(annotation.get("annotator_id"), "annotator_id")
            if annotation.get("independent") is not True:
                raise ValidationError("independent annotation declaration required")
            path = reader.artifact(annotation.get("artifact"), base, recording_id + ".annotation")
            identities.add(identity)
            paths.add(path)
            _validate_words(reader.json(path).get("words"), duration, nonempty=True)
        if len(identities) != 2 or len(paths) != 2:
            raise ValidationError("two distinct annotator identities and source artifacts required")
        adjudication = _object(row.get("adjudication"), "adjudication")
        _string(adjudication.get("adjudicator_id"), "adjudicator_id")
        gold_path = reader.artifact(adjudication.get("artifact"), base, recording_id + ".adjudication")
        if gold_path in paths:
            raise ValidationError("adjudication must be a separate immutable artifact")
        reference = reader.json(gold_path).get("words")
        _validate_words(reference, duration, nonempty=True)
        recordings[recording_id] = {"speaker_id": speaker, "split": split, "condition": condition,
                                    "duration_us": duration, "reference": reference}
    pilot = [speaker for speaker, split in speaker_splits.items() if split == "pilot"]
    holdout = [speaker for speaker, split in speaker_splits.items() if split == "holdout"]
    if len(pilot) < 8 or len(holdout) < 2:
        raise ValidationError("at least eight pilot speakers and two disjoint holdout speakers required")
    if any(conditions_by_speaker[speaker] != set(CONDITIONS) for speaker in pilot + holdout):
        raise ValidationError("each pilot/holdout speaker requires all four conditions")
    return recordings, candidate, created, first_access, corpus_reviewed, {
        "recordings": len(recordings), "pilot_speakers": len(pilot), "holdout_speakers": len(holdout)}


def _criteria(reader, path, manifest_hash, created, first_access, provenance):
    if path is None:
        return None, ["criteria not preregistered"]
    config = reader.json(path)
    base = Path(path).resolve().parent
    if type(config.get("schema_version")) is not int or config["schema_version"] != 1:
        raise ValidationError("unsupported criteria schema_version")
    if _sha(config.get("manifest_sha256"), "criteria.manifest_sha256") != manifest_hash:
        raise ValidationError("criteria is bound to a different manifest")
    registered = _timestamp(config.get("registered_at"), "criteria.registered_at")
    if not created <= registered < first_access:
        raise ValidationError("criteria must be registered before first holdout access")
    limits = _object(config.get("criteria"), "criteria")
    for key, maximum in CEILINGS.items():
        value = limits.get(key)
        if type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= maximum:
            raise ValidationError(f"criteria.{key}: invalid or relaxed threshold")
        if key.endswith("_us"):
            _integer(value, "criteria." + key)
    coverage = limits.get("matched_coverage_min")
    if type(coverage) not in (int, float) or not math.isfinite(coverage) or not 0.80 <= coverage <= 1:
        raise ValidationError("criteria.matched_coverage_min: must be >= .80 and <= 1")
    reasons = []
    for name in ("protocol_version", "validator_sha256", "protocol_sha256", "unicode_version"):
        if config.get(name) is None:
            reasons.append(f"preregistered scoring {name} absent")
            continue
        if name == "protocol_version":
            _integer(config[name], "criteria.protocol_version", 1)
        elif name == "unicode_version":
            _string(config[name], "criteria.unicode_version")
        else:
            _sha(config[name], "criteria." + name)
        if config[name] != provenance[name]:
            raise ValidationError(f"preregistered scoring {name} does not match this evaluator")
    if not _approved(reader, config.get("acceptance"), base, "criteria.acceptance"):
        reasons.append("proposed WER/coverage criteria have no recorded acceptance")
    if config.get("pilot_report") is None:
        reasons.append("measured pilot report absent")
    else:
        reader.artifact(config["pilot_report"], base, "criteria.pilot_report")
    return limits, reasons


def _violations(metrics, limits, scope):
    reasons = []
    if metrics["wer"] > limits["wer_max"]:
        reasons.append(f"{scope}: WER above threshold")
    if metrics["matched_coverage"] < limits["matched_coverage_min"]:
        reasons.append(f"{scope}: matched coverage below threshold")
    for boundary in ("start", "end"):
        for percentile in ("median", "p95"):
            value = metrics[boundary][percentile + "_us"]
            if value is None or value > limits[f"{boundary}_{percentile}_max_us"]:
                reasons.append(f"{scope}: {boundary} {percentile} missing or above threshold")
    return reasons


def _confidence(reader, candidate, predictions, base):
    """Checks a separate human review declaration, never derives calibration from SHA."""
    if predictions["confidence_kind"] != "CALIBRATED_WORD":
        return "OPEN", ["word confidence is uncalibrated or unavailable"]
    if candidate.get("calibration") is None:
        return "OPEN", ["word confidence calibration evidence absent"]
    if predictions.get("calibration_sha256") != candidate["calibration"]["sha256"]:
        raise ValidationError("prediction calibration does not match candidate")
    review = candidate.get("calibration_review")
    if review is None:
        return "OPEN", ["confidence calibration has no separate human review"]
    review = _object(review, "candidate.calibration_review")
    _string(review.get("reviewer_id"), "candidate.calibration_review.reviewer_id")
    if review.get("calibration_sha256") != candidate["calibration"]["sha256"]:
        raise ValidationError("calibration review is bound to a different artifact")
    if not _approved(reader, review, base, "candidate.calibration_review"):
        return "OPEN", ["confidence calibration human review is not approved"]
    return "PASS", []


def evaluate(manifest_path, predictions_path, criteria_path=None):
    """PASS requires valid evidence and accepted preregistration; declarations still need human audit."""
    reader = EvidenceReader()
    provenance = {"protocol_version": PROTOCOL_VERSION, "unicode_version": unicodedata.unidata_version}
    try:
        provenance["validator_sha256"] = reader.digest(VALIDATOR_PATH)
        if provenance["validator_sha256"] != VALIDATOR_SHA256:
            raise ValidationError("validator source changed after import")
        provenance["protocol_sha256"] = reader.digest(PROTOCOL_PATH)
        manifest = reader.json(manifest_path)
        manifest_hash = reader.digest(manifest_path)
        records, candidate, created, first_access, reviewed, corpus = _manifest(
            reader, manifest, Path(manifest_path).resolve().parent)
        predictions = reader.json(predictions_path)
        if type(predictions.get("schema_version")) is not int or predictions["schema_version"] != 1:
            raise ValidationError("unsupported predictions schema_version")
        if _sha(predictions.get("manifest_sha256"), "predictions.manifest_sha256") != manifest_hash:
            raise ValidationError("predictions are bound to a different manifest")
        if predictions.get("backend_commit") != candidate["backend_commit"]:
            raise ValidationError("prediction backend does not match candidate")
        for name in ("backend_patch", "model", "apk"):
            if predictions.get(name + "_sha256") != candidate[name]["sha256"]:
                raise ValidationError(f"prediction {name} does not match candidate")
        if _timestamp(predictions.get("generated_at"), "generated_at") < first_access:
            raise ValidationError("predictions precede declared first holdout access")
        if predictions.get("alignment_kind") not in ("SEGMENT_ONLY", "TOKEN_HEURISTIC", "ACOUSTIC_WORD"):
            raise ValidationError("unknown alignment_kind")
        if predictions.get("confidence_kind") not in ("UNAVAILABLE", "TOKEN_AGGREGATE", "CALIBRATED_WORD"):
            raise ValidationError("unknown confidence_kind")
        reasons = []
        if not reviewed:
            reasons.append("human annotated corpus and rights have no recorded review")
        if predictions["alignment_kind"] != "ACOUSTIC_WORD":
            reasons.append("alignment is not validated acoustic word evidence")
        confidence_gate, confidence_reasons = _confidence(
            reader, candidate, predictions, Path(manifest_path).resolve().parent)
        limits, registration_reasons = _criteria(reader, criteria_path, manifest_hash, created, first_access, provenance)
        reasons.extend(registration_reasons)
        per_file = {}
        for row in _list(predictions.get("recordings"), "predictions.recordings", nonempty=True):
            row = _object(row, "prediction")
            recording_id = _string(row.get("id"), "prediction.id")
            if recording_id not in records or recording_id in per_file:
                raise ValidationError("duplicate or unknown prediction recording")
            record = records[recording_id]
            per_file[recording_id] = {**score_words(record["reference"], row.get("words"), record["duration_us"]),
                                      "speaker_id": record["speaker_id"], "split": record["split"],
                                      "condition": record["condition"], "duration_us": record["duration_us"]}
        if set(per_file) != set(records):
            raise ValidationError("missing prediction recordings; cannot omit failures")
        per_condition = {condition: _pooled(row for row in per_file.values() if row["condition"] == condition)
                         for condition in CONDITIONS}
        per_split = {split: _pooled(row for row in per_file.values() if row["split"] == split)
                     for split in ("pilot", "train", "holdout") if any(row["split"] == split for row in per_file.values())}
        holdout_conditions = {condition: _pooled(row for row in per_file.values()
                                                if row["split"] == "holdout" and row["condition"] == condition)
                              for condition in CONDITIONS}
        failures = []
        if limits is not None:
            failures.extend(_violations(per_split["holdout"], limits, "holdout"))
            for condition, metrics in holdout_conditions.items():
                failures.extend(_violations(metrics, limits, "holdout." + condition))
        reader.unchanged()
        alignment_gate = "OPEN" if reasons else ("FAIL" if failures else "PASS")
        return {**provenance, "gate": alignment_gate, "alignment_gate": alignment_gate,
                "confidence_gate": confidence_gate, "confidence_open_reasons": confidence_reasons,
                "confidence_review_declared": confidence_gate == "PASS",
                "needs_review": alignment_gate != "PASS" or confidence_gate != "PASS",
                "open_reasons": reasons, "threshold_failures": failures, "invalid_count": 0,
                "manifest_sha256": manifest_hash, "predictions_sha256": reader.digest(predictions_path),
                "criteria_sha256": reader.digest(criteria_path) if criteria_path else None,
                "corpus": corpus, "per_file": per_file, "per_condition": per_condition,
                "per_split": per_split, "holdout": per_split["holdout"],
                "holdout_per_condition": holdout_conditions, "pooled": _pooled(per_file.values()),
                "evidence_hashes": {str(path): digest for path, digest in reader.hashes.items()},
                "protected_paths": sorted(str(path) for path in reader.protected_paths)}
    except ValidationError as error:
        return {**provenance, "gate": "INVALID", "alignment_gate": "INVALID", "confidence_gate": "INVALID",
                "needs_review": True, "invalid_count": 1, "errors": [str(error)],
                "evidence_hashes": {str(path): digest for path, digest in reader.hashes.items()},
                "protected_paths": sorted(str(path) for path in reader.protected_paths)}


def _write_new_report(target, output):
    """Publish completed UTF-8 bytes atomically, refusing every existing target/alias."""
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n",
                                         prefix=".speech-word-report-", suffix=".tmp",
                                         dir=target.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(output)
            stream.flush()
            os.fsync(stream.fileno())
        # Same-directory hardlink creation is exclusive: existing files, links and
        # concurrent creators raise FileExistsError without modifying their bytes.
        os.link(temporary, target)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--predictions", type=Path, required=True)
    parser.add_argument("--criteria", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args(argv)
    result = evaluate(args.manifest, args.predictions, args.criteria)
    output = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    if args.report:
        # Never overwrite immutable source/evidence while writing the result.
        target = args.report.absolute()
        protected = {args.manifest.resolve(), args.predictions.resolve()}
        if args.criteria:
            protected.add(args.criteria.resolve())
        protected.update(Path(path) for path in result.get("evidence_hashes", {}))
        protected.update(Path(path) for path in result.get("protected_paths", []))
        if target.resolve() in protected:
            parser.error("report path would overwrite input evidence")
        try:
            _write_new_report(target, output)
        except OSError as error:
            parser.error(f"cannot create new report without overwriting an existing path: {error}")
    print(output, end="")
    return {"PASS": 0, "FAIL": 1, "INVALID": 2, "OPEN": 3}[result["gate"]]


if __name__ == "__main__":
    raise SystemExit(main())
