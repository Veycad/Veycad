import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from compare_media_performance import compare, median_gain


class MediaPerformanceComparisonTest(unittest.TestCase):
    def test_median_rejects_short_or_nonpositive_measurements(self):
        self.assertAlmostEqual(50, median_gain([10, 20, 100], [5, 10, 90])["reduction_percent"])
        for values in ([1, 2], [0, 2, 3], [-1, 2, 3]):
            with self.assertRaises(ValueError):
                median_gain(values, [1, 2, 3])

    def test_quality_or_identity_mismatch_rejects_even_a_faster_run(self):
        with tempfile.TemporaryDirectory() as directory:
            folders = [Path(directory) / name for name in ("before", "after")]
            report = {"status": "ok", "device": "device", "source_sha256": "source", "exact_matte_sha256": "mask",
                      "samples": [{"iteration": i, "warmup": i == 0, "analysis_frames": 32, "frame_sha256": "frames",
                                   "frame_decode_ns": 100, "render_30_ns": 200, "render_60_ns": 400} for i in range(4)]}
            for folder in folders:
                folder.mkdir()
                (folder / "report.json").write_text(json.dumps(report), encoding="utf-8")
            with patch("compare_media_performance.decoded_signature", return_value="same"):
                result = compare(folders[:1], folders[1:], "ffmpeg")
                self.assertEqual(12, result["decoded_exports_checked"])
                self.assertEqual(3, len(result["timings"]["render_30_ns"]["baseline_samples_ms"]))
                report["source_sha256"] = "different"
                (folders[1] / "report.json").write_text(json.dumps(report), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "Device, input"):
                    compare(folders[:1], folders[1:], "ffmpeg")
            report["source_sha256"] = "source"
            (folders[1] / "report.json").write_text(json.dumps(report), encoding="utf-8")
            with patch("compare_media_performance.decoded_signature", side_effect=lambda _, path, **kwargs: str(path.parent)):
                with self.assertRaisesRegex(ValueError, "Decoded frames"):
                    compare(folders[:1], folders[1:], "ffmpeg")


if __name__ == "__main__":
    unittest.main()
