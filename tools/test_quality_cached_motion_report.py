import gzip
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

import quality_cached_motion_report as cached_report

from quality_cached_motion_report import (MAGIC, MOTION_METHOD, MOTION_THRESHOLD,
                                         SEMANTICS_PROFILE, CORRESPONDENCE_PROFILE,
                                         cache_file_name, fear_summary, parse_cache, write_report)


SOURCE_SHA = "a" * 64


def pack(fmt, *values):
    return struct.pack(">" + fmt, *values)


def motion(subject=.3, camera=0, interval=250_000, cells=12, fraction=.8):
    return (subject, camera, 0, .8, cells, fraction, 20, 4, interval)


def camera(time, x=.3, previous=None, semantic=None, interval=None, method=MOTION_METHOD):
    previous = time - 250_000 if previous is None else previous
    return (x, 0, .8, 20, 4, previous, time,
            time if semantic is None else semantic,
            time - previous if interval is None else interval, method)


def observation(time, version=17, body=None, independent=None, flags=(True, False), face=False):
    # Explicit DataOutputStream order; legacy vectors deliberately large.
    payload = pack("qffffffB", time, 1, 0, 0, 1, 0, 0, face)
    if face:
        payload += pack("ffffff", .9, 10, -2, 3, .1, -.1)
    payload += pack("ffffffB", .2, .1, .9, .8, .9, .5, 1)
    payload += pack("fffffff", .5, .8, .8, .8, .8, .1, .9)
    payload += pack("B", body is not None)
    if body is not None:
        payload += pack("ffffifiiq", *body)
    payload += pack("BB", *flags)
    if version >= 17:
        payload += pack("B", independent is not None)
        if independent is not None:
            payload += pack("fffiiqqqq", *independent[:-1])
            encoded = independent[-1].encode("utf-8")
            payload += pack("H", len(encoded)) + encoded
    return payload


def attachment():
    return (pack("qB", 250_000, 1) + pack("iifffff", 2, 2, .9, .1, .2, .8, .9) +
            pack("BBfffB", 0, 0, .8, .1, .8, 1) + pack("fffff", .5, .3, .2, .2, .9) +
            pack("BfB", 0, 0, 0))


def cache(rows, version=17, duration=5_000_000, declared_count=None, with_attachment=False,
          profile=CORRESPONDENCE_PROFILE, assessments=None):
    count = len(rows) if declared_count is None else declared_count
    payload = pack("ii", MAGIC, version)
    if version == 18:
        encoded = profile.encode("utf-8")
        payload += pack("H", len(encoded)) + encoded
        completed = (0 if profile == SEMANTICS_PROFILE else len(rows)) if assessments is None else assessments
        payload += pack("i", completed)
    payload += pack("qiiii", duration, len(rows), len(rows), len(rows) * 3, count)
    payload += b"".join(rows)
    payload += pack("i", 1 if with_attachment else 0)
    if with_attachment:
        payload += attachment()
    payload += pack("i", 0)
    return gzip.compress(payload, mtime=0)


class CachedMotionReportTest(unittest.TestCase):
    def parse(self, rows, version=17, **kwargs):
        return parse_cache(cache(rows, version, **kwargs), SOURCE_SHA, version)

    def test_schema17_retains_actual_fields_and_exact_compressed_identity(self):
        raw = cache([observation(250_000, body=motion(), independent=camera(250_000), face=True)],
                    with_attachment=True)
        result = parse_cache(raw, SOURCE_SHA, 17)
        self.assertEqual(hashlib.sha256(raw).hexdigest(), result["cache_sha256"])
        self.assertEqual(1, result["schema_version"])
        self.assertTrue(result["cache_only"])
        self.assertTrue(result["not_acceptance"])
        self.assertFalse(result["fresh_frames_decoded"])
        self.assertEqual(1, result["attachment_frames"])
        self.assertEqual(1, result["body_measured_samples"])
        self.assertEqual(1, result["independent_camera_measured_samples"])
        self.assertEqual(0, result["camera_only_measured_samples"])
        self.assertEqual(250_000, result["samples"][0]["camera_measurement"]["semantic_pts_us"])
        self.assertEqual(MOTION_METHOD, result["samples"][0]["camera_measurement"]["method"])
        self.assertTrue(result["samples"][0]["face_inference_succeeded"])
        self.assertFalse(result["samples"][0]["gesture_evidence_available"])

    def test_schema16_reads_both_flags_without_inventing_camera(self):
        result = self.parse([observation(250_000, 16, body=motion(), flags=(False, True)),
                             observation(500_000, 16, flags=(True, False))], 16)
        self.assertEqual(0, result["independent_camera_measured_samples"])
        self.assertEqual(1, result["body_unknown_samples"])
        self.assertEqual(1, result["fear"]["camera_measured_samples"])
        self.assertTrue(all(row["camera_measurement"] is None for row in result["samples"]))
        self.assertEqual([False, True], [row["face_inference_succeeded"] for row in result["samples"]])

    def test_old_or_mismatched_version_rejected(self):
        for version in (4, 15, 18):
            with self.assertRaises(ValueError):
                parse_cache(cache([observation(250_000, 16)], version), SOURCE_SHA, 16)
        with self.assertRaises(ValueError):
            parse_cache(cache([observation(250_000, 17)], 17), SOURCE_SHA, 16)

    def test_camera_only_movement_can_supply_or_but_never_body(self):
        result = self.parse([observation(time, independent=camera(time))
                             for time in (250_000, 500_000, 750_000)])
        self.assertEqual(0, result["body_measured_samples"])
        self.assertEqual(3, result["camera_only_measured_samples"])
        self.assertAlmostEqual(.3, result["measured_camera_intensity_peak"])
        self.assertIsNone(result["measured_subject_intensity_peak"])
        self.assertEqual({"moving_samples": 3, "longest_motion_run": 3,
                          "longest_motion_run_span_us": 500_000, "measured_samples": 3,
                          "unknown_samples": 0, "subject_measured_samples": 0,
                          "camera_measured_samples": 3, "conclusive_samples": 3,
                          "inconclusive_samples": 0, "cached_fear_opening_motion_supported": True},
                         result["fear"])

    def test_static_camera_unknown_body_is_inconclusive_not_static_subject(self):
        result = self.parse([observation(time, independent=camera(time, x=0))
                             for time in (250_000, 500_000, 750_000)])
        self.assertEqual(3, result["fear"]["measured_samples"])
        self.assertEqual(0, result["fear"]["conclusive_samples"])
        self.assertEqual(3, result["fear"]["inconclusive_samples"])
        self.assertFalse(result["fear"]["cached_fear_opening_motion_supported"])
        self.assertIsNone(result["measured_subject_intensity_peak"])

    def test_unknown_and_static_interrupt_runs_and_legacy_vectors_do_not_count(self):
        for middle in (observation(500_000), observation(500_000, independent=camera(500_000, x=0))):
            result = self.parse([observation(250_000, independent=camera(250_000)), middle,
                                 observation(750_000, independent=camera(750_000)),
                                 observation(1_000_000, independent=camera(1_000_000))])
            self.assertEqual(3, result["fear"]["moving_samples"])
            self.assertEqual(2, result["fear"]["longest_motion_run"])
            self.assertFalse(result["fear"]["cached_fear_opening_motion_supported"])

    def test_disconnected_actual_camera_pts_interrupt_sustained_run(self):
        result = self.parse([observation(250_000, independent=camera(250_000)),
                             observation(500_000, independent=camera(500_000, previous=300_000)),
                             observation(750_000, independent=camera(750_000))])
        self.assertEqual(2, result["fear"]["longest_motion_run"])
        self.assertFalse(result["fear"]["cached_fear_opening_motion_supported"])

    def test_v16_interval_is_not_velocity_and_three_samples_need_half_second(self):
        short = self.parse([observation(time, 16, body=motion(interval=100_000))
                            for time in (100_000, 200_000, 300_000)], 16)
        self.assertEqual(3, short["fear"]["longest_motion_run"])
        self.assertEqual(200_000, short["fear"]["longest_motion_run_span_us"])
        self.assertFalse(short["fear"]["cached_fear_opening_motion_supported"])
        long_gap = self.parse([observation(time, 16, body=motion())
                               for time in (250_000, 1_000_000, 1_250_000)], 16)
        self.assertEqual(2, long_gap["fear"]["longest_motion_run"])

    def test_body_and_camera_count_union_without_double_counting(self):
        result = self.parse([observation(time, body=motion(), independent=camera(time))
                             for time in (250_000, 500_000, 750_000)])
        self.assertEqual(3, result["fear"]["subject_measured_samples"])
        self.assertEqual(3, result["fear"]["camera_measured_samples"])
        self.assertEqual(3, result["fear"]["measured_samples"])
        self.assertEqual(3, result["fear"]["conclusive_samples"])

    def test_float32_threshold_matches_kotlin_boundary(self):
        threshold, = struct.unpack(">f", struct.pack(">f", .18))
        self.assertEqual(threshold, MOTION_THRESHOLD)
        bits, = struct.unpack(">I", struct.pack(">f", threshold))
        below, = struct.unpack(">f", struct.pack(">I", bits - 1))
        for value, moving in ((below, 0), (threshold, 1)):
            result = self.parse([observation(250_000, independent=camera(250_000, x=value))])
            self.assertEqual(moving, result["fear"]["moving_samples"])

    def test_stale_camera_bad_support_or_unknown_method_rejected(self):
        invalid = (camera(250_000, semantic=0), camera(250_000, interval=100_000),
                   camera(250_000, previous=-1), camera(250_000, method="legacy"),
                   camera(250_000, method="é"), camera(250_000, x=float("nan")),
                   camera(250_000, x=2), camera(250_000, previous=200_000))
        for value in invalid:
            with self.subTest(camera=value), self.assertRaises(ValueError):
                self.parse([observation(250_000, independent=value)])
        for index, value in ((2, .49), (3, 7), (4, 2), (6, 300_000)):
            bad = list(camera(250_000))
            bad[index] = value
            with self.assertRaises(ValueError):
                self.parse([observation(250_000, independent=tuple(bad))])

    def test_invalid_body_rate_support_and_nonfinite_values_rejected(self):
        for body in (motion(interval=99_999), motion(interval=500_001),
                     motion(cells=3), motion(fraction=.249), motion(subject=float("nan"))):
            with self.assertRaises(ValueError):
                self.parse([observation(250_000, body=body)])

    def test_counts_flags_and_observation_order_fail_closed(self):
        for rows, options in (([observation(250_000)], {"declared_count": 0}),
                              ([observation(250_000)], {"declared_count": 4001}),
                              ([observation(250_000)], {"declared_count": 2}),
                              ([observation(250_000, flags=(2, 0))], {}),
                              ([observation(250_000), observation(250_000)], {}),
                              ([observation(500_000), observation(250_000)], {}),
                              ([observation(250_000)], {"duration": 250_000})):
            with self.assertRaises(ValueError):
                self.parse(rows, **options)

    def test_truncated_observations_and_attachments_or_trailing_data_rejected(self):
        raw = cache([observation(250_000, independent=camera(250_000))], with_attachment=True)
        payload = gzip.decompress(raw)
        for cut in (1, 4, 8, 40, len(payload) - 1, len(payload) - 8):
            with self.assertRaises((ValueError, EOFError)):
                parse_cache(gzip.compress(payload[:cut]), SOURCE_SHA, 17)
        with self.assertRaises(ValueError):
            parse_cache(gzip.compress(payload + b"unexpected"), SOURCE_SHA, 17)
        with self.assertRaises((ValueError, EOFError)):
            parse_cache(raw[:-4], SOURCE_SHA, 17)

    def test_exclusive_report_creation_preserves_existing_evidence(self):
        result = self.parse([observation(250_000)])
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "diagnostic.json"
            write_report(result, destination)
            original = destination.read_bytes()
            self.assertEqual(1, json.loads(original)["schema_version"])
            with self.assertRaises(FileExistsError):
                write_report(dict(result, schema_version=999), destination)
            self.assertEqual(original, destination.read_bytes())

    def test_schema18_profile_key_is_explicit_and_historical_keys_remain_unchanged(self):
        for profile in (SEMANTICS_PROFILE, CORRESPONDENCE_PROFILE):
            self.assertEqual(f"v18-{profile}-250000-{SOURCE_SHA}.bin.gz",
                             cache_file_name(SOURCE_SHA, 18, profile))
        for version in (16, 17):
            self.assertEqual(f"v{version}-250000-{SOURCE_SHA}.bin.gz", cache_file_name(SOURCE_SHA, version))
            for profile in (SEMANTICS_PROFILE, CORRESPONDENCE_PROFILE, "unknown", ""):
                with self.assertRaises(ValueError):
                    cache_file_name(SOURCE_SHA, version, profile)
                with self.assertRaises(ValueError):
                    parse_cache(cache([observation(250_000, version)], version), SOURCE_SHA, version, profile)
        for profile in (None, "unknown", "", "editorial-correspondence-v2"):
            with self.assertRaises(ValueError):
                cache_file_name(SOURCE_SHA, 18, profile)

    def test_schema18_profile_header_cannot_be_inferred_from_null_records_or_caller(self):
        raw = cache([observation(250_000, 18)], 18, profile=SEMANTICS_PROFILE)
        with self.assertRaises(ValueError):
            parse_cache(raw, SOURCE_SHA, 18)
        with self.assertRaises(ValueError):
            parse_cache(raw, SOURCE_SHA, 18, CORRESPONDENCE_PROFILE)
        with self.assertRaises(ValueError):
            parse_cache(cache([observation(250_000, 18)], 18, profile="unknown"),
                        SOURCE_SHA, 18, SEMANTICS_PROFILE)

    def test_schema18_semantics_is_not_requested_not_false_motion_rejection(self):
        rows = [observation(time, 18) for time in (250_000, 500_000, 750_000)]
        result = parse_cache(cache(rows, 18, profile=SEMANTICS_PROFILE), SOURCE_SHA, 18, SEMANTICS_PROFILE)
        self.assertEqual(SEMANTICS_PROFILE, result["cache_profile"])
        self.assertEqual(0, result["correspondence_assessments_completed"])
        self.assertEqual("NOT_REQUESTED", result["correspondence_state"])
        self.assertFalse(result["correspondence_requested"])
        self.assertIsNone(result["body_unknown_samples"])
        self.assertEqual(3, result["fear"]["not_requested_samples"])
        self.assertFalse(result["fear"]["assessed"])
        for field in ("unknown_samples", "inconclusive_samples", "conclusive_samples",
                      "moving_samples", "cached_fear_opening_motion_supported"):
            self.assertIsNone(result["fear"][field])
        self.assertTrue(all(row["motion_measurement"] is None and row["camera_measurement"] is None
                            for row in result["samples"]))

    def test_schema18_semantics_rejects_body_or_camera_records_even_if_counts_zero(self):
        for row in (observation(250_000, 18, body=motion()),
                    observation(250_000, 18, independent=camera(250_000)),
                    observation(250_000, 18, body=motion(), independent=camera(250_000))):
            with self.assertRaises(ValueError):
                parse_cache(cache([row], 18, profile=SEMANTICS_PROFILE), SOURCE_SHA, 18, SEMANTICS_PROFILE)

    def test_schema18_all_unknown_correspondence_requires_all_attempts(self):
        rows = [observation(time, 18) for time in (250_000, 500_000, 750_000)]
        result = parse_cache(cache(rows, 18), SOURCE_SHA, 18, CORRESPONDENCE_PROFILE)
        self.assertEqual("ASSESSED", result["correspondence_state"])
        self.assertEqual(3, result["correspondence_assessments_completed"])
        self.assertTrue(result["correspondence_requested"])
        self.assertEqual(3, result["body_unknown_samples"])
        self.assertEqual(3, result["fear"]["unknown_samples"])
        self.assertEqual(0, result["fear"]["conclusive_samples"])
        self.assertEqual(3, result["fear"]["inconclusive_samples"])
        self.assertTrue(result["fear"]["assessed"])
        self.assertFalse(result["fear"]["cached_fear_opening_motion_supported"])
        for count in (-1, 0, 1, 2, 4, 4001):
            with self.subTest(count=count), self.assertRaises(ValueError):
                parse_cache(cache(rows, 18, assessments=count), SOURCE_SHA, 18, CORRESPONDENCE_PROFILE)
        for count in (-1, 1, 3, 4001):
            with self.subTest(editorial_count=count), self.assertRaises(ValueError):
                parse_cache(cache(rows, 18, profile=SEMANTICS_PROFILE, assessments=count),
                            SOURCE_SHA, 18, SEMANTICS_PROFILE)

    def test_schema18_full_profile_retains_v17_measurements_and_attachment_layout(self):
        raw = cache([observation(250_000, 18, body=motion(), independent=camera(250_000), face=True)],
                    18, with_attachment=True)
        result = parse_cache(raw, SOURCE_SHA, 18, CORRESPONDENCE_PROFILE)
        self.assertEqual(1, result["correspondence_assessments_completed"])
        self.assertEqual(1, result["body_measured_samples"])
        self.assertEqual(1, result["independent_camera_measured_samples"])
        self.assertEqual(1, result["attachment_frames"])
        self.assertEqual(MOTION_METHOD, result["samples"][0]["camera_measurement"]["method"])
        self.assertEqual(hashlib.sha256(raw).hexdigest(), result["cache_sha256"])

    def test_schema18_truncated_or_nonascii_profile_and_trailing_payload_rejected(self):
        raw = cache([observation(250_000, 18)], 18)
        payload = gzip.decompress(raw)
        token_end = 10 + len(CORRESPONDENCE_PROFILE)
        for cut in (8, 9, 10, token_end - 1, token_end + 2, token_end + 4, len(payload) - 1):
            with self.subTest(cut=cut), self.assertRaises((ValueError, EOFError)):
                parse_cache(gzip.compress(payload[:cut]), SOURCE_SHA, 18, CORRESPONDENCE_PROFILE)
        corrupt = pack("iiH", MAGIC, 18, 1000) + b"short"
        with self.assertRaises(ValueError):
            parse_cache(gzip.compress(corrupt), SOURCE_SHA, 18, CORRESPONDENCE_PROFILE)
        with self.assertRaises(ValueError):
            parse_cache(cache([observation(250_000, 18)], 18, profile="é"), SOURCE_SHA, 18, SEMANTICS_PROFILE)
        with self.assertRaises(ValueError):
            parse_cache(gzip.compress(payload + b"extra"), SOURCE_SHA, 18, CORRESPONDENCE_PROFILE)

    def test_schema18_bad_header_version_magic_and_count_not_cast_to_measurement_count(self):
        raw = cache([observation(250_000, 18)], 18)
        payload = gzip.decompress(raw)
        for prefix in (pack("ii", 0, 18), pack("ii", MAGIC, 17), pack("ii", MAGIC, 19)):
            with self.assertRaises(ValueError):
                parse_cache(gzip.compress(prefix + payload[8:]), SOURCE_SHA, 18, CORRESPONDENCE_PROFILE)
        # One typed record does not justify declaring one assessment for two observations.
        rows = [observation(250_000, 18, body=motion()), observation(500_000, 18)]
        with self.assertRaises(ValueError):
            parse_cache(cache(rows, 18, assessments=1), SOURCE_SHA, 18, CORRESPONDENCE_PROFILE)


class CacheSnapshotTest(unittest.TestCase):
    def capture(self, root, snapshot=True, serial="emulator-5554", raw=None):
        payload = raw if raw is not None else cache([observation(250_000, 18)],
                                                   18, profile=SEMANTICS_PROFILE)
        with patch.object(cached_report.subprocess, "run") as run:
            run.return_value.stdout = payload
            report = cached_report.capture_cache(
                "adb.exe", serial, SOURCE_SHA, 18, SEMANTICS_PROFILE,
                root / "report.json", root / "snapshot.bin.gz" if snapshot else None)
            return payload, report, run.call_args

    def test_selected_device_snapshot_keeps_exact_validated_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw, report, call = self.capture(root)
            self.assertEqual(raw, (root / "snapshot.bin.gz").read_bytes())
            self.assertEqual(["adb.exe", "-s", "emulator-5554", "exec-out", "run-as",
                              "com.veycad.app", "cat", "cache/full-video-analysis/" +
                              cache_file_name(SOURCE_SHA, 18, SEMANTICS_PROFILE)], call.args[0])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), report["cache_sha256"])
            self.assertEqual(str(root / "snapshot.bin.gz"), report["snapshot_file"])
            self.assertEqual(json.loads(json.dumps(report)),
                             json.loads((root / "report.json").read_text()))
            self.assertTrue(report["not_acceptance"])

    def test_existing_snapshot_is_preserved_before_device_read(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "snapshot.bin.gz").write_bytes(b"historical")
            with patch.object(cached_report.subprocess, "run") as run:
                with self.assertRaises(FileExistsError):
                    cached_report.capture_cache("adb.exe", "emulator-5554", SOURCE_SHA,
                        18, SEMANTICS_PROFILE, root / "report.json", root / "snapshot.bin.gz")
                run.assert_not_called()
            self.assertEqual(b"historical", (root / "snapshot.bin.gz").read_bytes())
            self.assertFalse((root / "report.json").exists())

    def test_existing_report_is_preserved_before_device_read(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "report.json").write_text("historical")
            with patch.object(cached_report.subprocess, "run") as run:
                with self.assertRaises(FileExistsError):
                    cached_report.capture_cache("adb.exe", "emulator-5554", SOURCE_SHA,
                        18, SEMANTICS_PROFILE, root / "report.json", root / "snapshot.bin.gz")
                run.assert_not_called()
            self.assertEqual("historical", (root / "report.json").read_text())
            self.assertFalse((root / "snapshot.bin.gz").exists())

    def test_snapshot_and_report_must_have_different_targets(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "same"
            with patch.object(cached_report.subprocess, "run") as run:
                with self.assertRaises(ValueError):
                    cached_report.capture_cache("adb.exe", "emulator-5554", SOURCE_SHA,
                        18, SEMANTICS_PROFILE, path, path)
                run.assert_not_called()
            self.assertFalse(path.exists())

    def test_invalid_cache_never_becomes_a_snapshot_or_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises((ValueError, EOFError, gzip.BadGzipFile)):
                self.capture(root, raw=b"not a cache")
            self.assertFalse((root / "snapshot.bin.gz").exists())
            self.assertFalse((root / "report.json").exists())

    def test_legacy_call_without_serial_or_snapshot_keeps_command_and_schema(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw, report, call = self.capture(root, snapshot=False, serial=None)
            self.assertEqual(["adb.exe", "exec-out", "run-as", "com.veycad.app", "cat",
                              "cache/full-video-analysis/" +
                              cache_file_name(SOURCE_SHA, 18, SEMANTICS_PROFILE)], call.args[0])
            self.assertEqual(parse_cache(raw, SOURCE_SHA, 18, SEMANTICS_PROFILE), report)
            self.assertFalse((root / "snapshot.bin.gz").exists())

    def test_empty_or_padded_explicit_serial_cannot_fall_back_to_another_device(self):
        for serial in ("", " emulator-5554", "emulator-5554 "):
            with self.subTest(serial=serial), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                with patch.object(cached_report.subprocess, "run") as run:
                    with self.assertRaises(ValueError):
                        cached_report.capture_cache("adb.exe", serial, SOURCE_SHA,
                            18, SEMANTICS_PROFILE, root / "report.json", root / "snapshot.bin.gz")
                    run.assert_not_called()
                self.assertEqual([], list(root.iterdir()))


if __name__ == "__main__":
    unittest.main()
