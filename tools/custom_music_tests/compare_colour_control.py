"""Compare exact frozen visual inputs and decoded witnesses, never a release QA substitute."""
import argparse
import json
import re
from pathlib import Path


BASE_SHA = 'a0395e26b93463f91520181a2eeb09dfd05e78a3'


def compare(base, current, expected_current_sha=None):
    if not isinstance(expected_current_sha, str) or not re.fullmatch('[0-9a-f]{40}', expected_current_sha):
        raise ValueError('Expected diagnostic current SHA is required')
    if expected_current_sha == BASE_SHA:
        raise ValueError('Base/current roles must refer to distinct commits')
    for report, role, head in [(base, 'colour-base', BASE_SHA), (current, 'colour-current', expected_current_sha)]:
        if report.get('build_ref') != role or report.get('build_head_sha') != head:
            raise ValueError('Wrong base/current build role or SHA')
        blobs = report.get('model_git_blobs_declared_by_build')
        if not isinstance(blobs, dict) or not blobs or any(not re.fullmatch('[0-9a-f]{40}', str(v)) for v in blobs.values()):
            raise ValueError('Missing/invalid declared model git blobs')
    for report in [base, current]:
        if report.get('success') is not True or report.get('stage') != 'complete' or report.get('release_acceptance') is not False:
            raise ValueError('Incomplete diagnostic or attempted release override')
        if report['original_native_qa']['quality_gate'] is not False:
            raise ValueError('Original native QA was not preserved')
        for key, count in [('planned_frames', 480), ('actual_video_source_clock', 480), ('decoded_colour_samples', 160)]:
            if not isinstance(report[key], list) or len(report[key]) != count:
                raise ValueError('Incomplete witness coverage: ' + key)
        if len(report['decoded_pixels']['frames']) != 480:
            raise ValueError('Incomplete decoded pixel coverage')
        for key in ['source_unchanged_after_render', 'frozen_json_unchanged_after_render', 'planes_unchanged_after_render']:
            if report[key] is not True:
                raise ValueError('Control input mutated: ' + key)
    keys = ['input_sha256', 'source_sha256', 'frozen_json_sha256', 'planes_sha256',
            'visual_graph_sha256_declared', 'render_inputs', 'planned_frames_sha256', 'planned_frames',
            'actual_video_source_clock', 'decoded_pixels', 'decoded_colour_samples', 'twelve_second_pair',
            'device', 'origin', 'original_native_qa']
    differences = [key for key in keys if base[key] != current[key]]
    return {'method': 'frozen-visual-base-current-comparison-v1', 'equivalent': not differences,
            'differences': differences, 'base_head_sha': base.get('build_head_sha'),
            'current_head_sha': current.get('build_head_sha'), 'native_quality_gate': False,
            'release_acceptance': False, 'projection': 'audioTrack=null; all other visual inputs unchanged',
            'limits': ['One synthetic source and frozen graph; not whole CUSTOM_MUSIC function regression',
                       'Encoded MP4 container bytes may differ; comparison uses actual decoded PTS/pixels',
                       'Real tracks, device and human acceptance remain separate']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base', type=Path)
    parser.add_argument('current', type=Path)
    parser.add_argument('destination', type=Path)
    parser.add_argument('--current-sha', required=True)
    args = parser.parse_args()
    result = compare(json.loads(args.base.read_text(encoding='utf-8')),
                     json.loads(args.current.read_text(encoding='utf-8')), args.current_sha)
    for role, path in [('base', args.base), ('current', args.current)]:
        import hashlib
        result[role + '_report_sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
    args.destination.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result))
    raise SystemExit(0 if result['equivalent'] else 1)
