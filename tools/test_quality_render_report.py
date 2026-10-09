import unittest

from quality_render_report import REVIEW_AREAS, STYLE_REVIEW_AREAS, assess, parse_result
from quality_test_support import (EXPECTED_REVIEW_AREAS, EXPECTED_STYLE_REVIEW_AREAS,
                                  fear_result, heartbeat_result, independent_audio_report, review,
                                  sigma_result, unknown_headroom_result)


class QualityRenderReportTest(unittest.TestCase):
    def test_review_contract_has_all_required_products_and_checklist_areas(self):
        self.assertEqual(EXPECTED_REVIEW_AREAS, REVIEW_AREAS)
        self.assertEqual(EXPECTED_STYLE_REVIEW_AREAS, STYLE_REVIEW_AREAS)

    def test_human_attribution_rejects_truthy_but_invalid_identity_or_date(self):
        for key, value in (("reviewer", "   "), ("reviewer", ["human"]),
                           ("reviewed_at", "not-a-date"), ("reviewed_at", "2026-09-25"),
                           ("reviewed_at", "2026-09-25T12:00:00"),
                           ("reviewed_at", "9999-12-31T23:59:59Z"),
                           ("reviewed_at", True)):
            with self.subTest(key=key, value=value):
                form = review()
                form[key] = value
                report = assess(heartbeat_result(), "HEARTBEAT", form)
                self.assertFalse(report["machine_and_human_pass"])
                self.assertEqual(report["issues"], ["human review identity/date missing or invalid"])

    def test_execution_evidence_is_required_even_with_accepted_decoded_metrics(self):
        expected = {
            "render_execution_method": (None, "", "unrecognised-v1"),
            "render_execution_evidence": (None, "", "false", "TRUE"),
            "render_execution_accepted": (None, "", "false", "TRUE"),
            "render_execution_issues": (None, "temporal_layer_sources_too_close_to_read"),
        }
        for field, values in expected.items():
            for value in values:
                with self.subTest(field=field, value=value):
                    result = heartbeat_result()
                    if value is None:
                        result.pop(field)
                    else:
                        result[field] = value
                    report = assess(result, "HEARTBEAT", review())
                    self.assertFalse(report["machine_and_human_pass"])
                    self.assertTrue(any(issue.startswith(field + ":") for issue in report["issues"]))

    def test_audio_resolution_cannot_hide_missing_or_rejected_shader_execution(self):
        scenarios = (
            {"render_execution_evidence": "false", "render_execution_accepted": "false",
             "render_execution_issues": "execution-evidence-missing"},
            {"render_execution_evidence": "true", "render_execution_accepted": "false",
             "render_execution_issues": "temporal_layer_sources_too_close_to_read"},
            # Even a stale/misreported accepted flag must not erase an issue.
            {"render_execution_accepted": "true",
             "render_execution_issues": "temporal_layer_sources_too_close_to_read"},
        )
        for overrides in scenarios:
            with self.subTest(overrides=overrides):
                result = dict(unknown_headroom_result(), **overrides)
                report = assess(result, "HEARTBEAT", review(), independent_audio_report())
                self.assertFalse(report["machine_and_human_pass"])
                self.assertTrue(report["independent_audio_verified"])
                self.assertEqual(report["resolved_unknowns"], ["audio-unclamped-headroom-unavailable"])
                self.assertTrue(any(issue.startswith("render_execution_") for issue in report["issues"]))

    def test_explicit_temporal_method_policy_cannot_be_replaced_by_accepted_metrics_or_audio(self):
        for field in ("temporal_decoded_evidence_method", "temporal_layer_policy"):
            for value in (None, "", "unknown-v1", True, [], {}):
                with self.subTest(field=field, value=value):
                    result = dict(unknown_headroom_result(), **{field: value})
                    report = assess(result, "HEARTBEAT", review(), independent_audio_report())
                    self.assertFalse(report["machine_and_human_pass"])
                    self.assertTrue(report["independent_audio_verified"])
                    self.assertTrue(any(issue.startswith(field + ":") for issue in report["issues"]))

    def test_temporal_policy_is_per_product_and_cannot_claim_spatial_exemption(self):
        for policy in ("no-temporal-layer-v1", "reference-spatial-v1"):
            result = dict(heartbeat_result(), temporal_layer_policy=policy)
            report = assess(result, "HEARTBEAT", review())
            self.assertFalse(report["machine_and_human_pass"])
            self.assertTrue(any(issue.startswith("temporal_layer_policy:") for issue in report["issues"]))
        for generator in ("HEARTBEAT_V1:production", "veycad-reference-SUBJECT_REENTRY_PULSE:test"):
            result = dict(sigma_result(), graph_generator=generator)
            report = assess(result, "SIGMA")
            self.assertTrue(any(issue.startswith("temporal_layer_policy:") for issue in report["issues"]))

    def test_fear_and_duality_raw_require_no_temporal_policy_even_without_inspector_reconstruction(self):
        for recipe, generator in (("FEAR_STROBE", "FEAR_STROBE_V1:exact"),
                                  ("DUALITY_LOOP", "DUALITY_LOOP_V1")):
            result = dict(heartbeat_result(), recipe=recipe, graph_generator=generator,
                          temporal_layer_policy="no-temporal-layer-v1")
            report = assess(result, recipe)
            self.assertFalse(any(issue.startswith("temporal_") for issue in report["issues"]))
            for policy in ("temporal-distinct-pts-v1", "reference-spatial-v1"):
                report = assess(dict(result, temporal_layer_policy=policy), recipe)
                self.assertTrue(any(issue.startswith("temporal_layer_policy:") for issue in report["issues"]))

    def test_heartbeat_accidental_repeat_metric_is_required_even_with_accepted_flag(self):
        for value in ("0.020001", "0.029531915", "1.0", "-0.01", "NaN", "inf", "-inf", "invalid", None):
            with self.subTest(value=value):
                result = heartbeat_result()
                if value is None:
                    result.pop("repeated_source_ratio")
                else:
                    result["repeated_source_ratio"] = value
                report = assess(result, "HEARTBEAT", review())
                self.assertFalse(report["machine_and_human_pass"])
                self.assertTrue(any("repeated_source_ratio" in issue for issue in report["issues"]))
        for value in ("0", "0.01999", "0.02"):
            self.assertTrue(assess(dict(heartbeat_result(), repeated_source_ratio=value),
                                   "HEARTBEAT", review())["machine_and_human_pass"])

    def test_heartbeat_beat_metric_checks_native_bounds_not_accepted_flag(self):
        for value in ("0.89999", "0", "-1", "1.01", "NaN", "inf", None):
            with self.subTest(value=value):
                result = heartbeat_result()
                if value is None:
                    result.pop("beat_hit_rate")
                else:
                    result["beat_hit_rate"] = value
                report = assess(result, "HEARTBEAT", review())
                self.assertFalse(report["machine_and_human_pass"])
                self.assertTrue(any("beat_hit_rate" in issue for issue in report["issues"]))
        for value in ("0.90", "1"):
            self.assertTrue(assess(dict(heartbeat_result(), beat_hit_rate=value),
                                   "HEARTBEAT", review())["machine_and_human_pass"])

    def test_independent_audio_cannot_mask_bad_or_missing_heartbeat_numeric_evidence(self):
        for field, value in (("repeated_source_ratio", "0.029531915"),
                             ("repeated_source_ratio", None), ("beat_hit_rate", "0.01"),
                             ("beat_hit_rate", None)):
            with self.subTest(field=field, value=value):
                result = unknown_headroom_result()
                if value is None:
                    result.pop(field)
                else:
                    result[field] = value
                report = assess(result, "HEARTBEAT", review(), independent_audio_report())
                self.assertTrue(report["independent_audio_verified"])
                self.assertFalse(report["machine_and_human_pass"])
                self.assertTrue(any(field in issue for issue in report["issues"]))

    def test_source_capability_evidence_cannot_be_replaced_by_acceptance(self):
        result = heartbeat_result()
        fields = [key for key in result if key.startswith("source_analysis_")]
        for key in fields:
            missing = {name: value for name, value in result.items() if name != key}
            self.assertFalse(assess(missing, "HEARTBEAT", review())["machine_and_human_pass"], key)
        for key, value in (("source_analysis_count", "2"),
                           ("source_analysis_0_profile", "implicit-full"),
                           ("source_analysis_0_correspondence_state", "ASSESSED"),
                           ("source_analysis_0_correspondence_assessments_completed", "1"),
                           ("source_analysis_0_subject_measured_samples", "1"),
                           ("source_analysis_0_camera_measured_samples", "1"),
                           ("source_analysis_0_observations", "0"),
                           ("source_analysis_0_observations", "1.5"),
                           ("source_analysis_0_observations", "NaN"),
                           ("source_analysis_1_profile", "editorial-semantics-v1")):
            bad = dict(result, **{key: value})
            self.assertFalse(assess(bad, "HEARTBEAT", review())["machine_and_human_pass"], (key, value))

    def test_full_unknown_assessment_is_available_but_not_a_motion_positive(self):
        result = heartbeat_result()
        result.update(source_analysis_0_profile="editorial-correspondence-v1",
                      source_analysis_0_correspondence_state="ASSESSED",
                      source_analysis_0_correspondence_assessments_completed="10")
        # More capabilities are allowed; zero measurements remain zero, not invented motion.
        self.assertTrue(assess(result, "HEARTBEAT", review())["machine_and_human_pass"])
        for field, value in (("source_analysis_0_correspondence_assessments_completed", "9"),
                             ("source_analysis_0_camera_measured_samples", "11"),
                             ("source_analysis_0_subject_measured_samples", "1")):
            bad = dict(result, **{field: value})
            self.assertFalse(assess(bad, "HEARTBEAT", review())["machine_and_human_pass"], (field, value))

    def test_duality_requires_two_separately_recorded_capabilities(self):
        from quality_render_report import source_profile_issues
        result = heartbeat_result()
        result["source_analysis_count"] = "2"
        self.assertTrue(source_profile_issues(result, "DUALITY_LOOP"))
        result.update({key.replace("source_analysis_0_", "source_analysis_1_"): value
                       for key, value in list(result.items()) if key.startswith("source_analysis_0_")})
        self.assertEqual([], source_profile_issues(result, "DUALITY_LOOP"))
        result["source_analysis_1_correspondence_assessments_completed"] = "1"
        self.assertTrue(source_profile_issues(result, "DUALITY_LOOP"))

    def test_fear_cannot_use_editorial_skipped_correspondence_as_full_evidence(self):
        from quality_render_report import source_profile_issues
        result = heartbeat_result()
        self.assertTrue(source_profile_issues(result, "FEAR_STROBE"))
        result.update(source_analysis_0_profile="editorial-correspondence-v1",
                      source_analysis_0_correspondence_state="ASSESSED",
                      source_analysis_0_correspondence_assessments_completed="10")
        self.assertEqual([], source_profile_issues(result, "FEAR_STROBE"))

    def test_old_or_unknown_face_evidence_cannot_be_replaced_by_acceptance_or_human_review(self):
        result = heartbeat_result()
        for field in ("face_evidence_method", "face_expected_samples", "face_measured_samples",
                      "face_unknown_samples", "face_unknown_times_us", "face_empty_samples",
                      "face_loss_samples", "face_loss_times_us", "face_loss_rate"):
            missing = {key: value for key, value in result.items() if key != field}
            self.assertFalse(assess(missing, "HEARTBEAT", review())["machine_and_human_pass"], field)
        for field, value in (("face_evidence_method", "previous-frame-confidence"),
                             ("face_unknown_samples", "1"),
                             ("face_unknown_times_us", "1033333"),
                             ("face_measured_samples", "9"),
                             ("face_measured_samples", "10.5"),
                             ("face_expected_samples", "0"),
                             ("face_empty_samples", "-1"),
                             ("face_empty_samples", "NaN"),
                             ("face_empty_samples", "1"),
                             ("face_loss_samples", "0.5"),
                             ("face_loss_samples", "11"),
                             ("face_loss_rate", ".01"),
                             ("face_loss_times_us", "1000000")):
            bad = dict(result, **{field: value})
            self.assertFalse(assess(bad, "HEARTBEAT", review())["machine_and_human_pass"], (field, value))

    def test_decoded_face_loss_count_and_timestamps_retain_style_threshold(self):
        result = heartbeat_result()
        result.update(face_expected_samples="100", face_measured_samples="100",
                      face_empty_samples="1", face_loss_samples="1",
                      face_loss_rate=".01", face_loss_times_us="1033333")
        self.assertTrue(assess(result, "HEARTBEAT", review())["machine_and_human_pass"])
        for times in ("1033333,1033333", "1033333,1000000", "-1", "1.5", "NaN", "21166000"):
            bad = dict(result, face_loss_times_us=times)
            self.assertFalse(assess(bad, "HEARTBEAT", review())["machine_and_human_pass"], times)
        result.update(face_loss_samples="6", face_loss_rate=".06", face_empty_samples="0",
                      face_loss_times_us="1000000,1033333,1066667,1100000,1133333,1166667")
        self.assertFalse(assess(result, "HEARTBEAT", review())["machine_and_human_pass"])

    def test_heartbeat_pulse_success_cannot_replace_missing_tail_evidence(self):
        result = heartbeat_result()
        for field in (key for key in result if key.startswith("heartbeat_tail_")):
            missing = {key: value for key, value in result.items() if key != field}
            report = assess(missing, "HEARTBEAT", review())
            self.assertFalse(report["machine_and_human_pass"], field)
            self.assertTrue(any(field in issue for issue in report["issues"]), field)

    def test_heartbeat_tail_failure_cannot_be_overridden_by_acceptance_or_review(self):
        for field, value in (("heartbeat_tail_matched", "false"),
                             ("heartbeat_tail_measured_frames", "81"),
                             ("heartbeat_tail_boundary_measured_frames", "1"),
                             ("heartbeat_tail_expected_frames", "83"),
                             ("heartbeat_tail_black_frames", "80"),
                             ("heartbeat_tail_black_frames", "81.5"),
                             ("heartbeat_tail_missing_us", "21150000"),
                             ("heartbeat_tail_bright_us", "20200000"),
                             ("heartbeat_tail_unexpected_us", "20000002"),
                             ("heartbeat_tail_pre_boundary_luma", ".1"),
                             ("heartbeat_tail_method", "planned-black-overlay"),
                             ("heartbeat_tail_start_offset_us", "16669"),
                             ("heartbeat_tail_start_offset_us", "-16669"),
                             ("heartbeat_tail_start_offset_us", "0.5"),
                             ("heartbeat_tail_first_black_us", "19833333"),
                             ("heartbeat_tail_first_black_us", "NaN")):
            with self.subTest(field=field, value=value):
                report = assess(dict(heartbeat_result(), **{field: value}), "HEARTBEAT", review())
                self.assertFalse(report["machine_and_human_pass"])
                self.assertTrue(any("heartbeat_tail" in issue for issue in report["issues"]))

    def test_extra_or_duplicate_tail_evidence_fails_despite_complete_nominal_coverage(self):
        for unexpected in ("19999998", "20000002", "20000000,20000001", "21166667"):
            result = dict(heartbeat_result(), heartbeat_tail_unexpected_us=unexpected)
            report = assess(result, "HEARTBEAT", review())
            self.assertFalse(report["machine_and_human_pass"], unexpected)
            self.assertTrue(any("heartbeat_tail_unexpected_us" in issue for issue in report["issues"]))

    def test_heartbeat_tail_permits_one_frame_start_error_and_codec_flooring(self):
        for offset, black in ((-16667, "82"), (16667, "81"), (16666, "81"), (-1, "82")):
            result = dict(heartbeat_result(), heartbeat_tail_start_offset_us=str(offset),
                          heartbeat_tail_first_black_us=str(19_800_000 + offset),
                          heartbeat_tail_black_frames=black)
            self.assertTrue(assess(result, "HEARTBEAT", review())["machine_and_human_pass"])

    def test_sigma_unknown_mask_cannot_pass_even_with_true_acceptance(self):
        for field in ("foreground_mask_source_empty_samples", "foreground_mask_output_empty_samples",
                      "foreground_mask_inference_failed_samples"):
            result = sigma_result()
            result[field] = "1"
            report = assess(result, "SIGMA")
            self.assertFalse(report["machine_and_human_pass"])
            self.assertTrue(any(field in issue for issue in report["issues"]))

    def test_sigma_old_report_without_mask_status_is_not_new_evidence(self):
        result = sigma_result()
        del result["foreground_mask_measured_samples"]
        report = assess(result, "SIGMA")
        self.assertFalse(report["machine_and_human_pass"])
        self.assertTrue(any("foreground_mask_measured_samples" in issue for issue in report["issues"]))

    def test_fear_shutter_success_cannot_replace_opening_motion_evidence(self):
        result = fear_result()
        signed = review()
        signed.update(recipe="FEAR_STROBE", graph_generator=result["graph_generator"],
                      style_checks={name: True for name in STYLE_REVIEW_AREAS["FEAR_STROBE"]})
        self.assertTrue(assess(result, "FEAR_STROBE", signed)["machine_and_human_pass"])
        self.assertTrue(assess(dict(result, beat_hit_rate="0.85", repeated_source_ratio="0.02"),
                               "FEAR_STROBE", signed)["machine_and_human_pass"])
        camera_only = dict(result, fear_opener_motion_samples="3", fear_opener_motion_run="3",
                           fear_opener_motion_measured_samples="3", fear_opener_motion_unknown_samples="0",
                           fear_opener_subject_measured_samples="0", fear_opener_camera_measured_samples="3",
                           fear_opener_motion_conclusive_samples="3", fear_opener_motion_inconclusive_samples="0")
        self.assertTrue(assess(camera_only, "FEAR_STROBE", signed)["machine_and_human_pass"])
        impossible_body = dict(camera_only, fear_opener_subject_measured_samples="3",
                               fear_opener_camera_measured_samples="0")
        broken_report = assess(impossible_body, "FEAR_STROBE", signed)
        self.assertFalse(broken_report["machine_and_human_pass"])
        self.assertTrue(any("fear_opener_camera_measured_samples" in issue for issue in broken_report["issues"]))
        for field, value in (("beat_hit_rate", "0.84999"),
                             ("beat_hit_rate", "1.01"),
                             ("beat_hit_rate", "NaN"),
                             ("repeated_source_ratio", "0.020001"),
                             ("repeated_source_ratio", "-0.01"),
                             ("repeated_source_ratio", "NaN"),
                             ("fear_opener_motion_samples", "2"),
                             ("fear_opener_motion_run", "1"),
                             ("fear_opener_motion_span_us", "499999"),
                             ("fear_opener_evidence_method", "whole-body-only"),
                             ("fear_opener_motion_measured_samples", "1"),
                             ("fear_opener_motion_unknown_samples", "-1"),
                             ("fear_opener_subject_measured_samples", "10"),
                             ("fear_opener_camera_measured_samples", "1.5"),
                             ("fear_opener_motion_conclusive_samples", "0"),
                             ("fear_opener_motion_inconclusive_samples", "0"),
                             ("fear_first_cascade_sampled_clips", "2"),
                             ("fear_first_cascade_witnesses", "fear-cascade-2,fear-cascade-2,fear-cascade-4"),
                             ("fear_second_cascade_witnesses", "fear-cascade-2,fear-cascade-3,fear-cascade-4")):
            for broken in (dict(result, **{field: value}), {key: item for key, item in result.items() if key != field}):
                report = assess(broken, "FEAR_STROBE", signed)
                self.assertFalse(report["machine_and_human_pass"])
                self.assertTrue(any(field in issue for issue in report["issues"]))

    def test_sigma_metrics_are_checked_without_trusting_acceptance_flag(self):
        result = sigma_result()
        signed = review()
        signed.update(recipe="SIGMA", graph_generator=result["graph_generator"],
                      style_checks={name: True for name in STYLE_REVIEW_AREAS["SIGMA"]})
        self.assertTrue(assess(result, "SIGMA", signed)["machine_and_human_pass"])
        for field, bad in (("beat_hit_rate", "0.94"), ("repeated_source_ratio", "0.03"),
                           ("reference_grammar_fit", "0.54"), ("layered_frames", "0"),
                           ("decoded_glitch_peak", "0.006")):
            for value in (bad, "NaN", None):
                with self.subTest(field=field, value=value):
                    broken = dict(result)
                    if value is None:
                        del broken[field]
                    else:
                        broken[field] = value
                    report = assess(broken, "SIGMA", signed)
                    self.assertFalse(report["machine_and_human_pass"])
                    self.assertTrue(any(field in issue for issue in report["issues"]))

    def test_human_review_cannot_be_reused_for_another_export_or_grammar(self):
        for field, value in (("output_sha256", "b" * 64), ("recipe", "SIGMA"),
                             ("graph_generator", "HEARTBEAT_V1:old")):
            with self.subTest(field=field):
                signed = review()
                signed[field] = value
                report = assess(heartbeat_result(), "HEARTBEAT", signed)
                self.assertFalse(report["machine_and_human_pass"])
                self.assertTrue(any("not bound" in issue for issue in report["issues"]))

    def test_generic_human_checks_do_not_replace_product_specific_review(self):
        for area in STYLE_REVIEW_AREAS["HEARTBEAT"]:
            with self.subTest(area=area):
                signed = review()
                del signed["style_checks"][area]
                report = assess(heartbeat_result(), "HEARTBEAT", signed)
                self.assertIn(f"human style review failed or missing: {area}", report["issues"])

    def test_each_product_requires_its_own_human_checks(self):
        for recipe, areas in STYLE_REVIEW_AREAS.items():
            with self.subTest(recipe=recipe):
                signed = review()
                signed["style_checks"] = {}
                report = assess(heartbeat_result(), recipe, signed)
                for area in areas:
                    self.assertIn(f"human style review failed or missing: {area}", report["issues"])

    def test_verified_audio_resolves_only_exact_android_headroom_unknown(self):
        result = unknown_headroom_result()
        report = assess(result, "HEARTBEAT", review(), independent_audio_report())
        self.assertTrue(report["machine_and_human_pass"], report["issues"])
        self.assertEqual(report["android_acceptance"], "false")
        self.assertEqual(report["resolved_unknowns"], ["audio-unclamped-headroom-unavailable"])
        self.assertEqual(result["acceptance"], "false")  # Preserve original device evidence.

    def test_fear_motion_unknown_count_does_not_replace_resolved_audio_unknown_token(self):
        result = dict(fear_result(), **{key: unknown_headroom_result()[key] for key in
                      ("acceptance", "acceptance_issues", "audio_unclamped_float", "audio_decoded_issues")})
        result.update(fear_opener_motion_unknown_samples="5", fear_opener_motion_inconclusive_samples="8")
        signed = review()
        signed.update(recipe="FEAR_STROBE", graph_generator=result["graph_generator"],
                      style_checks={name: True for name in STYLE_REVIEW_AREAS["FEAR_STROBE"]})
        audio = independent_audio_report()
        audio.update(recipe="FEAR_STROBE", pcm_samples=1756800,
                     pcm_frames=878400, decoded_duration_us=18300000)
        report = assess(result, "FEAR_STROBE", signed, audio)
        self.assertTrue(report["machine_and_human_pass"], report["issues"])
        self.assertTrue(report["independent_audio_verified"])
        self.assertEqual(report["resolved_unknowns"], ["audio-unclamped-headroom-unavailable"])
        self.assertEqual(result["fear_opener_motion_unknown_samples"], "5")
        self.assertEqual(result["acceptance"], "false")
        for native_defect in ("face-loss", "repeated-source-moments", "audio-over-full-scale"):
            with self.subTest(native_defect=native_defect):
                rejected = assess(dict(result, acceptance_issues=
                                      "audio-unclamped-headroom-unavailable," + native_defect),
                                  "FEAR_STROBE", signed, audio)
                self.assertFalse(rejected["machine_and_human_pass"])
                self.assertEqual(rejected["resolved_unknowns"], [])
        missing_motion = dict(result, fear_opener_motion_run="2")
        self.assertFalse(assess(missing_motion, "FEAR_STROBE", signed, audio)["machine_and_human_pass"])

    def test_independent_audio_never_substitutes_for_human_review(self):
        report = assess(unknown_headroom_result(), "HEARTBEAT", independent_audio=independent_audio_report())
        self.assertEqual(report["issues"], ["human review missing"])

    def test_audio_resolution_never_erases_confirmed_video_or_audio_defects(self):
        for issue in ("face-loss", "container:non_monotonic_sample_pts", "audio-over-full-scale",
                      "audio-full-scale-plateau", "audio-decode-evidence-missing"):
            result = unknown_headroom_result()
            result["acceptance_issues"] += "," + issue
            report = assess(result, "HEARTBEAT", review(), independent_audio_report())
            self.assertFalse(report["machine_and_human_pass"], issue)
            self.assertEqual(report["resolved_unknowns"], [])

    def test_wrong_stale_invalid_or_clipped_independent_report_fails(self):
        for field, value in (("output_sha256", "b" * 64), ("recipe", "FEAR_STROBE"),
                             ("sample_peak", 1.01), ("sample_peak", float("nan")),
                             ("sample_peak", -.1), ("pcm_samples", 1),
                             ("pcm_samples", True), ("sample_rate", 10 ** 1000),
                             ("passed", False), ("issues", ["audio-over-full-scale"]),
                             ("rms", .99), ("decoder_version", "")):
            with self.subTest(field=field):
                audio = independent_audio_report()
                audio[field] = value
                report = assess(unknown_headroom_result(), "HEARTBEAT", review(), audio)
                self.assertFalse(report["machine_and_human_pass"])
                self.assertFalse(report["independent_audio_verified"])

    def test_missing_or_clipped_audio_cannot_pass(self):
        for field, value in (("audio_decoded_evidence", "false"),
                             ("audio_unclamped_float", "false"),
                             ("audio_sample_peak", "1.01"), ("audio_sample_peak", "0"),
                             ("audio_sample_peak", "0e0"),
                             ("audio_channels", "1.5"), ("audio_pcm_samples", "3"),
                             ("audio_rms", "0.95"),
                             ("audio_non_finite_samples", "1"),
                             ("audio_longest_full_scale_run", "3"),
                             ("audio_decoded_duration_us", "1000000")):
            with self.subTest(field=field, value=value):
                result = heartbeat_result()
                result[field] = value
                self.assertFalse(assess(result, "HEARTBEAT", review())["machine_and_human_pass"])

    def test_container_evidence_is_required_and_fail_closed(self):
        for field in ("container_issues", "encoded_rotation", "encoded_video_mime",
                      "encoded_audio_samples", "encoded_width", "expected_height"):
            with self.subTest(field=field):
                result = heartbeat_result()
                del result[field]
                self.assertFalse(assess(result, "HEARTBEAT", review())["machine_and_human_pass"])

    def test_broken_container_cannot_pass_good_visual_metrics(self):
        for field, value in (("container_issues", "non_monotonic_sample_pts"),
                             ("encoded_rotation", "90"), ("encoded_width", "1280"),
                             ("encoded_audio_samples", "0"), ("encoded_video_samples", "1269")):
            with self.subTest(field=field):
                result = heartbeat_result()
                result[field] = value
                self.assertFalse(assess(result, "HEARTBEAT", review())["machine_and_human_pass"])

    def test_wrong_product_cannot_pass_on_generic_style(self):
        report = assess(heartbeat_result(), "SIGMA", review())
        self.assertFalse(report["machine_and_human_pass"])
        self.assertTrue(any("recipe:" in issue for issue in report["issues"]))

    def test_heartbeat_cannot_pass_with_generic_source_pool(self):
        result = heartbeat_result()
        result["source_pool_generator"] = "veycad-event-director-dynamic"
        report = assess(result, "HEARTBEAT", review())
        self.assertTrue(any("source_pool_generator" in issue for issue in report["issues"]))

    def test_missing_human_review_fails(self):
        report = assess(heartbeat_result(), "HEARTBEAT")
        self.assertIn("human review missing", report["issues"])

    def test_nonfinite_metric_fails(self):
        result = heartbeat_result()
        result["face_loss_rate"] = "NaN"
        report = assess(result, "HEARTBEAT", review())
        self.assertTrue(any("face_loss_rate: missing or non-finite" == issue
                            for issue in report["issues"]))

    def test_duplicate_fields_rejected(self):
        with self.assertRaises(ValueError):
            parse_result("status=ok\nstatus=failed\n")


if __name__ == "__main__":
    unittest.main()
