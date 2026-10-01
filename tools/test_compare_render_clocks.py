import copy
import unittest
from compare_render_clocks import compare


class RenderClockTest(unittest.TestCase):
    def test_missing_is_not_a_matching_texture(self):
        result = compare({"frames": [{"output_us": 0}]}, {"frames": [{"output_us": 0}]})
        self.assertEqual([0], result["missing_evidence_pts"])
        self.assertEqual([], result["same_texture_pts"])

    def test_secondary_decoder_difference_invalidates_pair(self):
        a = {"frames": [{"output_us": 0, "decoded_source_us": 10, "decoded_secondary_source_us": 20}]}
        b = {"frames": [{"output_us": 0, "decoded_source_us": 10, "decoded_secondary_source_us": 30}]}
        self.assertEqual([0], compare(a, b)["changed_texture_pts"])
        self.assertEqual([0], compare(a, a)["same_texture_pts"])

    def test_duplicate_pts_are_rejected(self):
        duplicate = {"frames": [{"output_us": 0}, {"output_us": 0}]}
        for left, right in ((duplicate, {"frames": []}), ({"frames": []}, duplicate)):
            with self.subTest(side="left" if left is duplicate else "right"):
                with self.assertRaisesRegex(ValueError, "Duplicate output PTS"):
                    compare(left, right)

    def test_partition_sorted_union_and_primary_decoder_changes_without_mutating_input(self):
        left = {"frames": [
            {"output_us": 50, "decoded_source_us": 0},
            {"output_us": 30, "decoded_source_us": 100},
            {"output_us": 10, "decoded_source_us": 0},
            {"output_us": 40, "decoded_source_us": 100},
        ]}
        right = {"frames": [
            {"output_us": 40, "decoded_source_us": None},
            {"output_us": 20, "decoded_source_us": 100},
            {"output_us": 30, "decoded_source_us": 101},
            {"output_us": 10, "decoded_source_us": 0},
        ]}
        original = copy.deepcopy((left, right))
        expected = {"same_texture_pts": [10], "changed_texture_pts": [30],
                    "missing_evidence_pts": [20, 40, 50]}
        self.assertEqual(expected, compare(left, right))
        self.assertEqual(expected, compare(right, left))
        self.assertEqual(original, (left, right))
