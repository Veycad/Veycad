import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from quality_holdout import ALLOWED_TAGS, inspect


class QualityHoldoutTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "media").mkdir()
        self.media = []

    def add(self, name, *, duration=20, fps=30, container="mp4", mode="cfr",
            tags=(), camera=None, camera_evidence=None, vfr_evidence=None,
            role="holdout", pilot_note=None):
        payload = name.encode("ascii")
        (self.root / "media" / name).write_bytes(payload)
        self.media.append({
            "id": name, "file": name, "sha256": hashlib.sha256(payload).hexdigest(),
            "bytes": len(payload), "duration_s": duration, "fps_bucket": fps,
            "frame_rate_mode": mode, "container": container, "video_codec": "h264",
            "camera_model": camera, "camera_evidence": camera_evidence,
            "vfr_evidence": vfr_evidence, "tags": list(tags),
            "parent_source": name, "source_page": "https://example.test/" + name,
            "license": "test data",
            "role": role, "pilot_note": pilot_note,
        })
        if mode == "vfr" and vfr_evidence:
            # Synthetic metadata fixtures test binding only, not real camera VFR.
            report = self.root / "media" / (name + ".timing.json")
            report.write_text(json.dumps({"source_sha256": self.media[-1]["sha256"],
                              "classification_rule": "pairwise-cfr-rounding-one-tick-conservative-v2",
                              "measured_frame_rate_mode": "vfr"}), encoding="utf-8")
            self.media[-1].update(timing_report_file=report.relative_to(self.root).as_posix(),
                                 timing_report_sha256=hashlib.sha256(report.read_bytes()).hexdigest())

    def manifest(self, *, state="candidate_not_release_ready"):
        return {
            "schema_version": 1, "state": state, "media_root": "media",
            "required_tags": sorted(ALLOWED_TAGS),
            "required_single_source_fps": [24, 25, 30, 60],
            "required_containers": ["mp4", "webm", "mov"],
            "required_frame_rate_modes": ["cfr", "vfr"],
            "minimum_verified_camera_models": 2, "media": self.media,
        }

    def test_short_duality_input_does_not_fill_single_source_fps(self):
        self.add("short.webm", duration=10, fps=60, container="webm")
        self.add("long.mp4", duration=20, fps=24)
        report = inspect(self.manifest(), self.root)
        self.assertIn("single-source FPS missing: 60", report["coverage_gaps"])
        self.assertEqual(report["duality_eligible"], 2)
        self.assertEqual(report["single_source_eligible"], 1)

    def test_tampered_media_is_not_counted(self):
        self.add("one.mp4", tags=("portrait",))
        (self.root / "media" / "one.mp4").write_bytes(b"tampered")
        report = inspect(self.manifest(), self.root)
        self.assertTrue(any("byte length differs" in error for error in report["integrity_errors"]))
        self.assertEqual(report["media_verified"], 0)
        self.assertIn("single-source tag missing: portrait", report["coverage_gaps"])

    def test_unproven_metadata_is_an_integrity_error(self):
        self.add("vfr.mp4", mode="vfr", camera="Phone X")
        report = inspect(self.manifest(), self.root)
        self.assertTrue(any("VFR needs" in error for error in report["integrity_errors"]))
        self.assertTrue(any("camera model needs" in error for error in report["integrity_errors"]))

    def test_pilot_cannot_fill_blind_coverage(self):
        self.add("pilot.mp4", fps=24, tags=("portrait",), role="pilot",
                 pilot_note="render inspected")
        self.add("eligible.webm", fps=25, container="webm", tags=("full_body",))
        report = inspect(self.manifest(), self.root)
        self.assertEqual(report["pilot_media_verified"], 1)
        self.assertEqual(report["media_verified"], 1)
        self.assertIn("single-source tag missing: portrait", report["coverage_gaps"])
        self.assertIn("single-source FPS missing: 24", report["coverage_gaps"])

    def test_pilot_requires_reason(self):
        self.add("pilot.mp4", role="pilot")
        report = inspect(self.manifest(), self.root)
        self.assertTrue(any("pilot requires" in error for error in report["integrity_errors"]))

    def test_derivative_of_pilot_cannot_reenter_holdout(self):
        self.add("pilot.mp4", role="pilot", pilot_note="render inspected")
        self.media[-1]["parent_sha256"] = "a" * 64
        self.add("derivative.mp4")
        self.media[-1]["parent_source"] = "pilot.mp4"
        report = inspect(self.manifest(), self.root)
        self.assertTrue(any("parent source has been exposed" in error
                            for error in report["integrity_errors"]))

    def test_same_parent_sha_under_different_labels_does_not_fill_independence(self):
        for name in ("a.mp4", "b.mp4", "c.mp4"):
            self.add(name)
        self.media[0]["parent_sha256"] = "a" * 64
        self.media[1]["parent_sha256"] = "a" * 64
        report = inspect(self.manifest(), self.root)
        self.assertIn("fewer than three independent single-source parents",
                      report["coverage_gaps"])

    def test_transitive_parent_alias_cannot_hide_pilot_exposure(self):
        self.add("pilot.mp4", role="pilot", pilot_note="render inspected")
        self.media[-1]["parent_sha256"] = "a" * 64
        self.add("bridge.mp4")
        self.media[-1].update(parent_source="pilot.mp4", parent_sha256="b" * 64)
        self.add("alias.mp4")
        self.media[-1]["parent_sha256"] = "b" * 64
        report = inspect(self.manifest(), self.root)
        self.assertIn("alias.mp4: parent source has been exposed as a pilot",
                      report["integrity_errors"])

    def test_complete_metadata_can_seal_corpus(self):
        from quality_holdout_seal import make_seal
        from quality_test_support import fixture_baseline
        self.add("a.mp4", fps=24, tags=("portrait", "dark", "backlit"), camera="Phone A",
                 camera_evidence="camera EXIF")
        self.add("b.webm", fps=25, container="webm",
                 tags=("full_body", "multiple_people", "intense_motion"),
                 mode="vfr", vfr_evidence="decoded PTS deltas vary beyond timebase rounding")
        self.add("c.mov", fps=30, container="mov",
                 tags=("no_people", "low_quality", "static_camera"), camera="Phone B",
                 camera_evidence="camera EXIF")
        self.add("d.mp4", fps=60)
        fixture_baseline(self.root)
        manifest = self.manifest(state="sealed")
        manifest["seal"] = make_seal(manifest, self.root, "build/test.apk", "a" * 40)
        report = inspect(manifest, self.root)
        self.assertEqual(report["integrity_errors"], [])
        self.assertEqual(report["coverage_gaps"], [])
        self.assertTrue(report["corpus_ready"])

    def test_state_flag_does_not_replace_baseline_seal(self):
        self.add("one.mp4")
        report = inspect(self.manifest(state="sealed"), self.root)
        self.assertIn("sealed holdout requires a baseline seal", report["integrity_errors"])
        self.assertFalse(report["corpus_ready"])

    def test_vfr_text_or_changed_report_cannot_supply_evidence(self):
        self.add("timed.mp4", mode="vfr", vfr_evidence="timestamps measured")
        path = self.root / self.media[-1]["timing_report_file"]
        path.write_text("changed", encoding="utf-8")
        report = inspect(self.manifest(), self.root)
        self.assertTrue(any("VFR evidence invalid" in error for error in report["integrity_errors"]))

    def test_manifest_cannot_erase_coverage_requirements(self):
        self.add("one.mp4")
        manifest = self.manifest(state="sealed")
        manifest["required_tags"] = []
        manifest["required_single_source_fps"] = []
        manifest["required_containers"] = []
        manifest["required_frame_rate_modes"] = []
        manifest["minimum_verified_camera_models"] = 0
        report = inspect(manifest, self.root)
        self.assertTrue(any("release coverage contract" in error
                            for error in report["integrity_errors"]))
        self.assertFalse(report["corpus_ready"])

    def test_invalid_product_scope_is_a_corpus_integrity_error(self):
        from quality_product_scope import sigma_paused_scope
        self.add("one.mp4")
        manifest = self.manifest()
        manifest["product_scope"] = sigma_paused_scope()
        manifest["product_scope"]["products"]["FEAR_STROBE"] = {"state": "paused"}
        report = inspect(manifest, self.root)
        self.assertTrue(any("invalid FEAR_STROBE" in error for error in report["integrity_errors"]))
        self.assertFalse(report["corpus_ready"])


if __name__ == "__main__":
    unittest.main()
