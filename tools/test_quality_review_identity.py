from datetime import datetime, timedelta, timezone
import unittest

from quality_review_identity import valid_review_identity


class QualityReviewIdentityTest(unittest.TestCase):
    NOW = datetime(2026, 9, 27, 12, 0, 0, tzinfo=timezone.utc)

    def identity(self, **changes):
        return {"reviewer": "human reviewer", "reviewed_at": self.NOW.isoformat(), **changes}

    def test_real_aware_iso_times_accept_z_offsets_and_exact_now(self):
        for timestamp in ("2026-09-27T12:00:00Z", "2026-09-27T12:00:00+00:00",
                          "2026-09-27T17:00:00+05:00", "2026-09-27T07:00:00-05:00",
                          "2026-09-27T11:59:59.999999Z"):
            with self.subTest(timestamp=timestamp):
                self.assertTrue(valid_review_identity(self.identity(reviewed_at=timestamp), now=self.NOW))

    def test_reviewer_is_a_nonblank_string_not_just_truthy(self):
        for reviewer in (None, "", " ", "\t\r\n", True, 1, ["reviewer"], {"name": "reviewer"}):
            with self.subTest(reviewer=reviewer):
                self.assertFalse(valid_review_identity(self.identity(reviewer=reviewer), now=self.NOW))

    def test_truthy_malformed_dates_and_nonstring_values_are_rejected(self):
        for timestamp in (None, "", " ", "not-a-date", "2026-13-27T12:00:00Z",
                          "2026-09-27T25:00:00Z", True, 1, ["2026-09-27T12:00:00Z"],
                          {"time": "2026-09-27T12:00:00Z"}):
            with self.subTest(timestamp=timestamp):
                self.assertFalse(valid_review_identity(self.identity(reviewed_at=timestamp), now=self.NOW))

    def test_naive_and_date_only_values_are_not_dated_viewing_evidence(self):
        for timestamp in ("2026-09-27", "2026-09-27Z", "2026-09-27+05:00",
                          "2026-09-27T12:00:00", "2026-09-27 12:00:00"):
            with self.subTest(timestamp=timestamp):
                self.assertFalse(valid_review_identity(self.identity(reviewed_at=timestamp), now=self.NOW))

    def test_future_times_are_rejected_after_offset_normalisation(self):
        for timestamp in ((self.NOW + timedelta(microseconds=1)).isoformat(),
                          "2026-09-27T17:00:01+05:00", "9999-12-31T23:59:59Z"):
            with self.subTest(timestamp=timestamp):
                self.assertFalse(valid_review_identity(self.identity(reviewed_at=timestamp), now=self.NOW))

    def test_blank_or_wrong_shape_review_cannot_supply_identity(self):
        for review in (None, [], "signed", {}, {"reviewer": "", "reviewed_at": ""}):
            with self.subTest(review=review):
                self.assertFalse(valid_review_identity(review, now=self.NOW))

    def test_injected_clock_must_also_have_timezone(self):
        self.assertFalse(valid_review_identity(self.identity(), now=self.NOW.replace(tzinfo=None)))


if __name__ == "__main__":
    unittest.main()
