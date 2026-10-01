import json
from pathlib import Path
import subprocess
import sys
import unittest
import numpy as np
from probe_semantic_alpha_support import constrain


class SupportTest(unittest.TestCase):
    def test_empty_support_vetoes_foreground(self):
        np.testing.assert_array_equal(constrain(np.ones((80, 80)), np.zeros((20, 20))), 0)

    def test_full_support_preserves_fractional_opacity(self):
        alpha = np.full((80, 80), .37)
        np.testing.assert_array_equal(constrain(alpha, np.ones((20, 20))), alpha)

    def test_support_never_invents_foreground(self):
        alpha = np.random.default_rng(42).random((80, 80))
        core = np.zeros((20, 20))
        core[8:12, 8:12] = 1
        result = constrain(alpha, core)
        self.assertEqual(alpha.shape, result.shape)
        self.assertTrue(np.isfinite(result).all())
        self.assertTrue((result >= 0).all() and (result <= alpha).all())
        # A no-op or an all-zero implementation also satisfied the old bound.
        np.testing.assert_array_equal(result[:4, :4], 0)
        np.testing.assert_array_equal(result[36:44, 36:44], alpha[36:44, 36:44])
        self.assertGreater(np.count_nonzero((result > 0) & (result < alpha)), 0)

    def test_confidence_threshold_margin_and_rectangular_resize_have_observable_effect(self):
        alpha = np.full((24, 60), .6)
        core = np.zeros((12, 30))
        core[6, 15] = .5
        narrow = constrain(alpha, core, margin=1)
        wide = constrain(alpha, core, margin=3)
        self.assertEqual((24, 60), wide.shape)
        self.assertGreater(narrow[12, 30], 0)
        self.assertLess(narrow[12, 30], wide[12, 30])
        self.assertGreater(wide.sum(), narrow.sum())
        core[6, 15] = np.nextafter(.5, 0)
        np.testing.assert_array_equal(constrain(alpha, core, margin=3), 0)

    def test_zero_margin_preserves_support_without_native_process_crash(self):
        # Keep native image-filter failures isolated: they must fail a test,
        # never terminate discovery before unittest prints its result.
        code = ("import json, numpy as np; from probe_semantic_alpha_support import constrain; "
                "a=np.full((24,60), .6); r=constrain(a,np.ones((12,30)),0); "
                "print(json.dumps({'shape':list(r.shape),'equal':bool((r==a).all())}))")
        completed = subprocess.run([sys.executable, "-c", code],
                                   cwd=Path(__file__).resolve().parent,
                                   capture_output=True, text=True, timeout=15)
        self.assertEqual(0, completed.returncode,
                         f"zero margin crashed/failed: {completed.stderr}")
        self.assertEqual({"shape": [24, 60], "equal": True}, json.loads(completed.stdout))

    def test_bad_margin_rejected(self):
        for margin in (-1, 33):
            with self.subTest(margin=margin), self.assertRaises(ValueError):
                constrain(np.ones((8, 8)), np.ones((8, 8)), margin)

    def test_invalid_planes_rejected(self):
        for invalid in [np.full((2, 2), np.nan), np.full((2, 2), np.inf),
                        np.full((2, 2), 1.1), np.full((2, 2), -.1),
                        np.zeros((2, 2, 1)), np.zeros((0, 0))]:
            for plane in ("alpha", "core"):
                with self.subTest(shape=invalid.shape, plane=plane):
                    with self.assertRaises(ValueError):
                        constrain(invalid, np.ones((2, 2))) if plane == "alpha" else \
                            constrain(np.ones((2, 2)), invalid)


if __name__ == "__main__":
    unittest.main()
