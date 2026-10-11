"""The float oracle must reject actual overshoot, plateaus, NaN and missing evidence."""
import math
from pathlib import Path
import tempfile
import unittest
import tarfile
import io
import json
from verify_source_aac import analyze, verify


class SourceAacOracleTest(unittest.TestCase):
    def test_headroom_and_real_float_overshoot(self):
        self.assertAlmostEqual(0.8, analyze([0.0, -0.8, 0.7])["unclampedFloatPeak"])
        for signal in ([0.0, 1.02], [1.0] * 16, [0.3, math.nan], [], [0.0] * 20):
            with self.subTest(signal=signal), self.assertRaises(ValueError):
                analyze(signal)

    def test_missing_device_artifacts_never_pass(self):
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            verify(Path(directory))

    def test_corrupt_device_aac_hash_fails_before_decoder(self):
        with tempfile.TemporaryDirectory() as directory:
            archive=Path(directory)/"source-audio-native-evidence.tar"
            with tarfile.open(archive,"w") as package:
                manifest={"aac":"mixed-for-listening.m4a","sha256":"0"*64,"sampleFrames":48000,"sampleRate":48000,"channels":2}
                for name,data in (("peak/float-oracle.json",json.dumps(manifest).encode()),("peak/mixed-for-listening.m4a",b"altered AAC bytes")):
                    entry=tarfile.TarInfo(name); entry.size=len(data); package.addfile(entry,io.BytesIO(data))
            with self.assertRaisesRegex(ValueError,"device-recorded hash"):
                verify(directory)


if __name__ == "__main__":
    unittest.main()
