import copy
import hashlib
import json
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

from quality_release_matrix import MUSIC_FILE, inspect
from quality_review_form import prepare_negative
from quality_product_scope import PRODUCTS, all_active_scope, sigma_paused_scope
from quality_holdout_seal import make_seal
from quality_test_support import fixture_baseline
from quality_test_support import independent_audio_report, review, unknown_headroom_result
from quality_test_support import synthetic_inspector


class QualityReleaseMatrixTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "media").mkdir()
        self.media = []

    def add(self, name, role="holdout"):
        payload = name.encode("ascii")
        (self.root / "media" / name).write_bytes(payload)
        item = {"id": name, "file": name,
                "sha256": hashlib.sha256(payload).hexdigest(), "bytes": len(payload),
                "duration_s": 20, "fps_bucket": 30, "frame_rate_mode": "cfr",
                "container": "mp4", "camera_model": None, "tags": ["portrait"],
                "parent_source": name, "source_page": "https://example.test/",
                "license": "test", "role": role}
        if role == "pilot":
            item["pilot_note"] = "exposed"
        self.media.append(item)
        return item

    def manifest(self):
        return {"schema_version": 1, "state": "candidate_not_release_ready",
                "media_root": "media", "media": self.media,
                "required_tags": ["portrait", "full_body", "multiple_people",
                                  "no_people", "dark", "backlit", "low_quality",
                                  "static_camera", "intense_motion"],
                "required_single_source_fps": [24, 25, 30, 60],
                "required_containers": ["mp4", "webm", "mov"],
                "required_frame_rate_modes": ["cfr", "vfr"],
                "minimum_verified_camera_models": 2}

    def case(self, media_id):
        return {"id": "case", "recipe": "SIGMA", "media_ids": [media_id],
                "engine_commit": "a" * 40, "apk_sha256": "b" * 64,
                "device_model": "test phone", "android_api": 36,
                "result_file": "run.result", "review_file": "review.json",
                "outcome": "positive"}

    def test_empty_matrix_is_not_release_ready(self):
        self.add("source.mp4")
        report = inspect({"schema_version": 1, "cases": []}, self.manifest(), self.root)
        self.assertFalse(report["ready"])
        self.assertTrue(any("SIGMA: 0/3" in gap for gap in report["gaps"]))
        self.assertIn("FEAR_STROBE: negative insufficient_motion not verified", report["gaps"])
        self.assertIn("FEAR_STROBE: negative insufficient_distinct_moments not verified", report["gaps"])

    def scoped_manifest(self, *, sealed=False):
        self.add("source.mp4")
        manifest = dict(self.manifest(), product_scope=sigma_paused_scope())
        if sealed:
            fixture_baseline(self.root)
            manifest["state"] = "sealed"
            manifest["seal"] = make_seal(manifest, self.root, "build/test.apk", "a" * 40)
        return manifest

    def scoped_matrix(self):
        return {"schema_version": 2, "product_scope": sigma_paused_scope(), "cases": []}

    def assert_not_ready_four_products(self, report):
        self.assertFalse(report["ready"])
        self.assertFalse(report["active_products_ready"])
        self.assertEqual(set(report["products"]), set(PRODUCTS))

    def test_scoped_candidate_defers_sigma_without_claiming_any_release(self):
        report = inspect(self.scoped_matrix(), self.scoped_manifest(), self.root)
        self.assert_not_ready_four_products(report)
        self.assertFalse(report["scope_bound_before_disclosure"])
        self.assertEqual(report["paused_products"], ["SIGMA"])
        self.assertEqual(report["products"]["SIGMA"]["status"], "paused_not_release_ready")
        self.assertFalse(report["products"]["SIGMA"]["requirements_passed"])
        self.assertTrue(any("SIGMA: 0/3" in gap for gap in report["products"]["SIGMA"]["gaps"]))
        self.assertFalse(any(gap.startswith("SIGMA:") for gap in report["active_gaps"]))
        self.assertIn("holdout is not sealed", report["active_gaps"])

    def test_legacy_matrix_cannot_adopt_pause_or_hide_product_keys_on_invalid_schema(self):
        manifest = self.scoped_manifest()
        for matrix in ({"schema_version": 1, "cases": []},
                       {"schema_version": 99, "cases": []},
                       {"schema_version": True, "cases": []},
                       dict(self.scoped_matrix(), cases="not a list")):
            with self.subTest(matrix=matrix):
                report = inspect(matrix, manifest, self.root)
                self.assert_not_ready_four_products(report)
                self.assertFalse(report["scope_bound_before_disclosure"])
                self.assertTrue(report["errors"])

    def test_matrix_scope_mismatch_cannot_display_bound_flag_on_real_seal(self):
        manifest = self.scoped_manifest(sealed=True)
        valid = inspect(self.scoped_matrix(), manifest, self.root)
        self.assertTrue(valid["scope_bound_before_disclosure"])
        for scope in (None, all_active_scope(), {}, {"products": {}}):
            with self.subTest(scope=scope):
                matrix = dict(self.scoped_matrix(), product_scope=scope)
                report = inspect(matrix, manifest, self.root)
                self.assert_not_ready_four_products(report)
                self.assertFalse(report["scope_bound_before_disclosure"])

    def test_post_seal_scope_removal_or_change_is_detected_by_actual_verifier(self):
        manifest = self.scoped_manifest(sealed=True)
        for mode in ("removed", "active", "other-pause"):
            with self.subTest(mode=mode):
                changed = copy.deepcopy(manifest)
                if mode == "removed":
                    changed.pop("product_scope")
                    matrix = {"schema_version": 1, "cases": []}
                else:
                    if mode == "active":
                        changed["product_scope"] = all_active_scope()
                    else:
                        changed["product_scope"]["products"]["FEAR_STROBE"] = {"state": "paused"}
                    matrix = dict(self.scoped_matrix(), product_scope=changed["product_scope"])
                report = inspect(matrix, changed, self.root)
                self.assert_not_ready_four_products(report)
                self.assertFalse(report["scope_bound_before_disclosure"])
                self.assertTrue(any("metadata changed" in error for error in report["errors"]))

    def test_state_flag_or_changed_apk_cannot_supply_scope_binding(self):
        manifest = self.scoped_manifest(sealed=True)
        missing_seal = copy.deepcopy(manifest)
        missing_seal.pop("seal")
        report = inspect(self.scoped_matrix(), missing_seal, self.root)
        self.assert_not_ready_four_products(report)
        self.assertFalse(report["scope_bound_before_disclosure"])
        (self.root / "build/test.apk").write_bytes(b"changed APK")
        report = inspect(self.scoped_matrix(), manifest, self.root)
        self.assert_not_ready_four_products(report)
        self.assertFalse(report["scope_bound_before_disclosure"])

    def test_paused_sigma_case_is_rejected_before_file_or_coverage_access(self):
        manifest = self.scoped_manifest()
        matrix = dict(self.scoped_matrix(), cases=[self.case("source.mp4")])
        report = inspect(matrix, manifest, self.root)
        self.assert_not_ready_four_products(report)
        self.assertEqual(report["verified_case_count"], 0)
        self.assertTrue(any("paused product" in error for error in report["errors"]))
        self.assertFalse(any("result: file missing" in error for error in report["errors"]))
        self.assertIn("unexercised material tag: portrait", report["active_gaps"])

    def positive_audio_case(self):
        source = self.add("source.mp4")
        output = self.root / "case.mp4"
        output.write_bytes(b"synthetic exported video; decoding is mocked only in unit tests")
        output_hash = hashlib.sha256(output.read_bytes()).hexdigest()
        music = self.root / "app/src/main/res/raw/heartbeat_author.m4a"
        music.parent.mkdir(parents=True)
        music.write_bytes(b"synthetic authored score")
        result = unknown_headroom_result()
        result.update(source_sha256=source["sha256"], output_sha256=output_hash,
                      render_source_sha256=source["sha256"],
                      runtime_apk_sha256="b" * 64, runtime_split_apk_count="0",
                      runtime_device_model="test phone", runtime_android_api="36",
                      music_sha256=hashlib.sha256(music.read_bytes()).hexdigest())
        (self.root / "case.mp4.result").write_text(
            "\n".join(f"{key}={value}" for key, value in result.items()), encoding="utf-8")
        (self.root / "review.json").write_text(json.dumps(review(output_hash)), encoding="utf-8")
        audio = independent_audio_report()
        audio["output_sha256"] = output_hash
        (self.root / "case.audio.json").write_text(json.dumps(audio), encoding="utf-8")
        case = self.case(source["id"])
        inspector = self.root / "case-inspector.json"
        inspector.write_text(json.dumps(synthetic_inspector(output, result)), encoding="utf-8")
        case.update(recipe="HEARTBEAT", result_file="case.mp4.result", output_file="case.mp4",
                    output_sha256=output_hash, audio_report_file="case.audio.json",
                    inspector_file=inspector.name, inspector_sha256=hashlib.sha256(inspector.read_bytes()).hexdigest())
        return case, audio

    def test_positive_audio_is_remeasured_and_exact_unknown_can_be_resolved(self):
        case, audio = self.positive_audio_case()
        decoder = self.root / "synthetic-ffmpeg.exe"
        with patch("quality_audio_report.measure", return_value=audio) as measured:
            report = inspect({"schema_version": 1, "cases": [case]}, self.manifest(), self.root, decoder)
        measured.assert_called_once_with(self.root / "case.mp4", decoder, "HEARTBEAT")
        self.assertEqual(report["errors"], [])
        self.assertEqual(report["verified_case_count"], 1)
        self.assertFalse(report["ready"])  # A synthetic single case does not close corpus/matrix gaps.

    def test_positive_identity_is_checked_before_audio_or_mocked_assessment(self):
        case, _ = self.positive_audio_case()
        for changes in ({"reviewer": "   "}, {"reviewer": ["human"]},
                        {"reviewed_at": "not-a-date"}, {"reviewed_at": "2026-09-25"},
                        {"reviewed_at": "2026-09-25T12:00:00"},
                        {"reviewed_at": "9999-12-31T23:59:59Z"}, {"reviewed_at": True}):
            with self.subTest(changes=changes):
                form = dict(review(case["output_sha256"]), **changes)
                (self.root / "review.json").write_text(json.dumps(form), encoding="utf-8")
                with patch("quality_audio_report.measure") as measured, \
                        patch("quality_release_matrix.assess", return_value={"machine_and_human_pass": True}) as assessed:
                    report = inspect({"schema_version": 1, "cases": [case]}, self.manifest(), self.root,
                                     self.root / "synthetic-ffmpeg.exe")
                measured.assert_not_called()
                assessed.assert_not_called()
                self.assertEqual(report["verified_case_count"], 0)
                self.assertTrue(any("identity/date missing or invalid" in error for error in report["errors"]))

    def test_saved_inspector_is_required_before_audio_or_mocked_assessment(self):
        case, _ = self.positive_audio_case()
        for changes in ({"inspector_file": None}, {"inspector_file": "missing.json"},
                        {"inspector_sha256": None}, {"inspector_sha256": True},
                        {"inspector_sha256": "0" * 64}):
            with self.subTest(changes=changes):
                changed = dict(case, **changes)
                with patch("quality_audio_report.measure") as measured, \
                        patch("quality_release_matrix.assess", return_value={"machine_and_human_pass": True}) as assessed:
                    report = inspect({"schema_version": 1, "cases": [changed]}, self.manifest(), self.root,
                                     self.root / "synthetic-ffmpeg.exe")
                measured.assert_not_called()
                assessed.assert_not_called()
                self.assertEqual(report["verified_case_count"], 0)
                self.assertTrue(any("inspector" in error for error in report["errors"]))

    def test_hash_matching_but_inconsistent_inspector_cannot_supply_positive_case(self):
        case, _ = self.positive_audio_case()
        path = self.root / case["inspector_file"]
        original = json.loads(path.read_text())
        for mode in ("shape", "accepted", "graph", "count", "output", "container"):
            with self.subTest(mode=mode):
                saved = copy.deepcopy(original)
                if mode == "shape":
                    saved = []
                elif mode == "accepted":
                    saved["summary"]["accepted"] = False
                elif mode == "graph":
                    saved["graph"]["generator"] = "other"
                elif mode == "count":
                    saved["frames"].pop()
                elif mode == "output":
                    saved["mp4_name"] = "other.mp4"
                else:
                    saved["container"]["issues"] = ["actual container problem"]
                path.write_text(json.dumps(saved), encoding="utf-8")
                changed = dict(case, inspector_sha256=hashlib.sha256(path.read_bytes()).hexdigest())
                with patch("quality_audio_report.measure") as measured, \
                        patch("quality_release_matrix.assess", return_value={"machine_and_human_pass": True}) as assessed:
                    report = inspect({"schema_version": 1, "cases": [changed]}, self.manifest(), self.root,
                                     self.root / "synthetic-ffmpeg.exe")
                measured.assert_not_called()
                assessed.assert_not_called()
                self.assertEqual(report["verified_case_count"], 0)
                self.assertTrue(any("inspector" in error for error in report["errors"]))

    def test_saved_inspector_path_cannot_escape_repository(self):
        case, _ = self.positive_audio_case()
        with tempfile.TemporaryDirectory() as outside:
            path = Path(outside) / "external-inspector.json"
            path.write_bytes((self.root / case["inspector_file"]).read_bytes())
            changed = dict(case, inspector_file=str(path))
            report = inspect({"schema_version": 1, "cases": [changed]}, self.manifest(), self.root)
        self.assertEqual(report["verified_case_count"], 0)
        self.assertTrue(any("inspector: file missing or outside repository" in error for error in report["errors"]))

    def test_accepted_flags_and_matching_hash_cannot_hide_missing_intent_or_decoded_self_copy(self):
        case, _ = self.positive_audio_case()
        path = self.root / case["inspector_file"]
        original = json.loads(path.read_text())
        for mode in ("missing-policy", "unknown-policy", "missing-method", "null-primary",
                     "null-secondary", "equal-secondary", "false-dual"):
            with self.subTest(mode=mode):
                saved = copy.deepcopy(original)
                frame = saved["frames"][705]
                frame.update(layer_kind="DOUBLE_EXPOSURE", layer_opacity=.62, dual_decoder=True,
                             decoded_secondary_source_us=frame["decoded_source_us"] - 66_656)
                if mode == "missing-policy":
                    del saved["graph"]["temporal_layer_policy"]
                elif mode == "unknown-policy":
                    saved["graph"]["temporal_layer_policy"] = "unknown-v1"
                elif mode == "missing-method":
                    del saved["temporal_decoded_evidence_method"]
                elif mode == "null-primary":
                    frame["decoded_source_us"] = None
                elif mode == "null-secondary":
                    frame["decoded_secondary_source_us"] = None
                elif mode == "equal-secondary":
                    frame["decoded_secondary_source_us"] = frame["decoded_source_us"]
                else:
                    frame["dual_decoder"] = False
                path.write_text(json.dumps(saved), encoding="utf-8")
                changed = dict(case, inspector_sha256=hashlib.sha256(path.read_bytes()).hexdigest())
                with patch("quality_audio_report.measure") as measured, \
                        patch("quality_release_matrix.assess", return_value={"machine_and_human_pass": True}) as assessed:
                    report = inspect({"schema_version": 1, "cases": [changed]}, self.manifest(), self.root,
                                     self.root / "synthetic-ffmpeg.exe")
                measured.assert_not_called()
                assessed.assert_not_called()
                self.assertEqual(report["verified_case_count"], 0)
                self.assertTrue(any("inspector" in error for error in report["errors"]))

    def test_valid_identity_does_not_replace_sealed_review_chronology(self):
        case, _ = self.positive_audio_case()
        manifest = self.manifest()
        manifest.update(state="sealed", seal={"engine_commit": case["engine_commit"],
                                              "apk_sha256": case["apk_sha256"]})
        with patch("quality_holdout_seal.review_after_seal", return_value=False) as chronology:
            report = inspect({"schema_version": 1, "cases": [case]}, manifest, self.root)
        chronology.assert_called_once()
        self.assertEqual(report["verified_case_count"], 0)
        self.assertTrue(any("review predates seal" in error for error in report["errors"]))

    def test_numeric_heartbeat_repeat_defect_cannot_enter_matrix_via_audio_resolution(self):
        case, audio = self.positive_audio_case()
        result_path = self.root / "case.mp4.result"
        original = result_path.read_text(encoding="utf-8")
        self.assertIn("repeated_source_ratio=0.0", original)
        result_path.write_text(original.replace("repeated_source_ratio=0.0",
                                               "repeated_source_ratio=0.029531915"), encoding="utf-8")
        with patch("quality_audio_report.measure", return_value=audio):
            report = inspect({"schema_version": 1, "cases": [case]}, self.manifest(), self.root,
                             self.root / "synthetic-ffmpeg.exe")
        self.assertEqual(report["verified_case_count"], 0)
        self.assertFalse(report["ready"])
        self.assertFalse(report["active_products_ready"])
        self.assertTrue(any("repeated_source_ratio: 0.029531915 > 0.02" in error
                            for error in report["errors"]))

    def test_saved_audio_claim_cannot_replace_actual_remeasurement(self):
        case, audio = self.positive_audio_case()
        changed = dict(audio, sample_peak=1.2, passed=False, issues=["audio-over-full-scale"])
        with patch("quality_audio_report.measure", return_value=changed):
            report = inspect({"schema_version": 1, "cases": [case]}, self.manifest(), self.root,
                             self.root / "synthetic-ffmpeg.exe")
        self.assertEqual(report["verified_case_count"], 0)
        self.assertTrue(any("differs from actual MP4" in error for error in report["errors"]))

    def test_run_from_another_build_cannot_supply_sealed_case(self):
        case, _ = self.positive_audio_case()
        manifest = self.manifest()
        manifest.update(state="sealed", seal={"engine_commit": "c" * 40,
                                              "apk_sha256": "d" * 64})
        report = inspect({"schema_version": 1, "cases": [case]}, manifest, self.root)
        self.assertEqual(report["verified_case_count"], 0)
        self.assertTrue(any("build differs from pre-disclosure" in error for error in report["errors"]))

    def test_review_of_another_export_cannot_supply_positive_case(self):
        case, audio = self.positive_audio_case()
        (self.root / "review.json").write_text(json.dumps(review("c" * 64)), encoding="utf-8")
        with patch("quality_audio_report.measure", return_value=audio):
            report = inspect({"schema_version": 1, "cases": [case]}, self.manifest(), self.root,
                             self.root / "synthetic-ffmpeg.exe")
        self.assertEqual(report["verified_case_count"], 0)
        self.assertTrue(any("human review not bound" in error for error in report["errors"]))

    def test_transformed_or_unknown_input_cannot_supply_independent_case(self):
        case, _ = self.positive_audio_case()
        result_path = self.root / "case.mp4.result"
        original = result_path.read_text(encoding="utf-8")
        source_hash = self.media[0]["sha256"]
        for changed in (original.replace("static_source=false", "static_source=true"),
                        original.replace(f"render_source_sha256={source_hash}", "render_source_sha256=" + "d" * 64),
                        original.replace(f"render_source_sha256={source_hash}", "missing_field=true")):
            result_path.write_text(changed, encoding="utf-8")
            report = inspect({"schema_version": 1, "cases": [case]}, self.manifest(), self.root)
            self.assertEqual(report["verified_case_count"], 0)
            self.assertTrue(any("transformed or unverified" in error for error in report["errors"]))

    def test_runtime_identity_cannot_be_replaced_by_case_declaration(self):
        case, _ = self.positive_audio_case()
        result_path = self.root / "case.mp4.result"
        original = result_path.read_text(encoding="utf-8")
        for field, value in (("runtime_apk_sha256=" + "b" * 64, "runtime_apk_sha256=" + "c" * 64),
                             ("runtime_split_apk_count=0", "runtime_split_apk_count=1"),
                             ("runtime_device_model=test phone", "runtime_device_model=another phone"),
                             ("runtime_android_api=36", "runtime_android_api=28"),
                             ("runtime_apk_sha256=" + "b" * 64, "missing_runtime_apk=true")):
            with self.subTest(field=field):
                result_path.write_text(original.replace(field, value), encoding="utf-8")
                report = inspect({"schema_version": 1, "cases": [case]}, self.manifest(), self.root)
                self.assertEqual(report["verified_case_count"], 0)
                self.assertTrue(any("running APK/device" in error for error in report["errors"]))

    def test_matching_but_clipped_audio_cannot_pass_on_its_passed_flag(self):
        case, audio = self.positive_audio_case()
        audio["sample_peak"] = 1.2  # Deliberately inconsistent passed=true.
        (self.root / "case.audio.json").write_text(json.dumps(audio), encoding="utf-8")
        with patch("quality_audio_report.measure", return_value=audio):
            report = inspect({"schema_version": 1, "cases": [case]}, self.manifest(), self.root,
                             self.root / "synthetic-ffmpeg.exe")
        self.assertEqual(report["verified_case_count"], 0)
        self.assertTrue(any("silent/over-full-scale" in error for error in report["errors"]))

    def test_positive_cannot_pass_without_independent_decoder(self):
        case, _ = self.positive_audio_case()
        report = inspect({"schema_version": 1, "cases": [case]}, self.manifest(), self.root)
        self.assertEqual(report["verified_case_count"], 0)
        self.assertTrue(any("decoder required" in error for error in report["errors"]))

    def test_exposed_pilot_cannot_supply_release_case(self):
        item = self.add("pilot.mp4", role="pilot")
        report = inspect({"schema_version": 1, "cases": [self.case(item["id"])]},
                         self.manifest(), self.root)
        self.assertTrue(any("exposed pilot" in error for error in report["errors"]))

    def test_result_must_bind_to_manifest_source_hash(self):
        item = self.add("source.mp4")
        (self.root / "run.result").write_text(
            "recipe=SIGMA\nsource_sha256=" + "0" * 64 + "\n", encoding="utf-8")
        report = inspect({"schema_version": 1, "cases": [self.case(item["id"])]},
                         self.manifest(), self.root)
        self.assertTrue(any("not bound" in error for error in report["errors"]))

    def negative_material_case(self, recipe="SIGMA"):
        item = self.add("no_people.mp4")
        item["tags"] = ["no_people"]
        ids = [item["id"]]
        secondary_line = ""
        if recipe == "DUALITY_LOOP":
            secondary = self.add("second_no_people.mp4")
            secondary["tags"] = ["no_people"]
            ids.append(secondary["id"])
            secondary_line = f"secondary_source_sha256={secondary['sha256']}\n"
        music = self.root / "app/src/main/res/raw" / MUSIC_FILE[recipe]
        music.parent.mkdir(parents=True)
        music.write_bytes(b"score")
        (self.root / "case.mp4.result").write_text(
            f"status=material_rejected\nrecipe={recipe}\n"
            f"runtime_apk_sha256={'b' * 64}\nruntime_split_apk_count=0\n"
            "runtime_device_model=test phone\nruntime_android_api=36\n"
            "rejection_code=insufficient_human_evidence\n"
            f"source_sha256={item['sha256']}\n{secondary_line}"
            f"render_source_sha256={item['sha256']}\nstatic_source=false\n"
            f"music_sha256={hashlib.sha256(b'score').hexdigest()}\n",
            encoding="utf-8")
        review = prepare_negative(self.root / "case.mp4.result")
        review.update(reviewer="human", reviewed_at="2026-09-25T12:00:00+05:00",
                      material_case_confirmed=True, message_specific=True)
        (self.root / "review.json").write_text(json.dumps(review), encoding="utf-8")
        case = self.case(item["id"])
        case.update(recipe=recipe, media_ids=ids, outcome="negative", result_file="case.mp4.result",
                    expected_rejection_code="insufficient_human_evidence")
        return case, review

    def test_negative_material_code_counts_only_without_rendered_mp4(self):
        case, _ = self.negative_material_case()
        report = inspect({"schema_version": 1, "cases": [case]}, self.manifest(), self.root)
        self.assertEqual(report["errors"], [])
        self.assertEqual(report["verified_case_count"], 1)
        (self.root / "case.mp4").write_bytes(b"stale render")
        report = inspect({"schema_version": 1, "cases": [case]}, self.manifest(), self.root)
        self.assertTrue(any("rendered MP4" in error for error in report["errors"]))

    def test_negative_identity_is_checked_even_with_signed_refusal_checkboxes(self):
        case, original = self.negative_material_case()
        for changes in ({"reviewer": "   "}, {"reviewer": {"name": "human"}},
                        {"reviewed_at": "not-a-date"}, {"reviewed_at": "2026-09-25"},
                        {"reviewed_at": "2026-09-25T12:00:00"},
                        {"reviewed_at": "9999-12-31T23:59:59Z"}, {"reviewed_at": 1}):
            with self.subTest(changes=changes):
                (self.root / "review.json").write_text(json.dumps(dict(original, **changes)), encoding="utf-8")
                report = inspect({"schema_version": 1, "cases": [case]}, self.manifest(), self.root)
                self.assertEqual(report["verified_case_count"], 0)
                self.assertTrue(any("identity/date missing or invalid" in error for error in report["errors"]))

    def test_copied_negative_review_cannot_confirm_another_product_source_or_message(self):
        case, review = self.negative_material_case()
        review.update(recipe="FEAR_STROBE", source_sha256="d" * 64,
                      rejection_code="insufficient_duration", result_sha256="e" * 64)
        (self.root / "review.json").write_text(json.dumps(review), encoding="utf-8")
        report = inspect({"schema_version": 1, "cases": [case]}, self.manifest(), self.root)
        self.assertEqual(report["verified_case_count"], 0)
        self.assertTrue(any("negative review not bound" in error for error in report["errors"]))
        self.assertIn("SIGMA: negative insufficient_human_evidence not verified", report["gaps"])

    def test_negative_review_requires_each_exact_binding_field(self):
        case, original = self.negative_material_case()
        for key, changed in (("recipe", "FEAR_STROBE"), ("source_sha256", "d" * 64),
                             ("secondary_source_sha256", "c" * 64),
                             ("rejection_code", "insufficient_duration"),
                             ("result_sha256", "e" * 64)):
            for mode in ("missing", "wrong"):
                with self.subTest(key=key, mode=mode):
                    review = dict(original)
                    if mode == "missing":
                        review.pop(key)
                    else:
                        review[key] = changed
                    (self.root / "review.json").write_text(json.dumps(review), encoding="utf-8")
                    report = inspect({"schema_version": 1, "cases": [case]}, self.manifest(), self.root)
                    self.assertEqual(report["verified_case_count"], 0)
                    self.assertTrue(any("negative review not bound" in error for error in report["errors"]))

    def test_changed_result_bytes_invalidate_old_negative_review(self):
        case, _ = self.negative_material_case()
        path = self.root / "case.mp4.result"
        path.write_bytes(path.read_bytes() + b"extra_diagnostic=unchanged refusal semantics\n")
        report = inspect({"schema_version": 1, "cases": [case]}, self.manifest(), self.root)
        self.assertEqual(report["verified_case_count"], 0)
        self.assertTrue(any("negative review not bound" in error for error in report["errors"]))

    def test_duality_negative_review_is_bound_to_import_order(self):
        case, review = self.negative_material_case("DUALITY_LOOP")
        valid = inspect({"schema_version": 1, "cases": [case]}, self.manifest(), self.root)
        self.assertEqual(valid["errors"], [])
        self.assertEqual(valid["verified_case_count"], 1)
        review["source_sha256"], review["secondary_source_sha256"] = \
            review["secondary_source_sha256"], review["source_sha256"]
        (self.root / "review.json").write_text(json.dumps(review), encoding="utf-8")
        report = inspect({"schema_version": 1, "cases": [case]}, self.manifest(), self.root)
        self.assertEqual(report["verified_case_count"], 0)
        self.assertTrue(any("negative review not bound" in error for error in report["errors"]))

    def test_blank_bound_negative_form_does_not_supply_human_confirmation(self):
        case, _ = self.negative_material_case()
        (self.root / "review.json").write_text(
            json.dumps(prepare_negative(self.root / "case.mp4.result")), encoding="utf-8")
        report = inspect({"schema_version": 1, "cases": [case]}, self.manifest(), self.root)
        self.assertEqual(report["verified_case_count"], 0)
        self.assertTrue(any("identity/date missing or invalid" in error for error in report["errors"]))


if __name__ == "__main__":
    unittest.main()
