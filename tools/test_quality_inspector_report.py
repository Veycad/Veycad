"""Explicitly synthetic artifact checks; no video decoding or human acceptance."""

import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from quality_inspector_report import inspect_saved_inspector, validate_report
from quality_test_support import heartbeat_result, synthetic_inspector


class QualityInspectorReportTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.output = self.root / "fake.mp4"
        self.output.write_bytes(b"explicitly fake unit-test output, not playable")
        self.result = heartbeat_result()
        # Structural validation needs a few records with two cuts, not 1,270
        # frames. Product frame-count acceptance is covered by render-report tests.
        self.result.update(frames="8", clips="2", encoded_video_samples="8")
        self.report = synthetic_inspector(self.output, self.result)
        self.path = self.root / "fake-inspector.json"

    def save(self, report):
        self.path.write_text(json.dumps(report), encoding="utf-8")
        return hashlib.sha256(self.path.read_bytes()).hexdigest()

    def test_valid_saved_records_and_repeated_source_pts_are_consistent(self):
        digest = self.save(self.report)
        self.assertEqual([], inspect_saved_inspector(self.path, digest, self.output, self.result))

    def test_hash_is_required_valid_and_matches_exact_saved_bytes(self):
        digest = self.save(self.report)
        for wrong in (None, "", "bad", True, [digest], digest.upper(), "0" * 64):
            with self.subTest(wrong=wrong):
                self.assertTrue(inspect_saved_inspector(self.path, wrong, self.output, self.result))
        self.path.write_bytes(self.path.read_bytes() + b"\n")
        self.assertTrue(inspect_saved_inspector(self.path, digest, self.output, self.result))

    def test_missing_file_and_invalid_or_duplicate_json_are_rejected(self):
        self.assertTrue(inspect_saved_inspector(self.path, "a" * 64, self.output, self.result))
        for payload in (b"not json", b"{", b'{"format":"a","format":"b"}'):
            with self.subTest(payload=payload):
                self.path.write_bytes(payload)
                digest = hashlib.sha256(payload).hexdigest()
                self.assertTrue(inspect_saved_inspector(self.path, digest, self.output, self.result))

    def test_wrong_report_and_nested_shapes_fail_closed(self):
        for shape in (None, [], "report", 1, True):
            self.assertTrue(validate_report(shape, self.result, self.output))
        for section in ("graph", "summary", "container", "frames"):
            for shape in (None, "", True, 1):
                with self.subTest(section=section, shape=shape):
                    report = copy.deepcopy(self.report)
                    report[section] = shape
                    self.assertTrue(validate_report(report, self.result, self.output))

    def test_export_identity_and_graph_are_bound_to_output_and_raw(self):
        changes = (("format", "other"), ("local_only", 1), ("mp4_name", "other.mp4"),
                   ("mp4_bytes", self.output.stat().st_size + 1))
        for key, value in changes:
            with self.subTest(key=key):
                report = copy.deepcopy(self.report)
                report[key] = value
                self.assertTrue(validate_report(report, self.result, self.output))
        for key, value in (("generator", "other"), ("duration_ms", 1), ("clips", 1)):
            with self.subTest(key=key):
                report = copy.deepcopy(self.report)
                report["graph"][key] = value
                self.assertTrue(validate_report(report, self.result, self.output))

    def test_summary_and_container_acceptance_cannot_override_recorded_issues(self):
        for section in ("summary", "container"):
            for key, value in (("accepted", False), ("accepted", None), ("accepted", 1),
                               ("issues", ["actual problem"]), ("issues", None), ("issues", {})):
                with self.subTest(section=section, key=key, value=value):
                    report = copy.deepcopy(self.report)
                    report[section][key] = value
                    self.assertTrue(validate_report(report, self.result, self.output))

    def test_frame_counts_must_agree_with_raw_summary_and_record_array(self):
        for key in ("planned_frames", "shader_frames"):
            report = copy.deepcopy(self.report)
            report["summary"][key] -= 1
            self.assertTrue(validate_report(report, self.result, self.output))
        report = copy.deepcopy(self.report)
        report["frames"].pop()
        self.assertTrue(validate_report(report, self.result, self.output))

    def test_container_fields_and_av_clocks_must_match_raw(self):
        for key, value in (("width", 721), ("height", 1279), ("rotation", 90),
                           ("video_samples", 1), ("audio_samples", 1),
                           ("video_mime", "other"), ("audio_mime", "other"),
                           ("video_first_pts_us", 1), ("audio_first_pts_us", 1),
                           ("video_last_pts_us", 1), ("audio_last_pts_us", 1), ("av_delta_us", 1)):
            with self.subTest(key=key):
                report = copy.deepcopy(self.report)
                report["container"][key] = value
                self.assertTrue(validate_report(report, self.result, self.output))

    def test_integer_bindings_reject_bool_float_strings_null_and_negative(self):
        for section, key in (("graph", "duration_ms"), ("graph", "clips"),
                             ("summary", "planned_frames"), ("container", "width")):
            for value in (True, 1.0, "1", None, -1, {}, []):
                with self.subTest(section=section, key=key, value=value):
                    report = copy.deepcopy(self.report)
                    report[section][key] = value
                    self.assertTrue(validate_report(report, self.result, self.output))
        for value in (True, "1.0", "NaN", None, [], {}):
            result = dict(self.result, frames=value)
            self.assertTrue(validate_report(self.report, result, self.output))

    def test_frame_records_and_chronology_require_real_finite_timestamps(self):
        for key, value in (("output_us", 0), ("source_us", -1), ("clip", True),
                           ("decoded_source_us", float("nan")), ("decoded_secondary_source_us", "0"),
                           ("source_sampling_error_us", False), ("speed", float("inf")),
                           ("layer_opacity", {})):
            with self.subTest(key=key, value=value):
                report = copy.deepcopy(self.report)
                report["frames"][1][key] = value
                self.assertTrue(validate_report(report, self.result, self.output))
        report = copy.deepcopy(self.report)
        report["frames"][1] = None
        self.assertTrue(validate_report(report, self.result, self.output))

    def test_source_time_can_repeat_or_jump_at_cuts_but_not_go_back_inside_clip(self):
        report = copy.deepcopy(self.report)
        report["frames"][2]["source_us"] = 0
        report["frames"][3]["source_us"] = 0
        self.assertEqual([], validate_report(report, self.result, self.output))
        report["frames"][4]["source_us"] = 3_000
        report["frames"][5]["source_us"] = 2_000
        self.assertTrue(validate_report(report, self.result, self.output))

        cut = next(i for i in range(1, len(self.report["frames"]))
                   if self.report["frames"][i]["clip"] != self.report["frames"][i - 1]["clip"])
        report = copy.deepcopy(self.report)
        report["frames"][cut]["source_us"] = 0
        report["frames"][cut]["decoded_source_us"] = 0
        self.assertEqual([], validate_report(report, self.result, self.output))
        report = copy.deepcopy(self.report)
        report["frames"][5]["decoded_source_us"] = 1_000
        issues = validate_report(report, self.result, self.output)
        self.assertEqual(["inspector frames[5].decoded_source_us: decreases inside clip"], issues)

    def test_shader_mux_last_pts_allows_only_one_microsecond_quantisation(self):
        for difference in (-1, 0, 1):
            with self.subTest(difference=difference):
                report = copy.deepcopy(self.report)
                report["frames"][-1]["output_us"] += difference
                self.assertEqual([], validate_report(report, self.result, self.output))
        for difference in (-2, 2, 33_334):
            with self.subTest(difference=difference):
                report = copy.deepcopy(self.report)
                report["frames"][-1]["output_us"] += difference
                self.assertTrue(validate_report(report, self.result, self.output))

    def visible_echo(self, separation=66_656):
        """Faithful shape of a v25 visible draw record, but explicitly synthetic."""
        report = copy.deepcopy(self.report)
        for item in report["frames"]:
            item["source_us"] += 1_000_000
            item["decoded_source_us"] += 1_000_000
        frame = report["frames"][6]
        frame.update(layer_kind="DOUBLE_EXPOSURE", layer_opacity=.62,
                     dual_decoder=True,
                     decoded_secondary_source_us=frame["decoded_source_us"] - separation,
                     secondary_source_us=frame["source_us"] - 67_000)
        return report

    def test_visible_temporal_distinct_decoded_pts_accept_quantisation_without_new_threshold(self):
        for separation in (1, 66_656, 66_666, 83_334, -66_666):
            with self.subTest(separation=separation):
                self.assertEqual([], validate_report(self.visible_echo(separation), self.result, self.output))

    def test_visible_temporal_null_equal_missing_and_invalid_decoded_pts_fail_closed(self):
        for key in ("decoded_source_us", "decoded_secondary_source_us"):
            for value in (None, -1, True, False, "1", 1.0, float("nan"), {}, []):
                with self.subTest(key=key, value=value):
                    report = self.visible_echo()
                    report["frames"][6][key] = value
                    self.assertTrue(validate_report(report, self.result, self.output))
            report = self.visible_echo()
            del report["frames"][6][key]
            self.assertTrue(validate_report(report, self.result, self.output))
        report = self.visible_echo()
        report["frames"][6]["decoded_secondary_source_us"] = report["frames"][6]["decoded_source_us"]
        self.assertTrue(validate_report(report, self.result, self.output))

    def test_visible_temporal_requires_exact_true_dual_decoder(self):
        for value in (False, None, 0, 1, "true", [], {}):
            with self.subTest(value=value):
                report = self.visible_echo()
                report["frames"][6]["dual_decoder"] = value
                self.assertTrue(validate_report(report, self.result, self.output))
        report = self.visible_echo()
        del report["frames"][6]["dual_decoder"]
        self.assertTrue(validate_report(report, self.result, self.output))

    def test_visibility_boundary_is_point25_and_nonvisible_null_remains_legal(self):
        for kind in ("DOUBLE_EXPOSURE", "MIRROR_SLICE"):
            for opacity, invalid in ((.249, False), (.25, True)):
                with self.subTest(kind=kind, opacity=opacity):
                    report = self.visible_echo()
                    report["frames"][6].update(layer_kind=kind, layer_opacity=opacity,
                                                  decoded_secondary_source_us=None, dual_decoder=False)
                    self.assertEqual(invalid, bool(validate_report(report, self.result, self.output)))

    def test_layer_visibility_and_kind_cannot_be_omitted_or_nonfinite_to_bypass_gate(self):
        for key, values in (("layer_opacity", (None, True, False, -.1, 1.1, "0.62", float("nan"), float("inf"))),
                            ("layer_kind", (None, True, "UNKNOWN", [], {}))):
            for value in values:
                with self.subTest(key=key, value=value):
                    report = self.visible_echo()
                    report["frames"][6][key] = value
                    self.assertTrue(validate_report(report, self.result, self.output))
            report = self.visible_echo()
            del report["frames"][6][key]
            self.assertTrue(validate_report(report, self.result, self.output))

    def test_explicit_method_and_policy_are_bound_and_old_missing_is_unknown(self):
        for where, key in (("top", "temporal_decoded_evidence_method"), ("graph", "temporal_layer_policy")):
            for value in (None, "", "unknown-v1", True, [], {}):
                with self.subTest(where=where, value=value):
                    report = self.visible_echo()
                    record = report if where == "top" else report["graph"]
                    record[key] = value
                    self.assertTrue(validate_report(report, self.result, self.output))
            report = self.visible_echo()
            del (report if where == "top" else report["graph"])[key]
            self.assertTrue(validate_report(report, self.result, self.output))
        for key in ("temporal_decoded_evidence_method", "temporal_layer_policy"):
            for value in (None, "", "unknown-v1", True, [], {}):
                result = dict(self.result, **{key: value})
                self.assertTrue(validate_report(self.visible_echo(), result, self.output))

    def test_product_policy_cannot_be_relabelled_to_exempt_real_temporal_echo(self):
        for policy in ("reference-spatial-v1", "no-temporal-layer-v1"):
            report = self.visible_echo()
            report["graph"]["temporal_layer_policy"] = policy
            result = dict(self.result, temporal_layer_policy=policy)
            self.assertTrue(validate_report(report, result, self.output))

    def test_no_temporal_products_allow_same_moment_effects_but_not_visible_temporal_layers(self):
        for recipe, generator in (("FEAR_STROBE", "FEAR_STROBE_V1:exact"),
                                  ("DUALITY_LOOP", "DUALITY_LOOP_V1")):
            result = dict(self.result, recipe=recipe, graph_generator=generator,
                          temporal_layer_policy="no-temporal-layer-v1")
            report = copy.deepcopy(self.report)
            report["graph"].update(generator=generator, temporal_layer_policy="no-temporal-layer-v1")
            # Ordinary FLASH/shader GLITCH do not require a second decoded texture.
            report["frames"][6].update(layer_kind="FLASH", layer_opacity=.62,
                                          decoded_source_us=None, decoded_secondary_source_us=None)
            self.assertEqual([], validate_report(report, result, self.output))
            report["frames"][6]["layer_kind"] = "MIRROR_SLICE"
            self.assertTrue(validate_report(report, result, self.output))

    def test_reference_spatial_exemption_requires_sigma_and_exact_current_profile(self):
        generator = "veycad-reference-SUBJECT_REENTRY_PULSE_V2:test"
        result = dict(self.result, recipe="SIGMA", graph_generator=generator,
                      temporal_layer_policy="reference-spatial-v1")
        report = self.visible_echo()
        report["graph"].update(generator=generator, temporal_layer_policy="reference-spatial-v1")
        report["frames"][6].update(decoded_secondary_source_us=None, dual_decoder=False)
        self.assertEqual([], validate_report(report, result, self.output))
        for recipe, bad_generator in (("HEARTBEAT", generator), ("FEAR_STROBE", generator),
                                      ("SIGMA", "veycad-reference-SUBJECT_REENTRY_PULSE:test"),
                                      ("SIGMA", "SIGMA_V1")):
            changed = copy.deepcopy(report)
            changed["graph"]["generator"] = bad_generator
            raw = dict(result, recipe=recipe, graph_generator=bad_generator)
            self.assertTrue(validate_report(changed, raw, self.output))


if __name__ == "__main__":
    unittest.main()
