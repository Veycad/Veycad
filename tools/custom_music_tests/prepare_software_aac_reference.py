"""Remote independent compressed AAC materials; never runs an Android candidate."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
from fractions import Fraction

SOURCE_COMMIT = 'f893221c8d89cb798b829bebe71d55e1a3f242fd'
NATIVE_SHA = '09bb26563846c418b5452d035beb2a83fb528e4bca68e5d46c37eed6d259f187'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def hex_dump(text):
    """ffprobe show_data has an ASCII column; never hash that column."""
    result = bytearray()
    for line in text.splitlines():
        if ':' not in line:
            continue
        payload = line.split(':', 1)[1].strip().split('  ', 1)[0]
        require(bool(re.fullmatch(r'[0-9a-fA-F ]*', payload)), 'Malformed ffprobe hex')
        result.extend(bytes.fromhex(payload))
    return bytes(result)


def au_identity(packets, source):
    rolling = hashlib.sha256()
    ledger = []
    for index, packet in enumerate(packets):
        payload = hex_dump(packet['data'])
        require(len(payload) == int(packet['size']), 'Packet dump size mismatch')
        position = int(packet['pos'])
        require(source[position:position + len(payload)] == payload, 'Packet bytes differ from MP4')
        rolling.update(struct.pack('<I', len(payload)))
        rolling.update(payload)
        ledger.append({'index': index, 'size': len(payload), 'sha256': digest(payload),
                       'pos': position, 'pts': int(packet['pts']),
                       'duration': int(packet.get('duration', 0)),
                       'side_data_list': packet.get('side_data_list', [])})
    require(bool(ledger), 'Empty AU sequence')
    return rolling.hexdigest(), ledger


def float_stats(data, channels):
    require(channels > 0 and len(data) % (4 * channels) == 0, 'Partial float frame')
    values = [item[0] for item in struct.iter_unpack('<f', data)]
    require(bool(values) and all(math.isfinite(x) for x in values), 'Empty/nonfinite reference')
    return {'sample_count': len(values), 'frame_count': len(values) // channels,
            'byte_count': len(data), 'sha256': digest(data),
            'peak': max(abs(x) for x in values),
            'overshoot_sample_count': sum(abs(x) > 1.0 for x in values)}


def run(argv, log):
    result = subprocess.run([str(x) for x in argv], capture_output=True, check=False, timeout=180)
    with log.open('ab') as handle:
        handle.write((json.dumps([str(x) for x in argv]) + '\n').encode())
        handle.write(result.stderr)
    require(result.returncode == 0, 'Reference command failed: ' + str(argv[0]))
    return result.stdout


def prepare(input_dir, build, output):
    require(os.environ.get('GITHUB_ACTIONS') == 'true', 'Remote GitHub Actions only')
    require(not output.exists(), 'Refusing reused materials directory')
    require((build / 'provenance/source-commit.txt').read_text().strip() == SOURCE_COMMIT,
            'Unexpected host source revision')
    native = input_dir / 'native-editor.mp4'
    require(digest(native.read_bytes()) == NATIVE_SHA, 'Unexpected immutable CI20 MP4')
    output.mkdir(parents=True)
    shutil.copyfile(native, output / 'native_mp4.mp4')
    shutil.copyfile(input_dir / 'audio-input.json', output / 'original-native-snapshot.json')
    ffmpeg, ffprobe = build / 'host/ffmpeg', build / 'host/ffprobe'
    log = output / 'reference-commands.log'
    # AAC is actually encoded then decoded; the source amplitude is not evidence.
    rate = 48000
    source = b''.join(struct.pack('<f', 1.8 * math.sin(2 * math.pi * 997 * i / rate))
                      for i in range(rate * 2))
    (output / 'overshoot-source.f32le').write_bytes(source)
    run([ffmpeg, '-nostdin', '-f', 'f32le', '-ar', str(rate), '-ac', '1', '-i',
         output / 'overshoot-source.f32le', '-c:a', 'aac', '-profile:a', 'aac_low',
         '-b:a', '192k', '-use_editlist', '0', '-y', output / 'overshoot.mp4'], log)
    cases = []
    for case_id in ('native_mp4', 'overshoot'):
        file = output / (case_id + '.mp4')
        probe = json.loads(run([ffprobe, '-v', 'error', '-select_streams', 'a:0',
                               '-show_streams', '-show_packets', '-show_data', '-of', 'json', file], log))
        raw_probe = json.loads(run([ffprobe, '-v', 'error', '-ignore_editlist', '1',
                                   '-select_streams', 'a:0', '-show_packets', '-show_data', '-of', 'json', file], log))
        (output / (case_id + '-ffprobe.json')).write_text(json.dumps(probe, indent=2) + '\n')
        (output / (case_id + '-raw-packets.json')).write_text(json.dumps(raw_probe, indent=2) + '\n')
        stream = probe['streams'][0]
        require(stream['codec_name'] == 'aac' and stream['profile'] == 'LC', 'Expected AAC-LC')
        channels, rate = int(stream['channels']), int(stream['sample_rate'])
        time_base = Fraction(stream['time_base'])
        rolling, ledger = au_identity(probe['packets'], file.read_bytes())
        raw_rolling, raw_ledger = au_identity(raw_probe['packets'], file.read_bytes())
        require(rolling == raw_rolling, 'Edit list changes AU selection; ambiguous Android identity')
        for packet in ledger:
            packet['pts_us'] = int(Fraction(packet['pts']) * time_base * 1000000)
        (output / (case_id + '-au-ledger.json')).write_text(json.dumps(ledger, indent=2) + '\n')
        raw_path = output / (case_id + '-raw.f32le')
        presentation_path = output / (case_id + '-presentation.f32le')
        # Raw decode suppresses both demux edit lists and codec automatic skip.
        run([ffmpeg, '-nostdin', '-v', 'error', '-ignore_editlist', '1', '-flags2', '+skip_manual',
             '-i', file, '-map', '0:a:0', '-c:a', 'pcm_f32le', '-f', 'f32le', '-y', raw_path], log)
        # Independently retain FFmpeg's ordinary single-owner presentation interpretation.
        run([ffmpeg, '-nostdin', '-v', 'error', '-i', file, '-map', '0:a:0',
             '-c:a', 'pcm_f32le', '-f', 'f32le', '-y', presentation_path], log)
        raw, presentation = raw_path.read_bytes(), presentation_path.read_bytes()
        raw_stats, presentation_stats = float_stats(raw, channels), float_stats(presentation, channels)
        frames = json.loads(run([ffprobe, '-v', 'error', '-ignore_editlist', '1',
                                 '-flags2', '+skip_manual', '-select_streams', 'a:0',
                                 '-show_frames', '-of', 'json', file], log))
        frame_ledger = frames.get('frames', [])
        require(frame_ledger and all(frame.get('sample_fmt') == 'fltp' and
                                    int(frame.get('channels', 0)) == channels and
                                    frame.get('channel_layout') == stream.get('channel_layout')
                                    for frame in frame_ledger), 'Reference decoder is not AAC FLTP')
        require(sum(int(frame['nb_samples']) for frame in frame_ledger) == raw_stats['frame_count'],
                'Raw reference count differs from decoded frame ledger')
        (output / (case_id + '-raw-frames.json')).write_text(json.dumps(frames, indent=2) + '\n')
        presentation_frames = json.loads(run([ffprobe, '-v', 'error', '-select_streams', 'a:0',
                                               '-show_frames', '-of', 'json', file], log))
        require(presentation_frames.get('frames') and
                all(frame.get('sample_fmt') == 'fltp' and int(frame.get('channels', 0)) == channels and
                    frame.get('channel_layout') == stream.get('channel_layout')
                    for frame in presentation_frames['frames']), 'Presentation reference format mismatch')
        require(sum(int(frame['nb_samples']) for frame in presentation_frames['frames']) ==
                presentation_stats['frame_count'], 'Presentation CLI/frame ledger mismatch')
        (output / (case_id + '-presentation-frames.json')).write_text(
            json.dumps(presentation_frames, indent=2) + '\n')
        start_frames = max(Fraction(0), -Fraction(ledger[0]['pts']) * time_base * rate)
        require(start_frames.denominator == 1, 'Nonintegral MP4 presentation start')
        start = int(start_frames)
        timeline_frames = Fraction(stream['duration_ts']) * time_base * rate
        require(timeline_frames.denominator == 1, 'Nonintegral MP4 presentation endpoint')
        expected_frames = int(timeline_frames)
        end = raw_stats['frame_count'] - start - expected_frames
        require(end >= 0, 'Negative crop; ambiguous timing')
        packet_endpoint = Fraction(ledger[-1]['pts'] + ledger[-1]['duration']) * time_base * rate
        require(packet_endpoint == expected_frames, 'Packet endpoint differs from stream duration')
        for side in ledger[0]['side_data_list']:
            if side.get('side_data_type') == 'Skip Samples' and int(side.get('skip_samples', 0)):
                require(int(side['skip_samples']) == start, 'Priming skip metadata disagrees with PTS')
        for side in ledger[-1]['side_data_list']:
            if side.get('side_data_type') == 'Skip Samples' and int(side.get('discard_padding', 0)):
                require(int(side['discard_padding']) == end, 'Discard padding disagrees with endpoint')
        cropped = raw[start * channels * 4:(raw_stats['frame_count'] - end) * channels * 4]
        require(presentation.startswith(cropped), 'Raw/presentation bitwise crop mismatch; stop for review')
        ordinary_stats = presentation_stats
        (output / (case_id + '-ordinary-presentation.f32le')).write_bytes(presentation)
        presentation_path.write_bytes(cropped)
        presentation_stats = float_stats(cropped, channels)
        crop_ledger, cursor = [], 0
        for index, frame in enumerate(frame_ledger):
            count = int(frame['nb_samples'])
            left = min(count, max(0, start - cursor))
            right = min(count - left, max(0, cursor + count - (raw_stats['frame_count'] - end)))
            crop_ledger.append({'frame_index': index, 'raw_start_frame': cursor,
                                'raw_frames': count, 'crop_start_frames': left,
                                'crop_end_frames': right, 'presentation_frames': count - left - right})
            cursor += count
        require(sum(frame['presentation_frames'] for frame in crop_ledger) == expected_frames,
                'Presentation frame crop ledger mismatch')
        (output / (case_id + '-presentation-crop-ledger.json')).write_text(json.dumps(crop_ledger, indent=2) + '\n')
        require(case_id != 'overshoot' or presentation_stats['overshoot_sample_count'] > 0,
                'Compressed AAC control lacks retained overshoot')
        duration_us = int(Fraction(stream['duration_ts']) * time_base * 1000000)
        if case_id == 'native_mp4':
            duration_us = int(json.loads((input_dir / 'audio-input.json').read_text())['expected_duration_us'])
        cases.append({'id': case_id, 'file': file.name, 'sha256': digest(file.read_bytes()),
                      'csd_hex': hex_dump(stream['extradata']).hex(),
                      'csd_sha256': digest(hex_dump(stream['extradata'])), 'profile': 'AAC-LC',
                      'rate': rate, 'channels': channels, 'au_count': len(ledger),
                      'au_rolling_sha256': rolling, 'au_ledger': case_id + '-au-ledger.json',
                      'packets': ledger,
                      'time_base': str(time_base), 'expected_duration_us': duration_us,
                      'duration_tolerance_us': 33334 + 1024000000 // rate, 'crop_start_samples': start,
                      'crop_end_samples': end, 'crop_owner': 'pinned_material_manifest',
                      'crop_ambiguous': False,
                      'crop_reasons': [{'kind': 'negative_first_packet_pts', 'frames': start},
                                       {'kind': 'mp4_stream_and_packet_endpoint', 'frames': end}],
                      'crop_evidence': {'raw_packet_first_pts': raw_ledger[0]['pts'],
                                        'edit_list_demux_first_pts': ledger[0]['pts'],
                                        'packet_skip_metadata': ledger[0]['side_data_list'],
                                        'stream_duration_frames': expected_frames,
                                        'last_packet_endpoint_frames': int(packet_endpoint),
                                        'independent_bitwise_crop_match': True,
                                        'raw_decode_ignore_editlist': True, 'raw_decode_skip_manual': True},
                      'reference_raw': dict(raw_stats, file=raw_path.name),
                      'reference_raw_sha256': raw_stats['sha256'],
                      'reference_raw_sample_count': raw_stats['sample_count'],
                      'reference_raw_frame_ledger': case_id + '-raw-frames.json',
                      'reference_ordinary_presentation_frame_ledger': case_id + '-presentation-frames.json',
                      'reference_presentation_crop_ledger': case_id + '-presentation-crop-ledger.json',
                      'reference_frame_rate_evidence': 'stream_rate_only_ffprobe_does_not_expose_audio_frame_rate',
                      'reference_ordinary_presentation': dict(ordinary_stats, file=case_id + '-ordinary-presentation.f32le'),
                      'reference_presentation': dict(presentation_stats, file=presentation_path.name),
                      'reference_presentation_sha256': presentation_stats['sha256'],
                      'reference_presentation_sample_count': presentation_stats['sample_count']})
    jni_sha = digest((build / 'android/x86_64/libsoftware_aac_probe.so').read_bytes())
    manifest = {'format': 'custom-music-software-aac-materials-v1',
                'cases': {case['id']: case for case in cases}, 'jni_sha256': jni_sha,
                'provenance': {'source_commit': SOURCE_COMMIT, 'ndk_version': '27.2.12479018',
                               'android_api_min': 26, 'abi': 'x86_64',
                               'host_ffmpeg_sha256': digest(ffmpeg.read_bytes()),
                               'host_ffprobe_sha256': digest(ffprobe.read_bytes()),
                               'android_library_sha256': jni_sha,
                               'source_archive_sha256': digest((build / 'source/ffmpeg-source.tar').read_bytes()),
                               'build_evidence': 'build/provenance',
                               'native_snapshot': 'original-native-snapshot.json'},
                'candidate_results_disclosed': False}
    (output / 'materials.json').write_text(json.dumps(manifest, indent=2, allow_nan=False) + '\n')
    (output / 'materials.sha256').write_text(digest((output / 'materials.json').read_bytes()) + '\n')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('build', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    prepare(args.input, args.build, args.output)
