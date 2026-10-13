"""Pure source/helper tests: no codecs, compiler, Gradle or media process."""
import hashlib
import struct
import unittest
from prepare_software_aac_reference import au_identity, float_stats, hex_dump


class ReferenceTests(unittest.TestCase):
    def test_packet_hash_reads_mp4_bytes_and_length_prefix(self):
        packet = {'data': '\n00000000: 1234 abcd  .4..\n', 'size': '4', 'pos': '2', 'pts': '-1024'}
        rolling, ledger = au_identity([packet], b'xx\x12\x34\xab\xcd')
        self.assertEqual(rolling, hashlib.sha256(struct.pack('<I', 4) + bytes.fromhex('1234abcd')).hexdigest())
        self.assertEqual(ledger[0]['pts'], -1024)
        with self.assertRaises(ValueError):
            au_identity([packet], b'xxwrong')

    def test_hex_dump_excludes_ascii(self):
        self.assertEqual(hex_dump('\n00000000: 6162 63  abc\n'), b'abc')

    def test_float_calibration_keeps_overshoot_and_channel_frames(self):
        values = struct.pack('<4f', 1.25, -1.5, 0.5, 0.0)
        stats = float_stats(values, 2)
        self.assertEqual(stats['frame_count'], 2)
        self.assertEqual(stats['sample_count'], 4)
        self.assertEqual(stats['overshoot_sample_count'], 2)
        self.assertEqual(stats['peak'], 1.5)
        with self.assertRaises(ValueError):
            float_stats(values[:-1], 2)
        with self.assertRaises(ValueError):
            float_stats(struct.pack('<f', float('nan')), 1)


if __name__ == '__main__':
    unittest.main()
