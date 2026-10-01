import copy
import hashlib
import tempfile
import unittest
from pathlib import Path

from quality_holdout_next import prepare, scan_results


class NextCohortTest(unittest.TestCase):
    def manifest(self):
        return {"schema_version": 1, "state": "candidate_not_release_ready", "media": [
            {"id": "a", "sha256": "a" * 64, "parent_source": "session1", "role": "holdout"},
            {"id": "b", "sha256": "b" * 64, "parent_source": "session1", "role": "holdout"},
            {"id": "c", "sha256": "c" * 64, "parent_source": "session2", "role": "holdout"}]}

    def test_exposed_negative_retires_all_shared_parent_files_without_mutating_original(self):
        source = self.manifest(); original = copy.deepcopy(source)
        evidence = {"result_file": "negative.result", "result_sha256": "d" * 64}
        candidate = prepare(source, {"a" * 64: [evidence]}, "2026-09-26T00:00:00+00:00")
        self.assertEqual(source, original)
        self.assertEqual([i["role"] for i in candidate["media"]], ["pilot", "pilot", "holdout"])
        self.assertEqual(candidate["cohort_retirement"][0]["result_evidence"], [evidence])
        self.assertFalse(candidate["next_cohort_preparation"]["release_ready"])

    def test_parent_hash_chain_and_existing_pilot_propagate_transitively(self):
        source = self.manifest()
        source["media"][0].update(role="pilot", pilot_note="Original review remains unchanged")
        source["media"][1]["parent_sha256"] = "c" * 64
        candidate = prepare(source, {}, "2026-09-26T00:00:00+00:00")
        self.assertTrue(all(i["role"] == "pilot" for i in candidate["media"]))
        self.assertEqual(candidate["media"][0]["pilot_note"], "Original review remains unchanged")

    def test_no_result_is_not_a_blindness_or_release_readiness_claim(self):
        candidate = prepare(self.manifest(), {}, "2026-09-26T00:00:00+00:00")
        self.assertEqual(candidate["next_cohort_preparation"]["retired_media_ids"], [])
        self.assertEqual(candidate["state"], "candidate_not_release_ready")
        self.assertFalse(candidate["next_cohort_preparation"]["release_ready"])

    def test_sealed_input_is_rejected(self):
        source = self.manifest(); source["state"] = "sealed"
        with self.assertRaises(ValueError):
            prepare(source, {}, "2026-09-26T00:00:00+00:00")

    def test_result_identity_is_byte_bound_including_secondary_source(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); (root / "runs").mkdir()
            payload = "status=material_rejected\nsource_sha256=" + "a" * 64 + "\nsecondary_source_sha256=" + "b" * 64 + "\n"
            path = root / "runs" / "negative.result"; path.write_text(payload, encoding="utf-8")
            exposed = scan_results(root / "runs", root)
            self.assertEqual(set(exposed), {"a" * 64, "b" * 64})
            self.assertEqual(exposed["b" * 64][0], {"result_file": "runs/negative.result",
                "result_sha256": hashlib.sha256(path.read_bytes()).hexdigest()})

    def test_malformed_source_identity_fails_instead_of_silently_ignoring_exposure(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); (root / "runs").mkdir()
            (root / "runs" / "bad.result").write_text("source_sha256=unknown\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                scan_results(root / "runs", root)


if __name__ == "__main__":
    unittest.main()
