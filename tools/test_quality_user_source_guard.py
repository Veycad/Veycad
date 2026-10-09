import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import quality_user_source_guard as guard


class UserSourceGuardTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.first = Path(self.directory.name) / "first.mp4"
        self.second = Path(self.directory.name) / "second.mp4"
        self.third = Path(self.directory.name) / "IMG_2249.MOV"
        self.first.write_bytes(b"first test bytes")
        self.second.write_bytes(b"second test bytes")
        self.third.write_bytes(b"third test bytes")
        self.first_sha = "8e65b237e48e8fd4b3d50079c85c64034fb592f5881a389deaa9485dc43aafca"
        self.second_sha = "81629968d2a0b41b355ec729d900f9b1cd2aec244a543f785796e5aa55d616b2"
        self.third_sha = "0e2d39036a6eeb520196905a04d6d4fa1385a09e80124d98f3c4a3faf48e9c23"

    def approved_hashes(self):
        identities = {self.first.resolve(): self.first_sha,
                      self.second.resolve(): self.second_sha,
                      self.third.resolve(): self.third_sha}
        return patch.object(guard, "hash_file", side_effect=identities.__getitem__)

    def test_new_owner_capture_is_authorized_for_active_single_products_only(self):
        authorization = guard.USER_MONTAGE_SOURCES[self.third_sha]
        self.assertEqual(("HEARTBEAT", "FEAR_STROBE", "DUALITY_LOOP"), authorization["recipes"])
        self.assertEqual("IMG_2249.MOV", authorization["owner_file"])
        with self.approved_hashes():
            for recipe in ("HEARTBEAT", "FEAR_STROBE"):
                result = guard.preflight(recipe, [self.third], self.first_sha)
                self.assertEqual(self.third_sha, result["ordered_sources"][0]["sha256"])
                self.assertEqual("checked_against_supplied_prior_run", result["alternation"])
                self.assertEqual("not_assessed", result["quality_acceptance"])
            with self.assertRaisesRegex(ValueError, "not authorized for SIGMA"):
                guard.preflight("SIGMA", [self.third])


    def test_previous_identity_must_have_current_recipe_authorization(self):
        with patch.object(guard, "hash_file") as reader:
            with self.assertRaisesRegex(ValueError, "previous source is not authorized for SIGMA"):
                guard.preflight("SIGMA", [self.first], self.third_sha)
            reader.assert_not_called()
        with self.approved_hashes():
            for recipe in ("HEARTBEAT", "FEAR_STROBE"):
                result = guard.preflight(recipe, [self.first], self.third_sha)
                self.assertEqual(self.third_sha, result["previous_source_sha256"])

    def test_both_duality_orders_are_preserved(self):
        with self.approved_hashes():
            pairs = ((self.first, self.first_sha, self.second, self.second_sha),
                     (self.first, self.first_sha, self.third, self.third_sha),
                     (self.second, self.second_sha, self.third, self.third_sha))
            for first, first_sha, second, second_sha in pairs:
                for paths, expected in (([first, second], [first_sha, second_sha]),
                                        ([second, first], [second_sha, first_sha])):
                    with self.subTest(expected=expected):
                        result = guard.preflight("DUALITY_LOOP", paths)
                        self.assertEqual(expected, [item["sha256"] for item in result["ordered_sources"]])
                        self.assertTrue(result["source_authorization_passed"])
                        self.assertEqual("two_source_order_preserved", result["alternation"])
                        self.assertEqual("not_assessed", result["quality_acceptance"])
                        self.assertEqual("local_qa_only", result["scope"])

    def test_explicit_duality_alias(self):
        with self.approved_hashes():
            self.assertEqual("DUALITY_LOOP", guard.preflight("DUALITY", [self.first, self.second])["recipe"])

    def test_unknown_recipe_rejected_without_reading_sources(self):
        with patch.object(guard, "hash_file") as reader:
            for recipe in ("duality", "", "PUBLIC", "FEAR"):
                with self.assertRaisesRegex(ValueError, "unknown montage recipe"):
                    guard.preflight(recipe, [self.first, self.second])
            reader.assert_not_called()

    def test_exactly_two_duality_sources_required(self):
        for paths in ([], [self.first], [self.first, self.second, self.first]):
            with self.assertRaisesRegex(ValueError, "exactly 2"):
                guard.preflight("DUALITY_LOOP", paths)

    def test_same_identity_cannot_fill_both_roles(self):
        with self.approved_hashes():
            for path in (self.first, self.second, self.third):
                with self.subTest(path=path.name), self.assertRaisesRegex(ValueError, "two distinct"):
                    guard.preflight("DUALITY_LOOP", [path, path])
        with patch.object(guard, "hash_file", return_value=self.first_sha):
            with self.assertRaisesRegex(ValueError, "two distinct"):
                guard.preflight("DUALITY_LOOP", [self.first, self.second])

    def test_new_explicit_authorization_allows_each_original_for_all_single_styles(self):
        with self.approved_hashes():
            for recipe in ("SIGMA", "HEARTBEAT", "FEAR_STROBE"):
                for path, digest in ((self.first, self.first_sha), (self.second, self.second_sha)):
                    result = guard.preflight(recipe, [path])
                    self.assertEqual(recipe, result["recipe"])
                    self.assertEqual([digest], [item["sha256"] for item in result["ordered_sources"]])
                    self.assertEqual("not_checked_first_or_unspecified_run", result["alternation"])

    def test_single_source_styles_do_not_allow_two_source_montage(self):
        for recipe in ("SIGMA", "HEARTBEAT", "FEAR_STROBE"):
            for paths in ([], [self.first, self.second], [self.first, self.second, self.first]):
                with self.assertRaisesRegex(ValueError, "exactly 1"):
                    guard.preflight(recipe, paths)

    def test_single_source_alternation_is_checked_when_actual_prior_identity_supplied(self):
        with self.approved_hashes():
            for recipe in ("SIGMA", "HEARTBEAT", "FEAR_STROBE"):
                for path, previous in ((self.first, self.second_sha), (self.second, self.first_sha)):
                    result = guard.preflight(recipe, [path], previous)
                    self.assertEqual("checked_against_supplied_prior_run", result["alternation"])
                    self.assertEqual(previous, result["previous_source_sha256"])
                with self.assertRaisesRegex(ValueError, "must alternate"):
                    guard.preflight(recipe, [self.first], self.first_sha)
                with self.assertRaisesRegex(ValueError, "must alternate"):
                    guard.preflight(recipe, [self.second], self.second_sha)
            for recipe in ("HEARTBEAT", "FEAR_STROBE"):
                with self.assertRaisesRegex(ValueError, "must alternate"):
                    guard.preflight(recipe, [self.third], self.third_sha)

    def test_unknown_previous_identity_rejected(self):
        with self.assertRaisesRegex(ValueError, "previous source identity"):
            guard.preflight("SIGMA", [self.first], "0" * 64)

    def test_per_source_recipe_scope_still_fails_closed(self):
        limited = dict(guard.USER_MONTAGE_SOURCES)
        limited[self.first_sha] = dict(limited[self.first_sha], recipes=("DUALITY_LOOP",))
        with self.approved_hashes(), patch.object(guard, "USER_MONTAGE_SOURCES", limited):
            with self.assertRaisesRegex(ValueError, "not authorized for SIGMA"):
                guard.preflight("SIGMA", [self.first])

    def test_unknown_second_source_cannot_follow_approved_first_source(self):
        digests = {self.first.resolve(): self.first_sha, self.second.resolve(): "0" * 64}
        with patch.object(guard, "hash_file", side_effect=digests.__getitem__):
            with self.assertRaisesRegex(ValueError, "no user montage authorization"):
                guard.preflight("DUALITY_LOOP", [self.first, self.second])

    def test_single_source_alternation_not_used_as_duality_order_proof(self):
        with self.assertRaisesRegex(ValueError, "not a DUALITY ordering check"):
            guard.preflight("DUALITY_LOOP", [self.first, self.second], self.first_sha)

    def test_public_or_modified_source_bytes_rejected(self):
        # An allowed-looking filename is not provenance; actual unknown bytes fail.
        renamed = self.first.with_name("19348088228456.mp4")
        renamed.write_bytes(b"downloaded public video or modified original")
        with self.assertRaisesRegex(ValueError, "no user montage authorization"):
            guard.preflight("DUALITY_LOOP", [renamed, self.second])
        self.third.write_bytes(b"modified new owner capture")
        for recipe in ("HEARTBEAT", "FEAR_STROBE"):
            with self.subTest(recipe=recipe), self.assertRaisesRegex(ValueError, "no user montage authorization"):
                guard.preflight(recipe, [self.third])

    def test_real_hashing_integrates_with_preflight_and_detects_same_length_byte_tampering(self):
        original = self.first.read_bytes()
        digest = hashlib.sha256(original).hexdigest()
        authorization = dict(guard.USER_MONTAGE_SOURCES[self.first_sha])
        # Authorize only synthetic bytes in this local test; keep hash_file real.
        with patch.object(guard, "USER_MONTAGE_SOURCES", {digest: authorization}):
            result = guard.preflight("HEARTBEAT", [self.first])
            self.assertEqual(digest, result["ordered_sources"][0]["sha256"])
            self.assertEqual(original, self.first.read_bytes())
            self.first.write_bytes(b"X" + original[1:])
            with self.assertRaisesRegex(ValueError, "no user montage authorization"):
                guard.preflight("HEARTBEAT", [self.first])

    def test_each_owner_renderer_fixture_is_excluded_from_montage(self):
        for digest in guard.RENDERER_ONLY_FIXTURES:
            with patch.object(guard, "hash_file", return_value=digest):
                with self.assertRaisesRegex(ValueError, "renderer-validation fixture"):
                    guard.preflight("DUALITY_LOOP", [self.first, self.second])

    def test_missing_file_and_directory_rejected(self):
        for path, message in ((self.first.with_name("missing.mp4"), "unavailable"),
                              (Path(self.directory.name), "not a regular file")):
            with self.assertRaisesRegex(ValueError, message):
                guard.preflight("DUALITY_LOOP", [path, self.second])

    def test_file_bytes_are_hashed_and_not_edited(self):
        before = self.first.read_bytes()
        self.assertEqual(hashlib.sha256(before).hexdigest(), guard.hash_file(self.first))
        self.assertEqual(before, self.first.read_bytes())

    def test_observable_concurrent_modification_rejected(self):
        before = self.first.stat()
        changed = type("ChangedStat", (), {"st_size": before.st_size + 1,
                                          "st_mtime_ns": before.st_mtime_ns})()
        with patch.object(Path, "stat", side_effect=[before, changed]):
            with self.assertRaisesRegex(ValueError, "changed during preflight"):
                guard.hash_file(self.first)

    def test_unreadable_source_rejected(self):
        with patch.object(guard, "hash_file", side_effect=PermissionError("denied")):
            with self.assertRaisesRegex(ValueError, "cannot be read"):
                guard.preflight("DUALITY_LOOP", [self.first, self.second])


if __name__ == "__main__":
    unittest.main()
