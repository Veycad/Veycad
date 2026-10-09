import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from quality_source_timing import assess_pts, measure


class SourceTimingTest(unittest.TestCase):
    def test_cfr_rounding_is_not_vfr(self):
        pts = [round(i * 1001 / 30) for i in range(180)]
        result = assess_pts(pts, (1, 1000))
        self.assertEqual(set(result["delta_histogram_ticks"]), {"33", "34"})
        self.assertEqual(result["measured_frame_rate_mode"], "cfr")

    def test_variable_capture_intervals_are_detected(self):
        pts = [0, 33, 66, 116, 166, 199, 232, 298]
        self.assertEqual(assess_pts(pts, (1, 1000))["measured_frame_rate_mode"], "vfr")

    def test_small_but_cumulative_irregularity_is_not_hidden(self):
        pts = [0, 10, 20, 30, 40, 50, 61, 72, 83, 94, 105]
        report = assess_pts(pts, (1, 600))
        self.assertFalse(report["cfr_rounding_compatible"])
        self.assertEqual(report["measured_frame_rate_mode"], "unknown")

    def test_b_frame_packet_order_does_not_invent_vfr(self):
        self.assertEqual(assess_pts([0, 60, 20, 40, 100, 80], (1, 600))
                         ["measured_frame_rate_mode"], "cfr")

    def test_duplicate_or_missing_pts_fail(self):
        for pts, tb in (([0, 20, 20], (1, 600)), ([0], (1, 600)),
                        ([0, 20], (1, 600)), ([0, 20, 40], (0, 600)),
                        ([0, 20, 40], (1, 0)), ([0, 20, 40], (-1, 600))):
            with self.subTest(pts=pts, timebase=tb), self.assertRaises(ValueError):
                assess_pts(pts, tb)

    def test_packet_statistics_are_independent_of_decode_order_and_absolute_start(self):
        pts = [160, 100, 140, 120]
        before = list(pts)
        report = assess_pts(pts, (1, 60))
        self.assertEqual(before, pts)
        self.assertEqual(4, report["packet_count"])
        self.assertEqual([1, 60], report["timebase"])
        self.assertEqual(100, report["first_pts"])
        self.assertEqual(160, report["last_pts"])
        self.assertEqual({"20": 3}, report["delta_histogram_ticks"])
        self.assertEqual(3, report["mean_presentation_fps"])
        self.assertEqual("cfr", report["measured_frame_rate_mode"])
        self.assertAlmostEqual(59 / 3, report["compatible_period_ticks"][0])
        self.assertAlmostEqual(61 / 3, report["compatible_period_ticks"][1])
        shifted = assess_pts([value - 200 for value in pts], (1, 60))
        for key in ("delta_histogram_ticks", "mean_presentation_fps", "compatible_period_ticks",
                    "cfr_rounding_compatible", "measured_frame_rate_mode"):
            self.assertEqual(report[key], shifted[key], key)

    def test_measure_parses_presentation_pts_binds_real_bytes_and_detects_changes(self):
        output = "#tb 0: 1/600\n0, 0, 0, 20, 12, 0x1\n0, 20, 40, 20, 12, 0x2\n0, 40, 20, 20, 12, 0x3\n"
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "synthetic.mp4"
            original = b"synthetic source, decoder output is mocked"
            source.write_bytes(original)
            decoder = Path(directory) / "ffmpeg"
            completed = subprocess.CompletedProcess([], 0, output)
            with patch("quality_source_timing.subprocess.run", return_value=completed) as run:
                report = measure(source, decoder)
            self.assertEqual(hashlib.sha256(original).hexdigest(), report["source_sha256"])
            self.assertEqual({"20": 2}, report["delta_histogram_ticks"])
            self.assertEqual(30, report["mean_presentation_fps"])
            command = run.call_args.args[0]
            self.assertIn("-copyts", command)
            self.assertEqual("1", command[command.index("-copytb") + 1])
            self.assertEqual("copy", command[command.index("-c:v") + 1])
            self.assertEqual(original, source.read_bytes())
            for invalid in (output.replace("#tb 0: 1/600\n", ""),
                            output.replace("0, 40, 20", "1, 40, 20"), "#tb 0: 1/600\n0, 0"):
                with self.subTest(output=invalid):
                    with patch("quality_source_timing.subprocess.run", return_value=
                               subprocess.CompletedProcess([], 0, invalid)):
                        with self.assertRaises(ValueError):
                            measure(source, decoder)

            def changed_during_measure(*args, **kwargs):
                source.write_bytes(b"changed source")
                return completed

            with patch("quality_source_timing.subprocess.run", side_effect=changed_during_measure):
                with self.assertRaisesRegex(ValueError, "source changed during measurement"):
                    measure(source, decoder)
