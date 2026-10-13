import importlib.util
from pathlib import Path
import copy
import unittest

path = Path(__file__).with_name('compare_colour_control.py')
api = None
if path.exists():
    spec = importlib.util.spec_from_file_location('colour_control', path)
    api = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(api)


class ColourControlComparisonTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(api, 'Frozen renderer comparison is not implemented')
        self.report = {'success': True, 'stage': 'complete', 'release_acceptance': False,
                       'original_native_qa': {'quality_gate': False, 'issues': ['colour-jump']},
                       'planned_frames': list(range(480)), 'actual_video_source_clock': list(range(480)),
                       'decoded_colour_samples': list(range(160)),
                       'decoded_pixels': {'frames': list(range(480)), 'sha256': 'pixels'},
                       'source_unchanged_after_render': True, 'frozen_json_unchanged_after_render': True,
                       'planes_unchanged_after_render': True}
        for key in ['input_sha256', 'source_sha256', 'frozen_json_sha256', 'planes_sha256',
                    'visual_graph_sha256_declared', 'render_inputs', 'planned_frames_sha256',
                    'twelve_second_pair', 'device', 'origin']:
            self.report[key] = key
        self.current_sha = '9' * 40
        self.report.update(build_ref='colour-base', build_head_sha=api.BASE_SHA,
                           model_git_blobs_declared_by_build={'Renderer.kt': 'b' * 40})

    def current(self):
        report = copy.deepcopy(self.report)
        report.update(build_ref='colour-current', build_head_sha=self.current_sha)
        return report

    def test_same_visual_inputs_and_pixels_preserve_negative_native_gate(self):
        a, b = copy.deepcopy(self.report), self.current()
        a['output_sha256'], b['output_sha256'] = 'different-container-A', 'different-container-B'
        result = api.compare(a, b, self.current_sha)
        self.assertTrue(result['equivalent'])
        self.assertFalse(result['native_quality_gate'])
        self.assertFalse(result['release_acceptance'])

    def test_each_changed_witness_fails_equivalence(self):
        for field in ['input_sha256', 'planned_frames_sha256', 'actual_video_source_clock',
                      'decoded_pixels', 'decoded_colour_samples']:
            with self.subTest(field=field):
                other = self.current()
                if isinstance(other[field], list):
                    other[field][0] = 'changed'
                elif isinstance(other[field], dict):
                    other[field]['sha256'] = 'changed'
                else:
                    other[field] = 'changed'
                result = api.compare(self.report, other, self.current_sha)
                self.assertFalse(result['equivalent'])
                self.assertIn(field, result['differences'])

    def test_incomplete_report_and_gate_override_fail(self):
        for field, value in [('success', False), ('release_acceptance', True),
                             ('planned_frames', []), ('source_unchanged_after_render', False),
                             ('original_native_qa', {'quality_gate': True})]:
            with self.subTest(field=field):
                other = self.current()
                other[field] = value
                with self.assertRaises(ValueError):
                    api.compare(self.report, other, self.current_sha)

    def test_two_current_reports_cannot_establish_base_current_equivalence(self):
        current = self.current()
        with self.assertRaises(ValueError):
            api.compare(current, current, self.current_sha)

    def test_missing_wrong_or_same_commit_identities_are_rejected(self):
        for field, value in [('build_head_sha', None), ('build_head_sha', '0' * 40),
                             ('build_ref', 'colour-base'), ('model_git_blobs_declared_by_build', {})]:
            with self.subTest(field=field, value=value):
                current = self.current()
                current[field] = value
                with self.assertRaises(ValueError):
                    api.compare(self.report, current, self.current_sha)
        with self.assertRaises(ValueError):
            api.compare(self.report, self.current(), api.BASE_SHA)


if __name__ == '__main__':
    unittest.main()
