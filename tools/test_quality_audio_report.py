import math
import hashlib
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from quality_audio_report import assess_pcm, measure, parse_float_wav


def wav(samples, encoding=3, streaming=False):
    payload = struct.pack("<" + "f" * len(samples), *samples)
    fmt = struct.pack("<HHIIHH", encoding, 2, 48000, 384000, 8, 32)
    if encoding == 0xFFFE:
        # Literal WAVEFORMATEXTENSIBLE IEEE float GUID, independent of parser.
        fmt += struct.pack("<HHI", 22, 32, 3) + bytes.fromhex("0300000000001000800000aa00389b71")
    body = b"WAVEfmt " + struct.pack("<I", len(fmt)) + fmt
    body += b"data" + struct.pack("<I", 0xFFFFFFFF if streaming else len(payload)) + payload
    return b"RIFF" + struct.pack("<I", 0xFFFFFFFF if streaming else len(body)) + body


class QualityAudioReportTest(unittest.TestCase):
    def test_float_wave_preserves_overshoot_in_normal_and_streaming_formats(self):
        for encoding in (3, 0xFFFE):
            for streaming in (False, True):
                rate, channels, samples = parse_float_wav(wav([1.2, -.5], encoding, streaming))
                self.assertEqual((rate, channels), (48000, 2))
                self.assertAlmostEqual(samples[0], 1.2, places=6)
                self.assertEqual(2, len(samples))
                self.assertEqual(-.5, samples[1])

    def test_wave_metadata_padding_and_duplicate_chunks_are_validated(self):
        raw = wav([.5, -.5])
        junk = b"JUNK" + struct.pack("<I", 3) + b"abc\0"
        padded = raw[:12] + junk + raw[12:]
        self.assertEqual([.5, -.5], list(parse_float_wav(padded)[2]))
        corruptions = [b"RF64" + raw[4:], raw[:8] + b"NOPE" + raw[12:],
                       raw + raw[12:36], raw + raw[36:], raw[:36], raw[:12] + raw[36:]]
        # Channel count, sample rate, byte rate, block alignment and bit depth.
        for offset, fmt, value in ((22, "H", 0), (24, "I", 0), (28, "I", 1),
                                   (32, "H", 1), (34, "H", 16)):
            changed = bytearray(raw)
            struct.pack_into("<" + fmt, changed, offset, value)
            corruptions.append(bytes(changed))
        for index, invalid in enumerate(corruptions):
            with self.subTest(index=index), self.assertRaises(ValueError):
                parse_float_wav(invalid)

    def test_integer_truncated_and_partial_frames_are_rejected(self):
        for raw in (wav([.5, -.5], encoding=1), wav([.5, -.5])[:-1], wav([.5])):
            with self.assertRaises(ValueError):
                parse_float_wav(raw)

    def test_audio_clip_and_nonfinite_evidence_fail(self):
        report = assess_pcm([1.2, -1.1, math.nan], 48000, 1, "FEAR_STROBE")
        self.assertEqual(report["over_full_scale_samples"], 2)
        self.assertIn("audio-non-finite-samples", report["issues"])
        self.assertFalse(report["passed"])

    def test_pcm_statistics_use_every_channel_and_the_frame_clock(self):
        report = assess_pcm([.5, -.25, 0, .75], 48000, 2, "FEAR_STROBE")
        self.assertEqual(4, report["pcm_samples"])
        self.assertEqual(2, report["pcm_frames"])
        self.assertEqual(41, report["decoded_duration_us"])
        self.assertEqual(.75, report["sample_peak"])
        self.assertAlmostEqual(math.sqrt(.21875), report["rms"])
        self.assertEqual(0, report["non_finite_samples"])
        self.assertEqual(0, report["over_full_scale_samples"])
        self.assertEqual(0, report["longest_full_scale_run"])
        self.assertEqual(["audio-decoded-duration-mismatch"], report["issues"])

    def test_duration_and_plateau_thresholds_are_inclusive_only_at_contract_boundary(self):
        # FEAR is 18.3 s; at 1 kHz tolerance is 33,334 + 1,024,000 us.
        for frames, passed in ((17_243, True), (19_357, True),
                               (17_242, False), (19_358, False)):
            with self.subTest(frames=frames):
                report = assess_pcm([.5] * frames, 1000, 1, "FEAR_STROBE")
                self.assertIs(passed, report["passed"])
                self.assertEqual([] if passed else ["audio-decoded-duration-mismatch"], report["issues"])
        for values, longest in (([1, 1, 0, 0], 2), ([1, 1, 1, 0], 3),
                                ([1, 1, math.nan, 1, 1], 2), ([1, 1, -1, -1], 2)):
            with self.subTest(values=values):
                report = assess_pcm(values, 1000, 1, "FEAR_STROBE")
                self.assertEqual(longest, report["longest_full_scale_run"])
                self.assertEqual(longest >= 3, "audio-full-scale-plateau" in report["issues"])

    def test_invalid_pcm_contract_and_empty_signal_do_not_pass(self):
        for samples, rate, channels, recipe in (([.5], 1, 2, "HEARTBEAT"),
                                                ([.5], 0, 1, "HEARTBEAT"),
                                                ([.5], 1, 0, "HEARTBEAT"),
                                                ([.5], 1, 9, "HEARTBEAT"),
                                                ([.5], 1, 1, "OTHER")):
            with self.subTest(rate=rate, channels=channels, recipe=recipe), self.assertRaises(ValueError):
                assess_pcm(samples, rate, channels, recipe)
        empty = assess_pcm([], 48000, 2, "HEARTBEAT")
        self.assertFalse(empty["passed"])
        self.assertIn("audio-pcm-empty", empty["issues"])

    def test_decoder_measurement_binds_real_file_bytes_and_float_decode_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "fake.mp4"
            original = b"synthetic file bytes, not actual media"
            output.write_bytes(original)
            decoder = Path(directory) / "ffmpeg"
            answers = [subprocess.CompletedProcess([], 0, b"ffmpeg version synthetic\n"),
                       subprocess.CompletedProcess([], 0, wav([.5, -.5]))]
            with patch("quality_audio_report.subprocess.run", side_effect=answers) as run:
                report = measure(output, decoder, "HEARTBEAT")
            self.assertEqual(hashlib.sha256(original).hexdigest(), report["output_sha256"])
            self.assertEqual("ffmpeg version synthetic", report["decoder_version"])
            self.assertEqual(2, report["pcm_samples"])
            self.assertEqual(original, output.read_bytes())
            self.assertEqual(2, run.call_count)
            command = run.call_args.args[0]
            self.assertEqual(str(output), command[command.index("-i") + 1])
            self.assertEqual("pcm_f32le", command[command.index("-c:a") + 1])
            self.assertEqual("0:a:0", command[command.index("-map") + 1])
            self.assertTrue(run.call_args.kwargs["check"])
            self.assertLessEqual(run.call_args.kwargs["timeout"], 60)

            def changed_during_decode(command, **kwargs):
                if "-version" in command:
                    return answers[0]
                output.write_bytes(b"different bytes of same exported file")
                return answers[1]

            with patch("quality_audio_report.subprocess.run", side_effect=changed_during_decode):
                with self.assertRaisesRegex(ValueError, "export changed while decoding"):
                    measure(output, decoder, "HEARTBEAT")

    def test_full_scale_plateaus_are_per_channel_and_sign(self):
        report = assess_pcm([1, .2, 1, .3, 1, .4], 48000, 2, "FEAR_STROBE")
        self.assertEqual(report["longest_full_scale_run"], 3)
        self.assertIn("audio-full-scale-plateau", report["issues"])
        report = assess_pcm([1, -1, 1, -1], 48000, 1, "FEAR_STROBE")
        self.assertEqual(report["longest_full_scale_run"], 1)

    def test_complete_safe_signal_passes_but_silence_and_short_audio_fail(self):
        self.assertTrue(assess_pcm([.5] * 18300, 1000, 1, "FEAR_STROBE")["passed"])
        self.assertIn("audio-silent", assess_pcm([0] * 18300, 1000, 1, "FEAR_STROBE")["issues"])
        self.assertIn("audio-decoded-duration-mismatch", assess_pcm([.5], 1000, 1, "FEAR_STROBE")["issues"])


if __name__ == "__main__":
    unittest.main()
