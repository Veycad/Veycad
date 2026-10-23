"""The float oracle must reject actual overshoot, plateaus, NaN and missing evidence."""
import math
from pathlib import Path
import tempfile
import unittest
import tarfile
import io
import json
import hashlib
import struct
from unittest.mock import patch
from verify_source_aac import analyze, authenticate_artifacts, validate_evidence_archive, verify


class SourceAacOracleTest(unittest.TestCase):
    def evidence(self):
        # Independent container bytes: metadata can change while the coded AAC payload stays equal.
        def box(kind, body):
            return struct.pack(">I", len(body) + 8) + kind + body
        media = box(b"free", b"device metadata") + box(b"mdat", b"unchanged coded AAC payload")
        files = {}
        for name in ("prepared-30.m4a", "prepared-60.m4a", "av-30.mp4", "av-60.mp4", "mixed-for-listening.m4a"):
            case = "peak-1" if name == "mixed-for-listening.m4a" else "av-frames-1"
            path = f"source-audio-native-evidence/{case}/{name}"
            files[f"files/{path}"] = media
            files[f"files/{path}.device-sha256.json"] = json.dumps({"path": path, "sha256": hashlib.sha256(media).hexdigest()}).encode()
        files["files/source-audio-native-evidence/peak-1/float-oracle.json"] = json.dumps({
            "aac": "mixed-for-listening.m4a", "sampleFrames": 48000, "sampleRate": 48000, "channels": 2, "androidPcmEncoding": 2}).encode()
        return files

    def archive(self, directory, files, duplicate=None):
        archive = Path(directory) / "source-audio-native-evidence.tar"
        with tarfile.open(archive, "w") as package:
            for name, data in [*files.items(), *([] if duplicate is None else [(duplicate, files[duplicate])])]:
                entry = tarfile.TarInfo(name); entry.size = len(data)
                package.addfile(entry, io.BytesIO(data))

    def reject_before_decode(self, files, message, duplicate=None):
        with tempfile.TemporaryDirectory() as directory, patch("verify_source_aac.run") as decoder:
            self.archive(directory, files, duplicate)
            with self.assertRaisesRegex(ValueError, message):
                verify(directory)
            decoder.assert_not_called()

    def test_headroom_and_real_float_overshoot(self):
        self.assertAlmostEqual(0.8, analyze([0.0, -0.8, 0.7])["unclampedFloatPeak"])
        for signal in ([0.0, 1.02], [1.0] * 16, [0.3, math.nan], [], [0.0] * 20):
            with self.subTest(signal=signal), self.assertRaises(ValueError):
                analyze(signal)

    def test_missing_device_artifacts_never_pass(self):
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            verify(Path(directory))

    def test_corrupt_device_aac_hash_fails_before_decoder(self):
        files = self.evidence()
        files["files/source-audio-native-evidence/peak-1/mixed-for-listening.m4a"] = b"altered AAC bytes"
        self.reject_before_decode(files, "device-recorded hash")

    def test_every_marker_rejects_metadata_tamper_even_with_identical_coded_audio(self):
        for name in ("prepared-30.m4a", "prepared-60.m4a", "av-30.mp4", "av-60.mp4"):
            with self.subTest(name=name):
                files = self.evidence()
                path = f"files/source-audio-native-evidence/av-frames-1/{name}"
                before = files[path]
                files[path] = before.replace(b"device metadata", b"hostxx metadata")
                self.assertNotEqual(before, files[path])
                self.assertEqual(before[before.index(b"mdat"):], files[path][files[path].index(b"mdat"):])
                self.reject_before_decode(files, "device-recorded hash")

    def test_missing_extra_ambiguous_or_malformed_entries_never_reach_decoder(self):
        path = "files/source-audio-native-evidence/av-frames-1/av-30.mp4"
        manifest = path + ".device-sha256.json"
        for case in ("missing-entry", "extra-entry", "missing-file", "ambiguous-file", "duplicate-path", "unknown-name", "wrong-path", "malformed-hash"):
            with self.subTest(case=case):
                files = self.evidence()
                if case == "missing-entry":
                    del files[manifest]
                elif case == "extra-entry":
                    files[manifest + ".extra.device-sha256.json"] = files[manifest]
                elif case == "missing-file":
                    del files[path]
                elif case == "ambiguous-file":
                    files["files/source-audio-native-evidence/other/av-30.mp4"] = files[path]
                else:
                    entry = json.loads(files[manifest])
                    if case == "duplicate-path":
                        entry["path"] = "source-audio-native-evidence/av-frames-1/av-60.mp4"
                    elif case == "unknown-name":
                        entry["path"] = "source-audio-native-evidence/av-frames-1/other.mp4"
                    elif case == "wrong-path":
                        entry["path"] = "source-audio-native-evidence/../av-30.mp4"
                    else:
                        entry["sha256"] = "x" * 64
                    files[manifest] = json.dumps(entry).encode()
                self.reject_before_decode(files, "device artifact")

    def test_duplicate_archive_member_never_reaches_decoder(self):
        self.reject_before_decode(self.evidence(), "Duplicate/ambiguous evidence member",
                                  "files/source-audio-native-evidence/av-frames-1/av-30.mp4")

    def test_stale_extraction_cannot_supply_missing_current_evidence(self):
        with tempfile.TemporaryDirectory() as directory, patch("verify_source_aac.run") as decoder:
            self.archive(directory, self.evidence())
            (Path(directory) / "source-audio-host-oracle").mkdir()
            with self.assertRaisesRegex(ValueError, "must be fresh"):
                verify(directory)
            decoder.assert_not_called()

    def test_collection_rejects_remote_error_empty_and_truncated_tar_before_decode(self):
        with tempfile.TemporaryDirectory() as directory, patch("verify_source_aac.run") as decoder:
            archive = Path(directory) / "source-audio-native-evidence.tar"
            archive.write_bytes(b"tar: files/source-audio-native-evidence: No such file or directory\n" + bytes(1024))
            with self.assertRaisesRegex(ValueError, "complete tar"):
                verify(directory)
            with tarfile.open(archive, "w"):
                pass
            with self.assertRaisesRegex(ValueError, "Empty source AAC transfer"):
                verify(directory)
            self.archive(directory, self.evidence())
            validate_evidence_archive(archive)  # actual nonempty tar must pass transfer validation
            complete = archive.read_bytes()
            archive.write_bytes(complete[:700])
            with self.assertRaisesRegex(ValueError, "complete tar"):
                verify(directory)
            archive.write_bytes(complete[:1024])  # a complete first member but no end-of-archive blocks
            with self.assertRaisesRegex(ValueError, "Truncated source AAC tar terminator"):
                verify(directory)
            archive.write_bytes(complete + b"x" + bytes(511))
            with self.assertRaisesRegex(ValueError, "trailing source AAC tar bytes"):
                verify(directory)
            decoder.assert_not_called()
        self.reject_before_decode({**self.evidence(), "../outside": b"bad"}, "Unsafe/unexpected evidence path")

    def test_complete_device_hash_set_authenticates_exact_paths_and_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory)
            files = self.evidence()
            for name, data in files.items():
                path = destination / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
            result = authenticate_artifacts(destination)
            self.assertEqual({"prepared-30.m4a", "prepared-60.m4a", "av-30.mp4", "av-60.mp4", "mixed-for-listening.m4a"}, set(result))
            for path, digest in result.values():
                self.assertEqual(files[path.relative_to(destination).as_posix()], path.read_bytes())
                self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), digest)


if __name__ == "__main__":
    unittest.main()
