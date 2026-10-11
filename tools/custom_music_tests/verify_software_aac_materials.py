"""Integrity/metadata checks only: never compiles, loads or decodes the candidate."""
import argparse
import hashlib
import json
import math
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import struct
import zipfile
from fractions import Fraction

SOURCE = 'f893221c8d89cb798b829bebe71d55e1a3f242fd'
NATIVE_SHA = '09bb26563846c418b5452d035beb2a83fb528e4bca68e5d46c37eed6d259f187'
FORMAT = 'custom-music-software-aac-reviewed-pins-v1'
PREFIX = 'build/reports/custom-music-software-materials/'
REPOSITORY_ID = 1400642290
MAX_FILE = 128 * 1024 * 1024
MAX_TOTAL = 384 * 1024 * 1024


def require(value, message):
    if not value:
        raise ValueError(message)


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for part in iter(lambda: stream.read(65536), b''):
            digest.update(part)
    return digest.hexdigest()


def read(path):
    require(0 < path.stat().st_size <= MAX_FILE, 'Invalid JSON size')
    return json.loads(path.read_text(encoding='utf-8'),
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))


def integer(value, minimum=0):
    require(type(value) is int and value >= minimum, 'Invalid integer identity')
    return value


def hash_value(value):
    require(isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value), 'Invalid SHA256')
    return value


def file_at(root, name):
    require(isinstance(name, str) and '\\' not in name, 'Invalid path')
    path = PurePosixPath(name)
    require(not path.is_absolute() and all(p not in ('..', '.') for p in path.parts), 'Unsafe path')
    target = root.joinpath(*path.parts)
    require(target.is_file() and target.resolve().is_relative_to(root.resolve()), 'Missing/escaping file')
    return target


def float_identity(path, channels):
    size = path.stat().st_size
    require(0 < size <= 16 * 1024 * 1024 and size % (channels * 4) == 0, 'Invalid reference PCM bounds')
    count, overs, peak = 0, 0, 0.0
    with path.open('rb') as source:
        for block in iter(lambda: source.read(65536), b''):
            for (value,) in struct.iter_unpack('<f', block):
                require(math.isfinite(value), 'Nonfinite calibration sample')
                count += 1
                peak = max(peak, abs(value))
                overs += abs(value) > 1
    return {'sample_count': count, 'frame_count': count // channels, 'byte_count': size,
            'sha256': sha(path), 'peak': peak, 'overshoot_sample_count': overs}


def inspect(root):
    material_root = root / 'materials'
    manifest = read(material_root / 'materials.json')
    require(manifest.get('format') == 'custom-music-software-aac-materials-v1' and
            manifest.get('candidate_results_disclosed') is False, 'Unexpected material contract/disclosure')
    provenance = manifest['provenance']
    require(provenance['source_commit'] == SOURCE and provenance['ndk_version'] == '27.2.12479018' and
            provenance['android_api_min'] == 26 and provenance['abi'] == 'x86_64', 'Build identity mismatch')
    library = root / 'build/android/x86_64/libsoftware_aac_probe.so'
    require(sha(library) == hash_value(manifest['jni_sha256']) == provenance['android_library_sha256'],
            'JNI binary identity mismatch')
    with library.open('rb') as stream:
        elf = stream.read(64)
    require(elf[:6] == b'\x7fELF\x02\x01' and struct.unpack_from('<HH', elf, 16) == (3, 62),
            'Expected little-endian x86_64 ELF shared library')
    build = root / 'build'
    for name, key in [('host/ffmpeg', 'host_ffmpeg_sha256'), ('host/ffprobe', 'host_ffprobe_sha256'),
                      ('source/ffmpeg-source.tar', 'source_archive_sha256')]:
        require(sha(file_at(build, name)) == hash_value(provenance[key]), 'Build payload hash mismatch')
    require((build / 'provenance/source-commit.txt').read_text().strip() == SOURCE, 'Source pin mismatch')
    compiled_jni_source = (build / 'provenance/jni-source.sha256').read_text().split()[0]
    require(hash_value(compiled_jni_source) == sha(Path('app/src/customMusicSoftwareDiagnostic/cpp/SoftwareAacProbe.cpp')),
            'Prepared JNI binary belongs to another native source')
    configure = (build / 'provenance/android-configure-argv.txt').read_text().splitlines()
    for flag in ('--disable-network', '--disable-gpl', '--disable-nonfree', '--disable-programs',
                 '--disable-avformat', '--disable-avfilter', '--disable-swresample', '--enable-decoder=aac'):
        require(flag in configure, 'Missing minimal Android build flag ' + flag)
    components = (build / 'provenance/config_components.h').read_text()
    require(re.search(r'^#define CONFIG_AAC_DECODER 1$', components, re.M) and
            re.search(r'^#define CONFIG_AAC_FIXED_DECODER 0$', components, re.M), 'Wrong AAC decoder build')
    original = read(root / 'input/audio-input.json')
    require(original['expected_duration_us'] == 16000000 and
            original['native_snapshot']['quality_gate'] is False, 'Original native failure missing')
    require((material_root / 'original-native-snapshot.json').read_bytes() ==
            (root / 'input/audio-input.json').read_bytes(), 'Original snapshot changed')
    require(set(manifest['cases']) == {'native_mp4', 'overshoot'}, 'Unexpected case inventory')
    summaries = {}
    for case_id, case in manifest['cases'].items():
        media = file_at(material_root, case['file'])
        require(sha(media) == hash_value(case['sha256']), 'Compressed input changed')
        if case_id == 'native_mp4':
            require(case['sha256'] == NATIVE_SHA and case['expected_duration_us'] == 16000000,
                    'Wrong source native MP4/graph duration')
        csd = bytes.fromhex(case['csd_hex'])
        require(0 < len(csd) <= 65536 and hashlib.sha256(csd).hexdigest() == case['csd_sha256'], 'CSD mismatch')
        rate, channels = integer(case['rate'], 1), integer(case['channels'], 1)
        require(channels <= 8 and case['profile'] == 'AAC-LC' and
                case['duration_tolerance_us'] == 33334 + 1024000000 // rate, 'Audio quality contract changed')
        packets = case['packets']
        require(len(packets) == integer(case['au_count'], 1) <= 65536 and
                read(file_at(material_root, case['au_ledger'])) == packets, 'AU ledger mismatch')
        rolling = hashlib.sha256()
        time_base = Fraction(case['time_base'])
        with media.open('rb') as source:
            for index, packet in enumerate(packets):
                length, position = integer(packet['size'], 1), integer(packet['pos'])
                require(length <= 1048576 and packet['index'] == index and
                        packet['pts_us'] == int(Fraction(packet['pts']) * time_base * 1000000), 'AU clock/order mismatch')
                source.seek(position)
                payload = source.read(length)
                require(len(payload) == length and hashlib.sha256(payload).hexdigest() == packet['sha256'],
                        'AU bytes mismatch')
                rolling.update(struct.pack('<I', length))
                rolling.update(payload)
        require(rolling.hexdigest() == case['au_rolling_sha256'], 'AU rolling identity mismatch')
        require(case['crop_owner'] == 'pinned_material_manifest' and case['crop_ambiguous'] is False,
                'Ambiguous crop cannot be pinned')
        raw = file_at(material_root, case['reference_raw']['file'])
        presentation = file_at(material_root, case['reference_presentation']['file'])
        for name, path in [('reference_raw', raw), ('reference_presentation', presentation)]:
            statistics = float_identity(path, channels)
            require(all(case[name][key] == value for key, value in statistics.items()),
                    'Retained float statistics mismatch: ' + name)
        require(raw.stat().st_size == case['reference_raw']['byte_count'] and
                sha(raw) == case['reference_raw_sha256'] == case['reference_raw']['sha256'] and
                raw.stat().st_size // 4 == case['reference_raw_sample_count'], 'Raw reference identity mismatch')
        require(sha(presentation) == case['reference_presentation_sha256'] and
                presentation.stat().st_size // 4 == case['reference_presentation_sample_count'],
                'Presentation identity mismatch')
        raw_frames = raw.stat().st_size // (channels * 4)
        start, end = integer(case['crop_start_samples']), integer(case['crop_end_samples'])
        require(raw.stat().st_size % (channels * 4) == 0 and start + end < raw_frames, 'Invalid crop bounds')
        endpoint = integer(case['crop_evidence']['stream_duration_frames'], 1)
        require(endpoint == case['crop_evidence']['last_packet_endpoint_frames'] == raw_frames - start - end,
                'Crop geometry disagrees with metadata endpoint')
        require(raw.read_bytes()[start * channels * 4:(raw_frames - end) * channels * 4] ==
                presentation.read_bytes(), 'Presentation is not declared raw bit crop')
        raw_ledger = read(file_at(material_root, case['reference_raw_frame_ledger']))['frames']
        stream = read(material_root / (case_id + '-ffprobe.json'))['streams'][0]
        require(int(stream['sample_rate']) == rate and int(stream['channels']) == channels, 'Reference stream format mismatch')
        require(raw_ledger and all(frame['sample_fmt'] == 'fltp' and int(frame['channels']) == channels
                                  and frame['channel_layout'] == stream['channel_layout']
                                  for frame in raw_ledger) and
                sum(int(frame['nb_samples']) for frame in raw_ledger) == raw_frames, 'Actual reference frames mismatch')
        mapping = read(file_at(material_root, case['reference_presentation_crop_ledger']))
        require(len(mapping) == len(raw_ledger) and sum(item['presentation_frames'] for item in mapping) == endpoint,
                'Presentation frame mapping incomplete')
        summaries[case_id] = {'media_sha256': case['sha256'], 'au_count': len(packets),
                              'au_sha256': rolling.hexdigest(), 'raw_frames': raw_frames,
                              'presentation_frames': endpoint, 'crop_start': start, 'crop_end': end}
        if case_id == 'overshoot':
            values = (item[0] for item in struct.iter_unpack('<f', presentation.read_bytes()))
            require(any(abs(value) > 1 for value in values), 'AAC overshoot absent in presentation bytes')
    return {'format': 'custom-music-software-materials-integrity-v1',
            'jni_sha256': sha(library), 'source_commit': SOURCE, 'cases': summaries}


def pins(path):
    data = read(path)
    require(data['format'] == FORMAT and data['source_commit'] == SOURCE, 'Unreviewed pin format/source')
    integer(data['artifact_id'], 1)
    integer(data['run_id'], 1)
    integer(data['attempt'], 1)
    require(re.fullmatch('[0-9a-f]{40}', data['head_sha']), 'Invalid preparation head')
    hash_value(data['zip_sha256'])
    hash_value(data['materials_sha256'])
    hash_value(data['jni_sha256'])
    hash_value(data['jni_source_sha256'])
    require(data['independent_review_complete'] is True, 'Material review is incomplete')
    return data


def stage(archive, destination, identities, artifact, run,
          current_source=Path('app/src/customMusicSoftwareDiagnostic/cpp/SoftwareAacProbe.cpp')):
    require(not destination.exists(), 'Refusing stale staged material directory')
    require(sha(archive) == identities['zip_sha256'] and artifact['id'] == identities['artifact_id'] and
            artifact['name'] == 'custom-music-software-materials' and artifact['expired'] is False and
            artifact['size_in_bytes'] == archive.stat().st_size and
            artifact['digest'] == 'sha256:' + identities['zip_sha256'], 'Artifact identity mismatch')
    workflow = artifact['workflow_run']
    require(workflow['id'] == identities['run_id'] and workflow['head_sha'] == identities['head_sha'] and
            workflow['repository_id'] == workflow['head_repository_id'] == REPOSITORY_ID, 'Artifact run mismatch')
    require(run['id'] == identities['run_id'] and run['head_sha'] == identities['head_sha'] and
            run['run_attempt'] == identities['attempt'] and run['status'] == 'completed' and
            run['conclusion'] == 'success' and run['event'] == 'workflow_dispatch', 'Preparation run incomplete')
    destination.mkdir(parents=True)
    with zipfile.ZipFile(archive) as source:
        files = [info for info in source.infolist() if not info.is_dir()]
        names = [info.filename for info in files]
        require(len(names) == len(set(names)) and sum(info.file_size for info in files) <= MAX_TOTAL,
                'Duplicate/oversized artifact')
        for info in files:
            name = PurePosixPath(info.filename)
            require(not name.is_absolute() and '..' not in name.parts and '\\' not in info.filename and
                    info.file_size <= MAX_FILE and not stat.S_ISLNK(info.external_attr >> 16), 'Unsafe artifact entry')
            target = destination.joinpath(*name.parts)
            require(target.resolve().is_relative_to(destination.resolve()), 'Escaping artifact path')
            target.parent.mkdir(parents=True, exist_ok=True)
            with source.open(info) as stream, target.open('xb') as output:
                shutil.copyfileobj(stream, output, 65536)
    lines = (destination / 'diagnostic-manifest.txt').read_text().splitlines()
    metadata, declared = {}, {}
    for line in lines:
        match = re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', line)
        if match:
            name = match[2]
            require(name.startswith(PREFIX), 'Unexpected manifest prefix')
            name = name[len(PREFIX):]
            require(name not in declared, 'Duplicate manifest payload')
            declared[name] = match[1]
        else:
            key, value = line.split('=', 1)
            require(key not in metadata, 'Duplicate manifest field')
            metadata[key] = value
    require(metadata == {'format': 'custom-music-software-materials-manifest-v1',
                         'workflow_run_id': str(identities['run_id']), 'run_attempt': str(identities['attempt']),
                         'checkout_sha': identities['head_sha'], 'original_exit_code': '0'}, 'Preparation manifest mismatch')
    actual = {path.relative_to(destination).as_posix() for path in destination.rglob('*') if path.is_file()}
    require(set(declared) == actual - {'diagnostic-manifest.txt'}, 'Unbound/missing artifact payload')
    for name, expected in declared.items():
        require(sha(file_at(destination, name)) == expected, 'Manifest payload changed: ' + name)
    require(sha(destination / 'materials/materials.json') == identities['materials_sha256'], 'Material pin changed')
    result = inspect(destination)
    require(result['jni_sha256'] == identities['jni_sha256'], 'Reviewed binary changed')
    require(sha(current_source) == identities['jni_source_sha256'], 'Current native source differs from reviewed binary')
    input_root = destination / 'android-input'
    input_root.mkdir()
    shutil.copyfile(destination / 'materials/materials.json', input_root / 'material-input.json')
    shutil.copyfile(destination / 'input/audio-input.json', input_root / 'audio-input.json')
    for case in read(destination / 'materials/materials.json')['cases'].values():
        shutil.copyfile(file_at(destination / 'materials', case['file']), input_root / case['file'])
    (destination / 'stage-integrity.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('inspect'); p.add_argument('root', type=Path)
    p = sub.add_parser('pins'); p.add_argument('pins', type=Path)
    p = sub.add_parser('stage')
    p.add_argument('archive', type=Path); p.add_argument('destination', type=Path)
    for name in ('pins', 'artifact', 'run'):
        p.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'pins':
        identities = pins(args.pins)
        print(identities['artifact_id']); print(identities['run_id'])
    elif args.command == 'inspect':
        print(json.dumps(inspect(args.root), indent=2))
    else:
        print(json.dumps(stage(args.archive, args.destination, pins(args.pins), read(args.artifact), read(args.run)), indent=2))
