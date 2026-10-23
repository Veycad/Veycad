"""Synthetic arithmetic/contract proofs, never physical-device acceptance."""

import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

try:  # Both repository module invocation and baseline discovery.
    from tools.preview_benchmark_report import evaluate, render
except ModuleNotFoundError:
    from preview_benchmark_report import evaluate, render


def event(token, kind="EXACT_SEEK", ms=10, **changes):
    result = {
        "id": token, "event": kind, "request_ns": 1000, "start_ns": 1000,
        "end_ns": 1000 + ms * 1_000_000, "requested_output_us": 0,
        "shown_output_us": 0, "generation": {"project": 1, "surface": 1, "seek": 1},
        "completion_generation": {"project": 1, "surface": 1, "seek": 1},
        "status": "completed", "reason": None, "presented": True,
        "canonical_ready": True, "exact": True, "temperature": None,
        "prepare_ns": None,
    }
    result.update(changes)
    return result


def fixture():
    events = [event(f"seek-{i}") for i in range(100)]
    events += [event(f"format-{i}", "FORMAT_CHANGE") for i in range(20)]
    for temperature in ("cold", "warm"):
        events += [event(f"{temperature}-{i}", "FIRST_FRAME", temperature=temperature,
                         prepare_ns=5_000_000) for i in range(20)]
    events += [event("cached", "CACHED_SCRUB", canonical_ready=False, exact=False),
               event("play", "PLAY_START")]
    passes = []
    for i in range(3):
        passes.append({"id": f"pass-{i}", "observed_duration_us": 1_000_000,
                       "recipe_duration_us": 1_000_000, "completed": True,
                       "frames": [{"index": j, "status": "shown",
                                   "shown_output_us": j * 1_000_000 // 30,
                                   "audio_us": j * 1_000_000 // 30} for j in range(30)]})
    return {"schema_version": 1, "groups": [{
        "id": "synthetic-group", "metadata": {
            "app_version": "synthetic", "commit": "a" * 40,
            "draft_project": "anonymous-project", "draft_revision": 1,
            "device": "synthetic-arm64", "android": "36", "abi": "arm64-v8a",
            "codec": "synthetic-h264-decoder"},
        "scenario": {"style": "FEAR", "aspect": "1:1", "mode": "manual",
                     "input_class": "synthetic", "export_fps": 30},
        "sources": [{"sha256": "b" * 64, "codec": "h264", "width": 1920,
                     "height": 1080, "fps": 30, "dynamic_range": "SDR",
                     "gop_us": 2_000_000, "rotation": 0,
                     "sar_num": 1, "sar_den": 1}],
        "events": events, "playback": passes,
        "scrubbing": [{"id": "scrub-1", "start_ns": 0, "end_ns": 600_000_000_000,
                       "observed_duration_us": 600_000_000, "completed": True}],
        "memory": [{"at_ns": 0,
                    "app_bytes": dict.fromkeys(
                        ("cache", "pages", "header", "track", "scratch", "thumbnail", "fbo"), 1_000_000),
                    "process_bytes": {"rss": 100_000_000, "java": 5_000_000,
                                      "native": 10_000_000, "gpu": 20_000_000,
                                      "codec": 80_000_000}}],
    }]}


class PreviewBenchmarkTest(unittest.TestCase):
    def setUp(self):
        self.document = fixture()
        self.group = self.document["groups"][0]

    def result(self):
        return evaluate(self.document)["groups"][0]

    def test_complete_synthetic_metrics_pass_without_product_approval(self):
        report = evaluate(self.document)
        self.assertEqual("PASS", report["status"])
        self.assertFalse(report["product_approved"])
        self.assertEqual("baseline", report["groups"][0]["input_profile"])

    def test_p95_nearest_rank(self):
        self.group["events"] = [event(str(i), "CACHED_SCRUB", i) for i in range(1, 21)]
        metric = self.result()["events"]["CACHED_SCRUB"]
        self.assertEqual(19.0, metric["p95_ms"])
        self.assertEqual(20, metric["completed"])

    def test_missing_exact_frames_do_not_pass(self):
        self.group["events"][0].update(status="missing", end_ns=None,
                                      shown_output_us=None, presented=False, reason="no frame")
        result = self.result()
        self.assertEqual("INCOMPLETE", result["events"]["EXACT_SEEK"]["status"])
        self.assertEqual(99, result["events"]["EXACT_SEEK"]["completed"])
        self.assertEqual(1, result["events"]["EXACT_SEEK"]["missing"])
        self.assertIn("seek-0", render(evaluate(self.document)))

    def test_cold_and_warm_are_separate(self):
        for sample in self.group["events"]:
            if sample["temperature"] == "cold":
                sample["end_ns"] = 2_100_001_000
        metrics = self.result()["events"]
        self.assertEqual(2100.0, metrics["FIRST_FRAME/cold"]["p95_ms"])
        self.assertEqual("FAIL", metrics["FIRST_FRAME/cold"]["status"])
        self.assertEqual(10.0, metrics["FIRST_FRAME/warm"]["p95_ms"])
        self.assertNotIn("FIRST_FRAME", metrics)
        self.assertEqual(5.0, metrics["FIRST_FRAME/cold"]["prepare_p95_ms"])

    def test_codec_memory_is_not_hidden_in_app_budget(self):
        memory = self.result()["memory"]
        self.assertEqual(7_000_000, memory["app_peak_bytes"])
        self.assertEqual(80_000_000, memory["process_peak_bytes"]["codec"])
        self.assertEqual("PASS", memory["status"])
        self.group["memory"][0]["app_bytes"]["cache"] = 32 * 1024 * 1024
        self.assertEqual("FAIL", self.result()["memory"]["status"])

    def test_category_peaks_are_not_summed_across_time(self):
        first = self.group["memory"][0]
        first["app_bytes"] = dict.fromkeys(first["app_bytes"], 0)
        first["app_bytes"]["cache"] = 20_000_000
        second = copy.deepcopy(first)
        second["at_ns"] = 1
        second["app_bytes"].update(cache=0, fbo=20_000_000)
        self.group["memory"].append(second)
        memory = self.result()["memory"]
        self.assertEqual(20_000_000, memory["app_peak_bytes"])
        self.assertEqual(20_000_000, memory["app_category_peak_bytes"]["fbo"])
        self.assertEqual("PASS", memory["status"])

    def test_nonexact_thumbnail_cannot_complete_exact_seek(self):
        self.group["events"][0].update(exact=False, canonical_ready=False)
        metric = self.result()["events"]["EXACT_SEEK"]
        self.assertEqual(("FAIL", 99, 1), (metric["status"], metric["completed"], metric["failed"]))

    def test_stale_successful_presentation_is_failure(self):
        self.group["events"][0]["completion_generation"]["seek"] = 2
        metric = self.result()["events"]["EXACT_SEEK"]
        self.assertEqual(("FAIL", 99, 1), (metric["status"], metric["completed"], metric["obsolete"]))

    def test_historical_success_survives_later_generation(self):
        self.group["events"][-1]["generation"]["seek"] = 7
        self.group["events"][-1]["completion_generation"]["seek"] = 7
        self.assertEqual("PASS", self.result()["status"])

    def test_safe_supersession_is_visible_and_does_not_fail(self):
        self.group["events"].append(event("intermediate", status="superseded", presented=False,
                                          end_ns=None, shown_output_us=None, exact=False,
                                          canonical_ready=False, reason="new request"))
        metric = self.result()["events"]["EXACT_SEEK"]
        self.assertEqual(("PASS", 100, 1), (metric["status"], metric["completed"], metric["superseded"]))
        self.assertIn("intermediate", render(evaluate(self.document)))

    def test_superseded_presentation_cannot_hide_stale_callback(self):
        self.group["events"][0].update(status="superseded", reason="new request")
        self.assertEqual("FAIL", self.result()["events"]["EXACT_SEEK"]["status"])

    def test_superseded_shown_pts_cannot_hide_presentation_by_false_flag(self):
        self.group["events"][0].update(status="superseded", reason="new request", presented=False)
        self.assertEqual("FAIL", self.result()["events"]["EXACT_SEEK"]["status"])

    def test_output_pts_accuracy_uses_export_frame(self):
        # Raw target 20ms is snapped by the producer to canonical output frame 0.
        self.group["events"][0]["requested_output_us"] = 20_000
        self.assertEqual("PASS", self.result()["accuracy"]["status"])
        self.assertEqual(("PASS", 100),
                         (self.result()["events"]["EXACT_SEEK"]["status"],
                          self.result()["events"]["EXACT_SEEK"]["completed"]))
        self.group["scenario"]["export_fps"] = 60
        self.assertEqual(("FAIL", 20_000),
                         (self.result()["accuracy"]["status"], self.result()["accuracy"]["max_error_us"]))
        self.assertEqual("FAIL", self.result()["status"])
        self.group["scenario"]["export_fps"] = 30
        self.group["events"][0]["requested_output_us"] = 33_334
        self.assertEqual(("FAIL", 33_334),
                         (self.result()["accuracy"]["status"], self.result()["accuracy"]["max_error_us"]))

    def test_heavy_group_cannot_mask_baseline_failure(self):
        heavy = copy.deepcopy(self.group)
        heavy["id"] = "heavy"
        heavy["sources"][0]["codec"] = "hevc"
        self.document["groups"].append(heavy)
        self.group["events"][0]["status"] = "failed"
        self.group["events"][0]["reason"] = "decode"
        result = evaluate(self.document)
        self.assertEqual(["baseline", "heavy"], [g["input_profile"] for g in result["groups"]])
        self.assertEqual(["FAIL", "PASS"], [g["status"] for g in result["groups"]])

    def test_missing_quota_and_missing_event_are_incomplete(self):
        self.group["events"] = [e for e in self.group["events"] if e["event"] != "PLAY_START"][1:]
        metrics = self.result()["events"]
        self.assertEqual("INCOMPLETE", metrics["EXACT_SEEK"]["status"])
        self.assertEqual(100, metrics["EXACT_SEEK"]["required"])
        self.assertIsNone(metrics["PLAY_START"]["p95_ms"])
        self.assertEqual("INCOMPLETE", metrics["PLAY_START"]["status"])

    def test_grid_denominator_includes_absent_frames(self):
        self.group["playback"][0]["frames"] = self.group["playback"][0]["frames"][:1]
        playback = self.result()["playback"]
        self.assertEqual((90, 61, 29), (playback["expected_frames"], playback["shown"], playback["missing"]))
        self.assertEqual("INCOMPLETE", playback["status"])

    def test_dropped_grid_slots_not_draw_calls(self):
        for frame in self.group["playback"][0]["frames"][:5]:
            frame.update(status="dropped", shown_output_us=None, audio_us=None)
        playback = self.result()["playback"]
        self.assertAlmostEqual(100 * 5 / 90, playback["drop_percent"])
        self.assertEqual("FAIL", playback["status"])

    def test_null_clock_does_not_become_zero_drift(self):
        self.group["playback"][0]["frames"][0]["audio_us"] = None
        playback = self.result()["playback"]
        self.assertEqual(1, playback["unknown_drift"])
        self.assertEqual("INCOMPLETE", playback["status"])

    def test_av_drift_threshold(self):
        self.group["playback"][0]["frames"][0]["audio_us"] = 50_001
        self.assertEqual(("FAIL", 50_001),
                         (self.result()["playback"]["status"], self.result()["playback"]["max_av_error_us"]))

    def test_exact_seek_measures_from_request_not_delayed_work_start(self):
        for sample in self.group["events"][:100]:
            sample.update(request_ns=0, start_ns=800_000_000, end_ns=810_000_000)
        metric = self.result()["events"]["EXACT_SEEK"]
        self.assertEqual(("FAIL", 810.0), (metric["status"], metric["p95_ms"]))

    def test_format_change_includes_queue_delay(self):
        for sample in self.group["events"]:
            if sample["event"] == "FORMAT_CHANGE":
                sample.update(request_ns=0, start_ns=800_000_000, end_ns=810_000_000)
        result = self.result()
        metric = result["events"]["FORMAT_CHANGE"]
        self.assertEqual(("FAIL", 810.0, 20),
                         (metric["status"], metric["p95_ms"], metric["completed"]))
        self.assertEqual("FAIL", result["status"])

    def test_play_start_includes_queue_delay(self):
        self.group["events"][-1].update(request_ns=0, start_ns=800_000_000, end_ns=810_000_000)
        result = self.result()
        metric = result["events"]["PLAY_START"]
        self.assertEqual(("FAIL", 810.0, 1),
                         (metric["status"], metric["p95_ms"], metric["completed"]))
        self.assertEqual("FAIL", result["status"])

    def test_cached_scrub_includes_queue_delay(self):
        self.group["events"][-2].update(request_ns=0, start_ns=800_000_000, end_ns=810_000_000)
        result = self.result()
        metric = result["events"]["CACHED_SCRUB"]
        self.assertEqual(("FAIL", 810.0, 1),
                         (metric["status"], metric["p95_ms"], metric["completed"]))
        self.assertEqual("FAIL", result["status"])

    def test_first_frame_starts_when_draft_and_surface_ready_after_preparation(self):
        for sample in self.group["events"]:
            if sample["event"] == "FIRST_FRAME":
                sample.update(request_ns=0, start_ns=3_000_000_000, end_ns=3_010_000_000,
                              prepare_ns=3_000_000_000)
        result = self.result()
        for key in ("FIRST_FRAME/cold", "FIRST_FRAME/warm"):
            metric = result["events"][key]
            self.assertEqual(("PASS", 10.0, 3000.0, 20),
                             (metric["status"], metric["p95_ms"], metric["prepare_p95_ms"],
                              metric["completed"]))
        self.assertEqual("PASS", result["status"])

    def test_repeated_presentation_pts_cannot_fill_distinct_grid_slots(self):
        self.group["playback"][0]["frames"][1]["shown_output_us"] = 0
        with self.assertRaisesRegex(ValueError, "duplicate.*presentation"):
            self.result()

    def test_bad_playback_pass_not_hidden_by_other_passes(self):
        for frame in self.group["playback"][0]["frames"][:3]:
            frame.update(status="dropped", shown_output_us=None, audio_us=None)
        playback = self.result()["playback"]
        self.assertAlmostEqual(100 * 3 / 90, playback["drop_percent"])
        self.assertEqual("FAIL", playback["status"])
        self.assertEqual(("FAIL", 10.0),
                         (playback["passes"][0]["status"], playback["passes"][0]["drop_percent"]))

    def test_latency_limits_each_event(self):
        for kind, temperature, duration in (("FIRST_FRAME", "cold", 2001),
                                           ("FIRST_FRAME", "warm", 2001),
                                           ("CACHED_SCRUB", None, 101),
                                           ("EXACT_SEEK", None, 751),
                                           ("FORMAT_CHANGE", None, 151),
                                           ("PLAY_START", None, 301)):
            data = fixture()
            for sample in data["groups"][0]["events"]:
                if sample["event"] == kind and sample["temperature"] == temperature:
                    sample["end_ns"] = sample["start_ns"] + duration * 1_000_000
            with self.subTest(event=kind, temperature=temperature):
                key = kind + ("/" + temperature if temperature else "")
                metric = evaluate(data)["groups"][0]["events"][key]
                self.assertEqual(("FAIL", float(duration)), (metric["status"], metric["p95_ms"]))

    def test_two_sources_share_one_app_budget(self):
        self.group["sources"].append(copy.deepcopy(self.group["sources"][0]))
        self.group["memory"][0]["app_bytes"].update(cache=20_000_000, pages=20_000_000)
        self.assertEqual(("FAIL", 45_000_000),
                         (self.result()["memory"]["status"], self.result()["memory"]["app_peak_bytes"]))

    def test_unknown_prepare_and_completion_evidence_remain_incomplete(self):
        self.group["events"][120]["prepare_ns"] = None
        self.group["events"][0]["completion_generation"] = None
        metrics = self.result()["events"]
        self.assertEqual("INCOMPLETE", metrics["FIRST_FRAME/cold"]["status"])
        self.assertEqual(("INCOMPLETE", 99),
                         (metrics["EXACT_SEEK"]["status"], metrics["EXACT_SEEK"]["completed"]))

    def test_no_groups_does_not_pass_and_input_is_not_mutated(self):
        self.assertEqual("INCOMPLETE", evaluate({"schema_version": 1, "groups": []})["status"])
        original = copy.deepcopy(self.document)
        self.result()
        self.assertEqual(original, self.document)

    def test_absent_playback_and_scrubbing_evidence_is_incomplete(self):
        self.group["playback"] = []
        self.group["scrubbing"] = []
        result = self.result()
        self.assertEqual("INCOMPLETE", result["playback"]["status"])
        self.assertEqual("INCOMPLETE", result["coverage"]["status"])
        self.assertIsNone(result["playback"]["drop_percent"])

    def test_partial_play_and_scrub_are_not_full_coverage(self):
        self.group["playback"][0]["completed"] = False
        self.group["scrubbing"][0]["observed_duration_us"] = 599_999_999
        result = self.result()["coverage"]
        self.assertEqual(("INCOMPLETE", 2, 599_999_999),
                         (result["status"], result["full_plays"], result["scrubbing_us"]))

    def test_missing_and_unknown_memory_are_not_zero(self):
        del self.group["memory"][0]["app_bytes"]["cache"]
        self.group["memory"][0]["process_bytes"]["codec"] = None
        memory = self.result()["memory"]
        self.assertEqual("INCOMPLETE", memory["status"])
        self.assertIsNone(memory["app_peak_bytes"])
        self.assertIsNone(memory["process_peak_bytes"]["codec"])

    def test_all_failure_statuses_are_retained(self):
        for i, status in enumerate(("failed", "incomplete", "obsolete")):
            self.group["events"][i].update(status=status, reason=status)
        metric = self.result()["events"]["EXACT_SEEK"]
        self.assertEqual((97, 1, 1, 1),
                         (metric["completed"], metric["failed"], metric["incomplete"], metric["obsolete"]))
        self.assertEqual("FAIL", metric["status"])

    def test_duplicate_event_and_grid_ids_rejected(self):
        for field in ("event", "grid", "pass", "group", "scrub", "memory"):
            data = fixture()
            group = data["groups"][0]
            if field == "event":
                group["events"].append(copy.deepcopy(group["events"][0]))
            elif field == "grid":
                group["playback"][0]["frames"].append(copy.deepcopy(group["playback"][0]["frames"][0]))
            elif field == "pass":
                group["playback"].append(copy.deepcopy(group["playback"][0]))
            elif field == "group":
                data["groups"].append(copy.deepcopy(group))
            elif field == "scrub":
                group["scrubbing"].append(copy.deepcopy(group["scrubbing"][0]))
            else:
                group["memory"].append(copy.deepcopy(group["memory"][0]))
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "duplicate"):
                evaluate(data)

    def test_schema_types_timestamps_and_unknown_fields_rejected(self):
        mutations = [
            lambda d: d.update(schema_version=2),
            lambda d: d.update(schema_version=True),
            lambda d: d["groups"][0]["events"][0].update(start_ns=True),
            lambda d: d["groups"][0]["events"][0].update(end_ns=-1),
            lambda d: d["groups"][0]["events"][0].update(end_ns=0),
            lambda d: d["groups"][0]["events"][0].update(end_ns=float("nan")),
            lambda d: d["groups"][0]["events"][0].update(exact=1),
            lambda d: d["groups"][0]["events"][0].update(source_pts_us=0),
            lambda d: d["groups"][0]["sources"][0].update(fps=float("inf")),
            lambda d: d["groups"][0]["sources"][0].update(sha256="private.mp4"),
            lambda d: d["groups"][0]["memory"][0]["app_bytes"].update(cache=True),
            lambda d: d["groups"][0]["playback"][0]["frames"][0].update(index=30),
            lambda d: d["groups"][0]["scrubbing"][0].update(observed_duration_us=600_000_001),
        ]
        for index, mutate in enumerate(mutations):
            data = fixture()
            mutate(data)
            with self.subTest(mutation=index), self.assertRaises(ValueError):
                evaluate(data)

    def test_markdown_units_identity_and_nonapproval(self):
        self.group["metadata"]["device"] = "synthetic|device\nheading"
        markdown = render(evaluate(self.document))
        for text in ("p95 (ms)", "bytes", "us", "synthetic", "a" * 40,
                     "not product or visual approval", "baseline", "80,000,000"):
            self.assertIn(text, markdown)
        self.assertNotIn("synthetic|device\nheading", markdown)

    def test_cli_writes_report_and_distinguishes_failure_from_bad_input(self):
        script = Path(__file__).with_name("preview_benchmark_report.py")
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "synthetic.json"
            output = Path(directory) / "report.md"
            for mutation, expected in ((None, 0), ("missing", 1), ("invalid", 2)):
                data = fixture()
                if mutation == "missing":
                    data["groups"][0]["events"] = []
                elif mutation == "invalid":
                    data["schema_version"] = 9
                source.write_text(json.dumps(data), encoding="utf-8")
                if output.exists():
                    output.unlink()
                completed = subprocess.run([sys.executable, str(script), str(source), "--output", str(output)],
                                           capture_output=True, text=True)
                self.assertEqual(expected, completed.returncode, completed.stderr)
                self.assertEqual(mutation != "invalid", output.exists())
                if mutation != "invalid":
                    self.assertIn("not product or visual approval", output.read_text(encoding="utf-8"))

    def test_cli_rejects_duplicate_keys_nonfinite_json_and_broken_json(self):
        script = Path(__file__).with_name("preview_benchmark_report.py")
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "invalid.json"
            output = Path(directory) / "report.md"
            for raw in ('{"schema_version":1,"schema_version":1,"groups":[]}',
                        '{"schema_version": NaN, "groups":[]}',
                        '{"schema_version": Infinity, "groups":[]}', '{broken'):
                source.write_text(raw, encoding="utf-8")
                completed = subprocess.run([sys.executable, str(script), str(source), "--output", str(output)],
                                           capture_output=True, text=True)
                self.assertEqual(2, completed.returncode)
                self.assertFalse(output.exists())
                self.assertIn("preview benchmark:", completed.stderr)


if __name__ == "__main__":
    unittest.main()
