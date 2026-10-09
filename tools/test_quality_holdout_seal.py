import copy
import tempfile
import unittest
from pathlib import Path

from quality_holdout_seal import REQUIRED_FILES, make_seal, review_after_seal, verify_seal
from quality_product_scope import sigma_paused_scope
from quality_test_support import fixture_baseline


class HoldoutSealTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        fixture_baseline(self.root)
        self.manifest = {"schema_version": 1, "state": "sealed", "media": [{"id": "new"}]}
        self.manifest["seal"] = make_seal(self.manifest, self.root, "build/test.apk", "a" * 40)

    def test_unchanged_baseline_verifies(self):
        self.assertEqual(verify_seal(self.manifest, self.root), [])

    def test_explicit_sigma_pause_is_bound_by_original_manifest_hash(self):
        manifest = dict(self.manifest, product_scope=sigma_paused_scope())
        manifest["seal"] = make_seal(manifest, self.root, "build/test.apk", "a" * 40)
        self.assertEqual(verify_seal(manifest, self.root), [])
        for mode in ("removed", "active", "different-instruction"):
            with self.subTest(mode=mode):
                changed = copy.deepcopy(manifest)
                if mode == "removed":
                    changed.pop("product_scope")
                elif mode == "active":
                    changed["product_scope"]["products"]["SIGMA"] = {"state": "active"}
                else:
                    changed["product_scope"]["products"]["SIGMA"]["instruction"] = "different"
                self.assertTrue(any("metadata changed" in error
                                    for error in verify_seal(changed, self.root)))

    def test_invalid_active_product_pause_cannot_receive_new_seal(self):
        scope = sigma_paused_scope()
        scope["products"]["FEAR_STROBE"] = {"state": "paused"}
        with self.assertRaisesRegex(ValueError, "invalid FEAR_STROBE"):
            make_seal(dict(self.manifest, product_scope=scope), self.root, "build/test.apk", "a" * 40)

    def test_invalid_scope_still_rejected_even_if_attacker_rewrites_manifest_hash(self):
        from quality_holdout_seal import manifest_hash
        manifest = copy.deepcopy(self.manifest)
        manifest["product_scope"] = sigma_paused_scope()
        manifest["product_scope"]["products"]["HEARTBEAT"] = {"state": "paused"}
        manifest["seal"]["manifest_sha256"] = manifest_hash(manifest)
        self.assertTrue(any("invalid HEARTBEAT" in error for error in verify_seal(manifest, self.root)))

    def test_new_seal_rejects_known_prior_source_and_all_its_parent_derivatives(self):
        results = self.root / "artifacts/quality/runs"; results.mkdir(parents=True)
        (results / "old.result").write_text("status=material_rejected\nsource_sha256=" + "b" * 64 + "\n", encoding="utf-8")
        manifest = {"schema_version": 1, "state": "sealed", "media": [
            {"id": "known", "role": "holdout", "sha256": "b" * 64, "parent_source": "same-event"},
            {"id": "derived", "role": "holdout", "sha256": "c" * 64, "parent_source": "same-event"}]}
        with self.assertRaisesRegex(ValueError, "known exposed parents.*known, derived"):
            make_seal(manifest, self.root, "build/test.apk", "a" * 40)

    def test_first_results_after_seal_do_not_retroactively_invalidate_historical_series(self):
        results = self.root / "artifacts/quality/runs"; results.mkdir(parents=True)
        manifest = {"schema_version": 1, "state": "sealed", "media": [
            {"id": "fresh", "role": "holdout", "sha256": "b" * 64, "parent_source": "fresh-event"}]}
        manifest["seal"] = make_seal(manifest, self.root, "build/test.apk", "a" * 40)
        (results / "first.result").write_text("status=ok\nsource_sha256=" + "b" * 64 + "\n", encoding="utf-8")
        self.assertEqual(verify_seal(manifest, self.root), [])
        with self.assertRaisesRegex(ValueError, "known exposed parents"):
            make_seal(manifest, self.root, "build/test.apk", "a" * 40)

    def test_flag_alone_cannot_seal(self):
        self.assertIn("requires a baseline seal", verify_seal({"state": "sealed"}, self.root)[0])

    def test_source_metadata_or_role_change_invalidates_seal(self):
        changed = copy.deepcopy(self.manifest)
        changed["media"][0]["role"] = "pilot"
        self.assertTrue(any("metadata changed" in error for error in verify_seal(changed, self.root)))

    def test_engine_and_criteria_edits_invalidate_seal(self):
        for name in (REQUIRED_FILES[0], "docs/quality/README.md", "tools/quality_holdout.py"):
            with self.subTest(name=name):
                path = self.root / name
                original = path.read_bytes()
                path.write_bytes(b"changed after disclosure")
                self.assertTrue(any("criteria changed" in error
                                    for error in verify_seal(self.manifest, self.root)))
                path.write_bytes(original)

    def test_new_engine_file_invalidates_seal(self):
        (self.root / "app/src/main/java/NewDirector.kt").write_bytes(b"new rule")
        self.assertTrue(any("criteria changed" in error
                            for error in verify_seal(self.manifest, self.root)))

    def test_apk_replacement_invalidates_seal(self):
        (self.root / "build/test.apk").write_bytes(b"another build")
        self.assertTrue(any("APK missing or changed" in error
                            for error in verify_seal(self.manifest, self.root)))

    def test_apk_path_cannot_escape_repository(self):
        self.manifest["seal"]["apk_file"] = "../outside.apk"
        self.assertTrue(any("APK missing or changed" in error
                            for error in verify_seal(self.manifest, self.root)))

    def test_missing_required_criteria_invalidates_seal(self):
        (self.root / "docs/quality/human-review-template.json").unlink()
        self.assertTrue(any("baseline file missing" in error
                            for error in verify_seal(self.manifest, self.root)))

    def test_review_must_postdate_seal_with_valid_timezone(self):
        seal = {"sealed_at": "2020-01-01T00:00:00+00:00"}
        self.assertTrue(review_after_seal({"reviewed_at": "2020-01-02T05:00:00+05:00"}, seal))
        for date in ("2019-12-31T23:59:59+00:00", "2020-01-02T00:00:00",
                     "2999-01-01T00:00:00+00:00", "not a date", None):
            with self.subTest(date=date):
                self.assertFalse(review_after_seal({"reviewed_at": date}, seal))
