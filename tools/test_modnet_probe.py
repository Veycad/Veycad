import unittest

from evaluate_modnet_probe import input_size


class InputSizeTest(unittest.TestCase):
    def test_native_portrait(self):
        self.assertEqual(input_size(1080, 1920), (512, 896))

    def test_small_portrait(self):
        self.assertEqual(input_size(270, 480), (512, 896))

    def test_landscape(self):
        self.assertEqual(input_size(1920, 1080), (896, 512))

    def test_straddling_reference_preserves_scale(self):
        self.assertEqual(input_size(480, 640), (480, 640))

    def test_reference_and_alignment_boundaries_preserve_policy_and_orientation(self):
        for dimensions, expected in (((511, 511), (512, 512)), ((512, 512), (512, 512)),
                                      ((513, 513), (512, 512)), ((512, 769), (512, 768)),
                                      ((481, 640), (480, 640)), ((1, 512), (32, 512)),
                                      ((1, 1), (512, 512))):
            with self.subTest(dimensions=dimensions):
                self.assertEqual(expected, input_size(*dimensions))
                self.assertEqual(expected[::-1], input_size(*dimensions[::-1]))


if __name__ == "__main__":
    unittest.main()
