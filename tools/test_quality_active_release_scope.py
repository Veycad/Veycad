"""Synthetic aggregation tests, never media, human, or cryptographic seal evidence.

Corpus inspection, seal date validation, non-Heartbeat assessment and decoding
are explicitly mocked. Heartbeat still exercises its existing real assessor.
Real corpus/seal and decoded-media contracts have separate tests.
"""

import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from quality_product_scope import PRODUCTS, all_active_scope, sigma_paused_scope
from quality_release_matrix import MUSIC_FILE, inspect
from quality_render_report import REVIEW_AREAS, STYLE_REVIEW_AREAS, assess
from quality_review_form import prepare_negative
from quality_test_support import independent_audio_report, review, unknown_headroom_result
from quality_test_support import synthetic_inspector


TAGS = ("portrait", "full_body", "multiple_people", "no_people", "dark",
        "backlit", "low_quality", "static_camera", "intense_motion")


class QualityActiveReleaseScopeTest(unittest.TestCase):
    """Only aggregation is under test; every artifact below is an explicit fake."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.media = []
        self.cases = []
        self.audio = {}
        self.scope = sigma_paused_scope()
        for recipe, filename in MUSIC_FILE.items():
            path = self.root / "app/src/main/res/raw" / filename
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(f"synthetic unit-test score {recipe}".encode())
        for recipe in ("HEARTBEAT", "FEAR_STROBE"):
            for index in range(3):
                self.positive(recipe, [self.source(f"{recipe}-{index}")])
        for index in range(3):
            pair = [self.source(f"pair-{index}-a"), self.source(f"pair-{index}-b")]
            self.positive("DUALITY_LOOP", pair)
            self.positive("DUALITY_LOOP", list(reversed(pair)))
        for recipe in ("HEARTBEAT", "FEAR_STROBE", "DUALITY_LOOP"):
            for code in ("insufficient_duration", "insufficient_human_evidence"):
                self.negative(recipe, code)
        for code in ("insufficient_motion", "insufficient_distinct_moments"):
            self.negative("FEAR_STROBE", code)

    def source(self, name, duration=20, tags=None):
        index = len(self.media)
        payload = f"synthetic source, not a video: {name}".encode()
        item = {
            "id": name, "role": "holdout", "sha256": hashlib.sha256(payload).hexdigest(),
            "parent_source": f"independent synthetic parent {name}",
            "parent_sha256": hashlib.sha256(b"parent:" + payload).hexdigest(),
            "duration_s": duration, "tags": list(tags if tags is not None else
                                                (TAGS[index % len(TAGS)],)),
            "fps_bucket": (24, 25, 30, 60)[index % 4],
            "container": ("mp4", "webm", "mov")[index % 3],
            "frame_rate_mode": ("cfr", "vfr")[index % 2],
            "camera_model": ("synthetic camera A", "synthetic camera B")[index % 2],
        }
        self.media.append(item)
        return item

    def base_case(self, recipe, sources):
        name = f"case-{len(self.cases)}"
        return {"id": name, "recipe": recipe, "media_ids": [item["id"] for item in sources],
                "engine_commit": "a" * 40, "apk_sha256": "b" * 64,
                "device_model": "synthetic phone", "android_api": 36,
                "result_file": f"{name}.mp4.result", "review_file": f"{name}.review.json"}

    def base_result(self, recipe, sources):
        result = unknown_headroom_result() if recipe == "HEARTBEAT" else {}
        score = self.root / "app/src/main/res/raw" / MUSIC_FILE[recipe]
        result.update(recipe=recipe, source_sha256=sources[0]["sha256"],
                      render_source_sha256=sources[0]["sha256"], static_source="false",
                      runtime_apk_sha256="b" * 64, runtime_split_apk_count="0",
                      runtime_device_model="synthetic phone", runtime_android_api="36",
                      music_sha256=hashlib.sha256(score.read_bytes()).hexdigest())
        result.update(temporal_decoded_evidence_method="decoded-texture-pts-v1",
                      temporal_layer_policy={"HEARTBEAT": "temporal-distinct-pts-v1",
                                             "SIGMA": "reference-spatial-v1",
                                             "FEAR_STROBE": "no-temporal-layer-v1",
                                             "DUALITY_LOOP": "no-temporal-layer-v1"}[recipe])
        if len(sources) == 2:
            result["secondary_source_sha256"] = sources[1]["sha256"]
        return result

    def write_result(self, case, result):
        (self.root / case["result_file"]).write_text(
            "\n".join(f"{key}={value}" for key, value in result.items()) + "\n", encoding="utf-8")

    def positive(self, recipe, sources):
        case = self.base_case(recipe, sources)
        output = self.root / f"{case['id']}.mp4"
        output.write_bytes(f"synthetic output, not playable: {case['id']}".encode())
        digest = hashlib.sha256(output.read_bytes()).hexdigest()
        result = self.base_result(recipe, sources)
        result.update(status="ok", output_sha256=digest,
                      graph_generator=result.get("graph_generator",
                          {"SIGMA": "SUBJECT_REENTRY_PULSE_V2:synthetic",
                           "FEAR_STROBE": "FEAR_STROBE_V1:synthetic",
                           "DUALITY_LOOP": "DUALITY_LOOP_V1:synthetic"}.get(recipe)))
        if recipe != "HEARTBEAT":
            result.update(duration_ms="18300", duration_error_us="0", frames="549",
                          clips="30" if recipe == "FEAR_STROBE" else "25",
                          encoded_width="720", encoded_height="720" if recipe == "FEAR_STROBE" else "1280",
                          encoded_rotation="0", encoded_video_samples="549", encoded_audio_samples="790",
                          encoded_video_mime="video/avc", encoded_audio_mime="audio/mp4a-latm",
                          video_first_pts_us="0", audio_first_pts_us="0", av_drift_us="0")
        self.write_result(case, result)
        signed = review(digest)
        signed.update(recipe=recipe, graph_generator=result["graph_generator"],
                      reviewer="synthetic unit-test human, not acceptance evidence",
                      style_checks={name: True for name in STYLE_REVIEW_AREAS[recipe]})
        (self.root / case["review_file"]).write_text(json.dumps(signed), encoding="utf-8")
        audio = independent_audio_report()
        audio.update(recipe=recipe, output_sha256=digest)
        audio_path = f"{case['id']}.audio.json"
        (self.root / audio_path).write_text(json.dumps(audio), encoding="utf-8")
        self.audio[output] = audio
        inspector = self.root / f"{case['id']}-inspector.json"
        inspector.write_text(json.dumps(synthetic_inspector(output, result)), encoding="utf-8")
        case.update(outcome="positive", output_file=output.name,
                    output_sha256=digest, audio_report_file=audio_path,
                    inspector_file=inspector.name, inspector_sha256=hashlib.sha256(inspector.read_bytes()).hexdigest())
        self.cases.append(case)
        return case

    def negative(self, recipe, code):
        count = 2 if recipe == "DUALITY_LOOP" else 1
        sources = [self.source(f"negative-{len(self.cases)}-{index}",
                               duration=1 if code == "insufficient_duration" else 20,
                               tags=["no_people"] if code == "insufficient_human_evidence" else
                                    ["static_camera"]) for index in range(count)]
        case = self.base_case(recipe, sources)
        result = self.base_result(recipe, sources)
        result.update(status="material_rejected", rejection_code=code)
        self.write_result(case, result)
        signed = prepare_negative(self.root / case["result_file"])
        signed.update(reviewer="synthetic negative reviewer, not acceptance evidence",
                      reviewed_at="2026-09-25T12:00:00+05:00",
                      material_case_confirmed=True, message_specific=True)
        (self.root / case["review_file"]).write_text(json.dumps(signed), encoding="utf-8")
        case.update(outcome="negative", expected_rejection_code=code)
        self.cases.append(case)
        return case

    def manifest(self, state="sealed"):
        return {"schema_version": 1, "state": state, "media": self.media,
                "required_tags": list(TAGS), "product_scope": copy.deepcopy(self.scope),
                "seal": {"engine_commit": "a" * 40, "apk_sha256": "b" * 64}}

    def matrix(self):
        return {"schema_version": 2, "product_scope": copy.deepcopy(self.scope),
                "cases": self.cases}

    @staticmethod
    def synthetic_assess(result, recipe, signed, audio):
        if recipe == "HEARTBEAT":
            return assess(result, recipe, signed, audio)  # Existing real HB human/audio gate.
        passed = (signed.get("recipe") == recipe and
                  signed.get("output_sha256") == result.get("output_sha256") and
                  signed.get("graph_generator") == result.get("graph_generator") and
                  bool(signed.get("reviewer")) and bool(signed.get("reviewed_at")) and
                  signed.get("playback_1x") is True and signed.get("playback_half") is True and
                  all(signed.get("checks", {}).get(name) is True for name in REVIEW_AREAS) and
                  all(signed.get("style_checks", {}).get(name) is True
                      for name in STYLE_REVIEW_AREAS[recipe]) and not signed.get("blockers"))
        return {"machine_and_human_pass": bool(passed),
                "issues": [] if passed else ["synthetic human gate failed"]}

    def inspect(self, matrix=None, manifest=None, corpus=None):
        # A mocked seal is deliberately NOT proof of actual pre-disclosure sealing.
        with patch("quality_release_matrix.inspect_corpus", return_value=corpus or {
                    "integrity_errors": [], "coverage_gaps": []}), \
                patch("quality_holdout_seal.review_after_seal", return_value=True), \
                patch("quality_audio_report.measure", side_effect=lambda output, *_: self.audio[output]), \
                patch("quality_release_matrix.assess", side_effect=self.synthetic_assess):
            return inspect(self.matrix() if matrix is None else matrix,
                           self.manifest() if manifest is None else manifest,
                           self.root, self.root / "synthetic-decoder.exe")

    def assert_all_products(self, report):
        self.assertEqual(set(PRODUCTS), set(report["products"]))

    def test_complete_active_aggregation_defers_sigma_without_full_release(self):
        report = self.inspect()
        self.assert_all_products(report)
        self.assertEqual([], report["errors"])
        self.assertEqual([], report["active_gaps"])
        self.assertEqual(20, report["verified_case_count"])
        self.assertTrue(report["active_products_ready"])
        self.assertFalse(report["ready"])
        self.assertEqual(["SIGMA"], report["paused_products"])
        sigma = report["products"]["SIGMA"]
        self.assertEqual("paused_not_release_ready", sigma["status"])
        self.assertFalse(sigma["requirements_passed"])
        self.assertEqual(0, sigma["independent_positive_count"])
        self.assertEqual(3, len(sigma["gaps"]))
        self.assertEqual(self.scope["products"]["SIGMA"], sigma["pause_basis"])
        for recipe in ("HEARTBEAT", "FEAR_STROBE", "DUALITY_LOOP"):
            self.assertEqual("release_ready", report["products"][recipe]["status"])
            self.assertEqual(3, report["products"][recipe]["independent_positive_count"])
        self.assertIn("SIGMA: paused_not_release_ready", report["gaps"])

    def test_missing_active_positive_is_not_excused_by_sigma_pause(self):
        self.cases.remove(next(case for case in self.cases
                               if case["recipe"] == "FEAR_STROBE" and case["outcome"] == "positive"))
        report = self.inspect()
        self.assertFalse(report["active_products_ready"])
        self.assertIn("FEAR_STROBE: 2/3 independent positive parents or pairs", report["active_gaps"])

    def test_fear_still_requires_motion_and_distinct_moments_negatives(self):
        for code in ("insufficient_motion", "insufficient_distinct_moments"):
            with self.subTest(code=code):
                matrix = self.matrix()
                matrix["cases"] = [case for case in self.cases
                                   if case.get("expected_rejection_code") != code]
                report = self.inspect(matrix)
                self.assertFalse(report["active_products_ready"])
                self.assertIn(f"FEAR_STROBE: negative {code} not verified", report["active_gaps"])

    def test_duality_still_requires_both_orders_for_every_pair(self):
        case = next(case for case in self.cases if case["recipe"] == "DUALITY_LOOP"
                    and case["outcome"] == "positive")
        self.cases.remove(case)
        report = self.inspect()
        self.assertEqual(3, report["products"]["DUALITY_LOOP"]["independent_positive_count"])
        self.assertFalse(report["active_products_ready"])
        self.assertTrue(any("both import orders missing" in gap for gap in report["active_gaps"]))

    def test_blank_human_review_cannot_supply_heartbeat_positive(self):
        case = next(case for case in self.cases if case["recipe"] == "HEARTBEAT")
        path = self.root / case["review_file"]
        signed = json.loads(path.read_text())
        signed.update(reviewer="", reviewed_at="", playback_1x=False, playback_half=False)
        path.write_text(json.dumps(signed), encoding="utf-8")
        report = self.inspect()
        self.assertFalse(report["active_products_ready"])
        self.assertEqual(2, report["products"]["HEARTBEAT"]["independent_positive_count"])
        self.assertTrue(any("identity/date missing or invalid" in error for error in report["errors"]))

    def test_missing_style_human_review_cannot_supply_fear_positive(self):
        case = next(case for case in self.cases if case["recipe"] == "FEAR_STROBE")
        path = self.root / case["review_file"]
        signed = json.loads(path.read_text())
        signed.pop("style_checks")
        path.write_text(json.dumps(signed), encoding="utf-8")
        report = self.inspect()
        self.assertFalse(report["active_products_ready"])
        self.assertEqual(2, report["products"]["FEAR_STROBE"]["independent_positive_count"])
        self.assertTrue(any("synthetic human gate failed" in error for error in report["errors"]))

    def test_negative_human_confirmation_is_not_deferred_for_active_product(self):
        case = next(case for case in self.cases if case["recipe"] == "FEAR_STROBE"
                    and case.get("expected_rejection_code") == "insufficient_motion")
        path = self.root / case["review_file"]
        signed = json.loads(path.read_text())
        signed["material_case_confirmed"] = False
        path.write_text(json.dumps(signed), encoding="utf-8")
        report = self.inspect()
        self.assertFalse(report["active_products_ready"])
        self.assertIn("FEAR_STROBE: negative insufficient_motion not verified", report["active_gaps"])
        self.assertTrue(any("negative material and message review missing" in error
                            for error in report["errors"]))

    def test_saved_audio_is_still_compared_to_remeasurement_with_paused_scope(self):
        case = next(case for case in self.cases if case["outcome"] == "positive")
        path = self.root / case["audio_report_file"]
        saved = json.loads(path.read_text())
        saved["sample_peak"] = .8
        path.write_text(json.dumps(saved), encoding="utf-8")
        report = self.inspect()
        self.assertFalse(report["active_products_ready"])
        self.assertTrue(any("differs from actual MP4 remeasurement" in error
                            for error in report["errors"]))

    def test_global_exercised_coverage_still_applies_to_active_products(self):
        for item in self.media:
            item.update(fps_bucket=30, container="mp4", frame_rate_mode="cfr", camera_model="one camera")
            item["tags"] = [tag for tag in item["tags"] if tag != "intense_motion"]
        report = self.inspect()
        self.assertFalse(report["active_products_ready"])
        for gap in ("unexercised material tag: intense_motion", "unexercised fps_bucket: 24",
                    "unexercised fps_bucket: 25", "unexercised fps_bucket: 60",
                    "unexercised container: webm", "unexercised container: mov",
                    "unexercised frame_rate_mode: vfr", "unexercised verified camera models: 1/2"):
            self.assertIn(gap, report["active_gaps"])

    def test_paused_sigma_case_is_error_and_cannot_supply_coverage(self):
        for item in self.media:
            item["tags"] = [tag for tag in item["tags"] if tag != "intense_motion"]
        source = self.source("paused-only-coverage", tags=["intense_motion"])
        sigma = self.positive("SIGMA", [source])
        report = self.inspect()
        self.assertFalse(report["active_products_ready"])
        self.assertEqual(20, report["verified_case_count"])
        self.assertEqual(0, report["products"]["SIGMA"]["independent_positive_count"])
        self.assertIn(f"{sigma['id']}: paused product cannot supply a new release case", report["errors"])
        self.assertIn("unexercised material tag: intense_motion", report["active_gaps"])

    def test_invalid_extra_case_poisoning_is_not_hidden_by_complete_active_cases(self):
        self.cases.append({"id": "invalid-extra", "recipe": "unknown-product"})
        report = self.inspect()
        self.assertEqual(20, report["verified_case_count"])
        self.assertEqual([], report["active_gaps"])
        self.assertFalse(report["active_products_ready"])
        self.assertIn("invalid-extra: unknown recipe", report["errors"])

    def test_shared_parent_does_not_become_three_independent_positive_shoots(self):
        hb = [item for item in self.media if item["id"].startswith("HEARTBEAT-")]
        hb[1]["parent_sha256"] = hb[0]["parent_sha256"]
        report = self.inspect()
        self.assertEqual(2, report["products"]["HEARTBEAT"]["independent_positive_count"])
        self.assertFalse(report["active_products_ready"])

    def test_shared_parent_with_optional_sha_does_not_inflate_positive_count(self):
        hb = [item for item in self.media if item["id"].startswith("HEARTBEAT-")]
        hb[1]["parent_source"] = hb[0]["parent_source"]
        hb[1].pop("parent_sha256")
        report = self.inspect()
        self.assertEqual(2, report["products"]["HEARTBEAT"]["independent_positive_count"])
        self.assertFalse(report["active_products_ready"])

    def test_parent_aliases_merge_transitively_before_positive_counting(self):
        hb = [item for item in self.media if item["id"].startswith("HEARTBEAT-")]
        hb[1]["parent_source"] = hb[0]["parent_source"]
        hb[2]["parent_sha256"] = hb[1]["parent_sha256"]
        report = self.inspect()
        self.assertEqual(1, report["products"]["HEARTBEAT"]["independent_positive_count"])
        self.assertFalse(report["active_products_ready"])

    def test_derivatives_with_optional_parent_sha_do_not_inflate_duality_pairs(self):
        by_id = {item["id"]: item for item in self.media}
        for side in ("a", "b"):
            first, derivative = by_id[f"pair-0-{side}"], by_id[f"pair-1-{side}"]
            derivative["parent_source"] = first["parent_source"]
            derivative.pop("parent_sha256")
        report = self.inspect()
        self.assertEqual(2, report["products"]["DUALITY_LOOP"]["independent_positive_count"])
        self.assertFalse(report["active_products_ready"])

    def test_two_derivatives_of_one_parent_cannot_supply_a_positive_duality_pair(self):
        by_id = {item["id"]: item for item in self.media}
        by_id["pair-0-b"]["parent_source"] = by_id["pair-0-a"]["parent_source"]
        by_id["pair-0-b"].pop("parent_sha256")
        report = self.inspect()
        self.assertEqual(2, report["products"]["DUALITY_LOOP"]["independent_positive_count"])
        self.assertTrue(any("DUALITY sources share one parent recording" in error
                            for error in report["errors"]))
        self.assertFalse(report["active_products_ready"])

    def test_candidate_manifest_never_claims_sealed_active_readiness(self):
        report = self.inspect(manifest=self.manifest("candidate_not_release_ready"))
        self.assertEqual([], report["errors"])
        self.assertEqual([], report["active_gaps"])
        self.assertFalse(report["scope_bound_before_disclosure"])
        self.assertFalse(report["active_products_ready"])
        self.assertFalse(report["ready"])

    def test_corpus_integrity_or_coverage_failure_blocks_active_gate(self):
        for corpus in ({"integrity_errors": ["synthetic seal integrity failure"], "coverage_gaps": []},
                       {"integrity_errors": [], "coverage_gaps": ["synthetic unused corpus gap"]}):
            with self.subTest(corpus=corpus):
                report = self.inspect(corpus=corpus)
                self.assertFalse(report["active_products_ready"])
                self.assertFalse(report["ready"])

    def test_legacy_schema_one_remains_all_active_not_today_sigma_pause(self):
        manifest = self.manifest()
        manifest.pop("product_scope")
        report = self.inspect({"schema_version": 1, "cases": []}, manifest)
        self.assert_all_products(report)
        self.assertEqual([], report["paused_products"])
        self.assertFalse(report["ready"])
        self.assertEqual("not_release_ready", report["products"]["SIGMA"]["status"])
        self.assertIn("SIGMA: 0/3 independent positive parents or pairs", report["active_gaps"])

    def test_legacy_complete_synthetic_aggregation_requires_and_counts_sigma(self):
        for index in range(3):
            self.positive("SIGMA", [self.source(f"SIGMA-{index}")])
        for code in ("insufficient_duration", "insufficient_human_evidence"):
            self.negative("SIGMA", code)
        manifest = self.manifest()
        manifest.pop("product_scope")
        report = self.inspect({"schema_version": 1, "cases": self.cases}, manifest)
        self.assert_all_products(report)
        self.assertEqual([], report["errors"])
        self.assertEqual([], report["gaps"])
        self.assertEqual([], report["paused_products"])
        self.assertTrue(report["active_products_ready"])
        self.assertTrue(report["ready"])
        self.assertEqual(3, report["products"]["SIGMA"]["independent_positive_count"])
        self.assertEqual("release_ready", report["products"]["SIGMA"]["status"])

    def test_invalid_matrix_schema_scope_and_case_shape_keep_four_products(self):
        matrices = [{"schema_version": schema, "cases": []} for schema in (None, True, 0, 3, "2")]
        matrices.extend((dict(self.matrix(), schema_version=1),
                         {"schema_version": 2, "cases": []},
                         dict(self.matrix(), product_scope=all_active_scope()),
                         dict(self.matrix(), cases=None)))
        for matrix in matrices:
            with self.subTest(matrix=matrix):
                report = self.inspect(matrix)
                self.assert_all_products(report)
                self.assertTrue(report["errors"])
                self.assertFalse(report["active_products_ready"])
                self.assertFalse(report["ready"])

    def test_invalid_manifest_pause_cannot_remove_active_product_requirements(self):
        for mutation in ("missing-product", "pause-fear", "unknown-sigma-basis"):
            with self.subTest(mutation=mutation):
                manifest = self.manifest()
                scope = manifest["product_scope"]
                if mutation == "missing-product":
                    scope["products"].pop("FEAR_STROBE")
                elif mutation == "pause-fear":
                    scope["products"]["FEAR_STROBE"] = {"state": "paused"}
                else:
                    scope["products"]["SIGMA"]["instruction"] = "synthetic unauthorized pause"
                matrix = dict(self.matrix(), product_scope=copy.deepcopy(scope))
                report = self.inspect(matrix, manifest)
                self.assert_all_products(report)
                self.assertEqual([], report["paused_products"])
                self.assertTrue(report["errors"])
                self.assertFalse(report["active_products_ready"])
                self.assertIn("SIGMA: 0/3 independent positive parents or pairs", report["active_gaps"])


if __name__ == "__main__":
    unittest.main()
