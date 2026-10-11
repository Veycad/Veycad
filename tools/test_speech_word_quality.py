"""Controlled validator tests; generated fixtures are not a speech accuracy corpus."""

import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import wave

import speech_word_quality as quality


def word(text, start=0, end=100_000):
    return {"text": text, "start_us": start, "end_us": end}


class WordMetricsTest(unittest.TestCase):
    def test_nearest_rank_retains_outlier_and_both_boundaries(self):
        reference = [word(str(i), i * 1_000_000, i * 1_000_000 + 100_000)
                     for i in range(20)]
        hypothesis = [word(str(i), i * 1_000_000 + (600_000 if i == 19 else 0),
                           i * 1_000_000 + 100_000 + (700_000 if i >= 18 else 0))
                      for i in range(20)]
        report = quality.score_words(reference, hypothesis, 21_000_000)
        self.assertEqual({"median_us": 0.0, "p95_us": 0, "count": 20}, report["start"])
        self.assertEqual({"median_us": 0.0, "p95_us": 700_000, "count": 20}, report["end"])
        self.assertEqual(600_000, max(report["start_errors_us"]))
        self.assertEqual(20, report["matched"])

    def test_levenshtein_counts_substitution_deletion_insertion_and_misses(self):
        report = quality.score_words([word(x, i * 200_000, i * 200_000 + 100_000)
                                      for i, x in enumerate(["раз", "два", "три", "четыре"])],
                                     [word(x, i * 200_000, i * 200_000 + 100_000)
                                      for i, x in enumerate(["раз", "нет", "четыре", "пять"])],
                                     1_000_000)
        self.assertEqual((1, 1, 1, 2), (report["substitutions"], report["deletions"],
                                      report["insertions"], report["matched"]))
        self.assertEqual(0.75, report["wer"])
        self.assertEqual(0.5, report["matched_coverage"])
        self.assertEqual([1, 2], report["unmatched_reference_indices"])
        self.assertEqual([1, 3], report["unmatched_hypothesis_indices"])

    def test_empty_hypothesis_cannot_pass_with_null_percentiles(self):
        report = quality.score_words([word("речь")], [], 100_000)
        self.assertEqual((1.0, 0.0, 1), (report["wer"], report["matched_coverage"], report["deletions"]))
        self.assertIsNone(report["start"]["median_us"])
        self.assertIsNone(report["end"]["p95_us"])
        with self.assertRaises(quality.ValidationError):
            quality.score_words([], [], 100_000)

    def test_invalid_word_intervals_are_rejected_without_clipping(self):
        for invalid in [word("x", None, 1), word("x", -1, 1), word("x", 1, 1),
                        word("x", 2, 1), word("x", 0, 100_001), word("x", False, 1),
                        word("x", 0.0, 1), word("two words"), word("!")]:
            with self.subTest(invalid=invalid), self.assertRaises(quality.ValidationError):
                quality.score_words([word("x")], [invalid], 100_000)
        self.assertEqual(1, quality.score_words([word("x")], [word("x")], 100_000)["matched"])

    def test_unicode_normalization_keeps_real_repeats_and_yo(self):
        report = quality.score_words([word("ДА!", 0, 100_000), word("да", 100_000, 200_000),
                                      word("ёлка", 200_000, 300_000)],
                                     [word("да", 0, 100_000), word("да", 100_000, 200_000),
                                      word("елка", 200_000, 300_000)], 300_000)
        self.assertEqual((2, 1), (report["matched"], report["substitutions"]))

    def test_even_median_is_not_nearest_rank_or_rounded(self):
        report = quality.score_words([word("а", 0, 10), word("б", 20, 30)],
                                     [word("а", 1, 11), word("б", 22, 32)], 40)
        self.assertEqual({"median_us": 1.5, "p95_us": 2, "count": 2}, report["start"])

    def test_nonmonotonic_words_are_not_reordered_for_matching(self):
        with self.assertRaises(quality.ValidationError):
            quality.score_words([word("а", 0, 10)], [word("а", 20, 30), word("а", 10, 20)], 40)

    def test_symbol_only_or_invalid_unicode_are_not_lexical_words(self):
        for text in ("🦊", "\ud800", "\u0301"):
            with self.subTest(text=repr(text)), self.assertRaises(quality.ValidationError):
                quality.score_words([word("а")], [word(text)], 100_000)


class CorpusFixture:
    """Synthetic temporary bytes only exercise the validator, never the real gate."""
    def __init__(self, directory):
        self.root = Path(directory)
        self.backend = "48f628a84833905ee4a0658ee6d4a5c915ce1997"
        self.manifest = {
            "schema_version": 1, "corpus_kind": "human_annotated_ru",
            "created_at": "2026-10-01T00:00:00Z",
            "holdout_first_access_at": "2026-10-03T00:00:00Z",
            "corpus_review": {"approved": True, "artifact": self.artifact("review.txt", b"UNIT TEST ONLY")},
            "candidate": {"backend_commit": self.backend,
                          "backend_patch": self.artifact("patch.diff", b"unit patch"),
                          "model": self.artifact("model.bin", b"unit model"),
                          "apk": self.artifact("app.apk", b"unit apk"),
                          "calibration": self.artifact("calibration.json", b"unit calibration")},
            "recordings": [],
        }
        for index in range(10):
            for condition in quality.CONDITIONS:
                recording_id = f"s{index}-{condition}"
                audio_path = self.root / f"{recording_id}.wav"
                with wave.open(str(audio_path), "wb") as stream:
                    stream.setnchannels(1)
                    stream.setsampwidth(2)
                    stream.setframerate(16_000)
                    # Different controlled PCM bytes, never a gold accuracy corpus.
                    identity = index * 4 + quality.CONDITIONS.index(condition)
                    stream.writeframes(bytes([identity, 0]) * 16_000)
                reference = json.dumps({"words": [word("речь")]}, ensure_ascii=False).encode("utf-8")
                self.manifest["recordings"].append({
                    "id": recording_id, "speaker_id": f"s{index}",
                    "split": "pilot" if index < 8 else "holdout", "condition": condition,
                    "audio": {**self.existing(audio_path), "sample_rate": 16_000,
                              "channels": 1, "sample_width": 2, "input_frames": 16_000},
                    "rights": {"kind": "license", "artifact": self.artifact(f"{recording_id}-rights.txt", b"UNIT TEST ONLY")},
                    "annotations": [{"annotator_id": "a", "independent": True,
                                     "artifact": self.artifact(f"{recording_id}-a.json", reference)},
                                    {"annotator_id": "b", "independent": True,
                                     "artifact": self.artifact(f"{recording_id}-b.json", reference)}],
                    "adjudication": {"adjudicator_id": "c",
                                     "artifact": self.artifact(f"{recording_id}-gold.json", reference)},
                })
        self.criteria = {
            "schema_version": 1, "registered_at": "2026-10-02T00:00:00Z",
            "protocol_version": 1,
            "unicode_version": quality.unicodedata.unidata_version,
            "validator_sha256": hashlib.sha256(Path(quality.__file__).read_bytes()).hexdigest(),
            "protocol_sha256": hashlib.sha256(
                (Path(quality.__file__).resolve().parents[1] / "docs/testing/speech-word-quality.md").read_bytes()
            ).hexdigest(),
            "criteria": {"wer_max": 0.20, "matched_coverage_min": 0.80,
                         "start_median_max_us": 200_000, "start_p95_max_us": 500_000,
                         "end_median_max_us": 200_000, "end_p95_max_us": 500_000},
            "acceptance": {"approved": True, "artifact": self.artifact("acceptance.txt", b"UNIT TEST ONLY")},
            "pilot_report": self.artifact("pilot.json", b"UNIT TEST ONLY"),
        }
        self.predictions = {"schema_version": 1, "backend_commit": self.backend,
                            "backend_patch_sha256": self.manifest["candidate"]["backend_patch"]["sha256"],
                            "model_sha256": self.manifest["candidate"]["model"]["sha256"],
                            "apk_sha256": self.manifest["candidate"]["apk"]["sha256"],
                            "calibration_sha256": self.manifest["candidate"]["calibration"]["sha256"],
                            "alignment_kind": "ACOUSTIC_WORD", "confidence_kind": "CALIBRATED_WORD",
                            "generated_at": "2026-10-03T01:00:00Z",
                            "recordings": [{"id": row["id"], "words": [word("речь")]}
                                           for row in self.manifest["recordings"]]}
        self.save()

    def existing(self, path):
        return {"path": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def artifact(self, name, contents):
        path = self.root / name
        path.write_bytes(contents)
        return self.existing(path)

    def save(self):
        self.manifest_path = self.root / "manifest.json"
        self.manifest_path.write_text(json.dumps(self.manifest, ensure_ascii=False), encoding="utf-8")
        digest = hashlib.sha256(self.manifest_path.read_bytes()).hexdigest()
        self.predictions["manifest_sha256"] = digest
        self.criteria["manifest_sha256"] = digest
        self.predictions_path = self.root / "predictions.json"
        self.criteria_path = self.root / "criteria.json"
        self.predictions_path.write_text(json.dumps(self.predictions, ensure_ascii=False), encoding="utf-8")
        self.criteria_path.write_text(json.dumps(self.criteria), encoding="utf-8")

    def evaluate(self, criteria=True):
        self.save()
        return quality.evaluate(self.manifest_path, self.predictions_path,
                                self.criteria_path if criteria else None)


class WordGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.fixture = CorpusFixture(self.temp.name)

    def test_complete_bound_evidence_reports_counts_per_file_condition_and_split(self):
        report = self.fixture.evaluate()
        self.assertEqual("PASS", report["gate"])
        self.assertEqual((40, 8, 2), (report["corpus"]["recordings"],
                                     report["corpus"]["pilot_speakers"], report["corpus"]["holdout_speakers"]))
        self.assertEqual(40, len(report["per_file"]))
        self.assertEqual(10, report["per_condition"]["quiet"]["reference_count"])
        self.assertEqual(8, report["holdout"]["matched"])
        self.assertEqual(40, report["pooled"]["reference_count"])
        self.assertEqual(0, report["invalid_count"])

    def test_report_pins_validator_protocol_and_unicode_normalization_versions(self):
        report = self.fixture.evaluate()
        self.assertEqual(1, report.get("protocol_version"))
        validator = Path(quality.__file__).resolve()
        protocol = validator.parents[1] / "docs/testing/speech-word-quality.md"
        self.assertEqual(hashlib.sha256(validator.read_bytes()).hexdigest(), report.get("validator_sha256"))
        self.assertEqual(hashlib.sha256(protocol.read_bytes()).hexdigest(), report.get("protocol_sha256"))
        self.assertEqual(quality.unicodedata.unidata_version, report.get("unicode_version"))

    def test_outliers_are_not_hidden_by_pooled_pilot(self):
        for prediction in self.fixture.predictions["recordings"][-8:]:
            prediction["words"] = [word("речь", 0, 800_000)]
        report = self.fixture.evaluate()
        self.assertEqual("FAIL", report["gate"])
        self.assertEqual(700_000, report["holdout"]["end"]["p95_us"])
        self.assertEqual(0, report["holdout"]["start"]["p95_us"])

    def test_missing_words_fail_coverage_despite_exact_remaining_timings(self):
        self.fixture.predictions["recordings"][-1]["words"] = []
        self.fixture.predictions["recordings"][-2]["words"] = []
        report = self.fixture.evaluate()
        self.assertEqual("FAIL", report["gate"])
        self.assertEqual((0.25, 0.75), (report["holdout"]["wer"], report["holdout"]["matched_coverage"]))
        self.assertEqual(2, report["holdout"]["deletions"])

    def test_unvalidated_alignment_or_preregistration_keep_alignment_gate_open(self):
        for field, value in [("alignment_kind", "TOKEN_HEURISTIC"),
                             ("alignment_kind", "SEGMENT_ONLY")]:
            with self.subTest(field=field, value=value):
                original = copy.deepcopy(self.fixture.predictions)
                self.fixture.predictions[field] = value
                self.assertEqual("OPEN", self.fixture.evaluate()["gate"])
                self.fixture.predictions = original
        self.assertEqual("OPEN", self.fixture.evaluate(criteria=False)["gate"])
        self.fixture.criteria["acceptance"]["approved"] = False
        self.assertEqual("OPEN", self.fixture.evaluate()["gate"])

    def test_measured_word_alignment_does_not_claim_confidence_calibration(self):
        for kind in ("UNAVAILABLE", "TOKEN_AGGREGATE", "CALIBRATED_WORD"):
            self.fixture.predictions["confidence_kind"] = kind
            report = self.fixture.evaluate()
            self.assertEqual("PASS", report["gate"])
            self.assertEqual("PASS", report["alignment_gate"])
            self.assertEqual("OPEN", report["confidence_gate"])
            self.assertTrue(report["needs_review"])
        del self.fixture.manifest["candidate"]["calibration"]
        self.fixture.predictions["confidence_kind"] = "UNAVAILABLE"
        self.assertEqual("PASS", self.fixture.evaluate()["gate"])

    def test_confidence_requires_separate_human_review_bound_to_calibration(self):
        candidate = self.fixture.manifest["candidate"]
        candidate["calibration_review"] = {
            "approved": False, "reviewer_id": "unit-reviewer",
            "calibration_sha256": candidate["calibration"]["sha256"],
            "artifact": self.fixture.artifact("calibration-review.txt", b"UNIT TEST ONLY"),
        }
        self.assertEqual("OPEN", self.fixture.evaluate().get("confidence_gate"))
        candidate["calibration_review"]["approved"] = True
        report = self.fixture.evaluate()
        self.assertEqual("PASS", report["confidence_gate"])
        self.assertFalse(report["needs_review"])
        candidate["calibration_review"]["calibration_sha256"] = "f" * 64
        self.assertEqual("INVALID", self.fixture.evaluate()["gate"])

    def test_late_or_loosened_criteria_cannot_pass(self):
        self.fixture.criteria["registered_at"] = "2026-10-03T00:00:00Z"
        self.assertEqual("INVALID", self.fixture.evaluate()["gate"])
        self.fixture.criteria["registered_at"] = "2026-10-02T00:00:00Z"
        self.fixture.criteria["criteria"]["wer_max"] = 0.5
        self.assertEqual("INVALID", self.fixture.evaluate()["gate"])

    def test_preregistration_binds_scoring_protocol_before_holdout(self):
        original = copy.deepcopy(self.fixture.criteria)
        for field in ("protocol_version", "validator_sha256", "protocol_sha256", "unicode_version"):
            with self.subTest(missing=field):
                self.fixture.criteria = copy.deepcopy(original)
                del self.fixture.criteria[field]
                self.assertEqual("OPEN", self.fixture.evaluate()["gate"])
            with self.subTest(changed=field):
                self.fixture.criteria = copy.deepcopy(original)
                self.fixture.criteria[field] = 2 if field == "protocol_version" else "f" * 64
                self.assertEqual("INVALID", self.fixture.evaluate()["gate"])

    def test_malformed_and_out_of_bounds_words_fail_the_whole_gate(self):
        self.fixture.predictions["recordings"][0]["words"] = [word("речь", 0, 1_000_001)]
        report = self.fixture.evaluate()
        self.assertEqual("INVALID", report["gate"])
        self.assertNotIn("pooled", report)

    def test_tampered_real_hashes_and_wrong_backend_are_rejected(self):
        (self.fixture.root / "model.bin").write_bytes(b"different")
        self.assertEqual("INVALID", self.fixture.evaluate()["gate"])
        (self.fixture.root / "model.bin").write_bytes(b"unit model")
        self.fixture.predictions["backend_commit"] = "a" * 40
        self.assertEqual("INVALID", self.fixture.evaluate()["gate"])

    def test_unknown_backend_cannot_self_approve_by_changing_both_identifiers(self):
        self.fixture.manifest["candidate"]["backend_commit"] = "a" * 40
        self.fixture.predictions["backend_commit"] = "a" * 40
        self.assertEqual("INVALID", self.fixture.evaluate()["gate"])

    def test_empty_missing_duplicate_corpus_or_predictions_cannot_pass(self):
        original = copy.deepcopy(self.fixture.manifest)
        for records in [[], original["recordings"][:-1], original["recordings"] + [original["recordings"][0]]]:
            self.fixture.manifest["recordings"] = records
            self.assertEqual("INVALID", self.fixture.evaluate()["gate"])
        self.fixture.manifest = original
        self.fixture.predictions["recordings"].pop()
        self.assertEqual("INVALID", self.fixture.evaluate()["gate"])

    def test_holdout_speakers_are_disjoint_and_two_annotators_are_required(self):
        self.fixture.manifest["recordings"][-1]["speaker_id"] = "s0"
        self.assertEqual("INVALID", self.fixture.evaluate()["gate"])
        self.fixture.manifest["recordings"][-1]["speaker_id"] = "s9"
        self.fixture.manifest["recordings"][0]["annotations"][1]["annotator_id"] = "a"
        self.assertEqual("INVALID", self.fixture.evaluate()["gate"])

    def test_missing_rights_pilot_review_or_calibration_are_not_accuracy_evidence(self):
        del self.fixture.manifest["recordings"][0]["rights"]
        self.assertEqual("INVALID", self.fixture.evaluate()["gate"])
        self.fixture = CorpusFixture(self.temp.name)
        del self.fixture.criteria["pilot_report"]
        self.assertEqual("OPEN", self.fixture.evaluate()["gate"])
        self.fixture = CorpusFixture(self.temp.name)
        del self.fixture.manifest["candidate"]["calibration"]
        self.fixture.predictions["confidence_kind"] = "UNAVAILABLE"
        report = self.fixture.evaluate()
        self.assertEqual("PASS", report["gate"])
        self.assertEqual("OPEN", report["confidence_gate"])

    def test_duplicate_audio_hash_or_path_is_not_forty_independent_recordings(self):
        original = copy.deepcopy(self.fixture.manifest["recordings"][1]["audio"])
        first = self.fixture.manifest["recordings"][0]["audio"]
        self.fixture.manifest["recordings"][1]["audio"] = copy.deepcopy(first)
        self.assertEqual("INVALID", self.fixture.evaluate()["gate"])
        self.fixture.manifest["recordings"][1]["audio"] = original
        second_path = self.fixture.root / original["path"]
        second_path.write_bytes((self.fixture.root / first["path"]).read_bytes())
        self.fixture.manifest["recordings"][1]["audio"].update(self.fixture.existing(second_path))
        self.assertEqual("INVALID", self.fixture.evaluate()["gate"])

    def test_template_is_incomplete_and_cli_has_nonzero_exit_for_open_gate(self):
        template = Path(__file__).resolve().parents[1] / "docs/testing/speech-word-manifest.template.json"
        report = quality.evaluate(template, self.fixture.predictions_path, self.fixture.criteria_path)
        self.assertEqual("INVALID", report["gate"])
        self.fixture.predictions["alignment_kind"] = "TOKEN_HEURISTIC"
        self.fixture.save()
        command = [sys.executable, str(Path(quality.__file__)), "--manifest", str(self.fixture.manifest_path),
                   "--predictions", str(self.fixture.predictions_path), "--criteria", str(self.fixture.criteria_path)]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(3, result.returncode, result.stderr)
        self.assertEqual("OPEN", json.loads(result.stdout)["gate"])

    def test_fractional_pcm_duration_uses_floor_and_accepts_exact_end(self):
        recording = self.fixture.manifest["recordings"][0]
        audio_path = self.fixture.root / recording["audio"]["path"]
        with wave.open(str(audio_path), "wb") as stream:
            stream.setnchannels(1)
            stream.setsampwidth(2)
            stream.setframerate(16_000)
            stream.writeframes(b"\0\0" * 16_001)
        recording["audio"].update(self.fixture.existing(audio_path))
        recording["audio"]["input_frames"] = 16_001
        self.fixture.predictions["recordings"][0]["words"] = [word("речь", 0, 1_000_062)]
        report = self.fixture.evaluate()
        self.assertEqual(1_000_062, report["per_file"][recording["id"]]["duration_us"])
        self.assertNotEqual("INVALID", report["gate"])
        self.fixture.predictions["recordings"][0]["words"] = [word("речь", 0, 1_000_063)]
        self.assertEqual("INVALID", self.fixture.evaluate()["gate"])

    def test_report_cannot_overwrite_evidence_even_after_invalid_inputs(self):
        model = self.fixture.root / "model.bin"
        original = model.read_bytes()
        for kind in ("human_annotated_ru", "INCOMPLETE_TEMPLATE_NOT_GOLD"):
            self.fixture.manifest["corpus_kind"] = kind
            self.fixture.predictions["recordings"][0]["words"] = [word("речь", 0, 1_000_001)]
            self.fixture.save()
            result = subprocess.run([sys.executable, str(Path(quality.__file__)),
                                     "--manifest", str(self.fixture.manifest_path),
                                     "--predictions", str(self.fixture.predictions_path),
                                     "--criteria", str(self.fixture.criteria_path), "--report", str(model)],
                                    capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(original, model.read_bytes())

    def test_duplicate_json_key_is_invalid_instead_of_silently_changing_truth(self):
        self.fixture.manifest_path.write_text('{"schema_version":1,"schema_version":2}', encoding="utf-8")
        report = quality.evaluate(self.fixture.manifest_path, self.fixture.predictions_path)
        self.assertEqual("INVALID", report["gate"])
        self.assertIn("duplicate key", report["errors"][0])

    def test_report_hardlink_cannot_truncate_source_artifact(self):
        model = self.fixture.root / "model.bin"
        report_path = self.fixture.root / "report-hardlink.json"
        os.link(model, report_path)
        self.assertTrue(os.path.samefile(model, report_path))
        original = model.read_bytes()
        result = subprocess.run([sys.executable, str(Path(quality.__file__)),
                                 "--manifest", str(self.fixture.manifest_path),
                                 "--predictions", str(self.fixture.predictions_path),
                                 "--criteria", str(self.fixture.criteria_path), "--report", str(report_path)],
                                capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(original, model.read_bytes())

    def test_report_is_created_once_without_overwriting_any_existing_file(self):
        report_path = self.fixture.root / "new-report.json"
        command = [sys.executable, str(Path(quality.__file__)),
                   "--manifest", str(self.fixture.manifest_path),
                   "--predictions", str(self.fixture.predictions_path),
                   "--criteria", str(self.fixture.criteria_path), "--report", str(report_path)]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("PASS", json.loads(report_path.read_text(encoding="utf-8"))["gate"])
        original = b"previous independent report"
        report_path.write_bytes(original)
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(original, report_path.read_bytes())


if __name__ == "__main__":
    unittest.main()
