import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from validate_software_aac_xml import validate
from verify_software_aac_materials import file_at, read, stage


class SoftwareAacIntegrityTest(unittest.TestCase):
    def test_complete_relative_manifest_preserves_bound_inputs(self):
        # Isolate the archive/run/payload binding stage from the separately reviewed
        # FFmpeg binary/calibration inspector. No library is loaded by this test.
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'probe.cpp'; source.write_bytes(b'reviewed diagnostic source')
            materials = json.dumps({'cases': {'native_mp4': {'file': 'native.mp4'},
                                             'overshoot': {'file': 'overshoot.mp4'}}}).encode()
            payloads = {'materials/materials.json': materials, 'input/audio-input.json': b'{"native":false}',
                        'materials/native.mp4': b'saved-mp4', 'materials/overshoot.mp4': b'compressed-control'}
            header = ('format=custom-music-software-materials-manifest-v1\nworkflow_run_id=12\n'
                      'run_attempt=1\ncheckout_sha=' + 'a' * 40 + '\noriginal_exit_code=0\n')
            manifest = header + ''.join(hashlib.sha256(value).hexdigest() +
                ' *build/reports/custom-music-software-materials/' + name + '\n'
                for name, value in payloads.items())
            archive = root / 'artifact.zip'
            with zipfile.ZipFile(archive, 'w') as output:
                for name, value in payloads.items(): output.writestr(name, value)
                output.writestr('diagnostic-manifest.txt', manifest)
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            identity = {'artifact_id': 11, 'run_id': 12, 'attempt': 1, 'head_sha': 'a' * 40,
                        'zip_sha256': digest, 'materials_sha256': hashlib.sha256(materials).hexdigest(),
                        'jni_sha256': 'b' * 64, 'jni_source_sha256': hashlib.sha256(source.read_bytes()).hexdigest()}
            artifact = {'id': 11, 'name': 'custom-music-software-materials', 'expired': False,
                        'digest': 'sha256:' + digest, 'size_in_bytes': archive.stat().st_size,
                        'workflow_run': {'id': 12, 'head_sha': 'a' * 40,
                                         'repository_id': 1400642290, 'head_repository_id': 1400642290}}
            run = {'id': 12, 'head_sha': 'a' * 40, 'run_attempt': 1, 'status': 'completed',
                   'conclusion': 'success', 'event': 'workflow_dispatch'}
            with patch('verify_software_aac_materials.inspect', return_value={'jni_sha256': 'b' * 64}):
                stage(archive, root / 'staged', identity, artifact, run, source)
            self.assertEqual(materials, (root / 'staged/android-input/material-input.json').read_bytes())
            self.assertEqual(b'compressed-control', (root / 'staged/android-input/overshoot.mp4').read_bytes())

    def test_nonfinite_json_and_escaping_file_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'root'; root.mkdir()
            path = root / 'bad.json'; path.write_text('{"peak":NaN}')
            with self.assertRaises(ValueError): read(path)
            other = Path(temporary) / 'other'; other.write_text('private')
            with self.assertRaises(ValueError): file_at(root, '../other')

    def _stage_bad_archive(self, entries, message):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / 'input.zip'
            with zipfile.ZipFile(archive, 'w') as output:
                for name, value in entries:
                    output.writestr(name, value)
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            identity = {'artifact_id': 11, 'run_id': 12, 'attempt': 1,
                        'head_sha': 'a' * 40, 'zip_sha256': digest}
            artifact = {'id': 11, 'name': 'custom-music-software-materials', 'expired': False,
                        'digest': 'sha256:' + digest, 'size_in_bytes': archive.stat().st_size,
                        'workflow_run': {'id': 12, 'head_sha': 'a' * 40,
                                         'repository_id': 1400642290, 'head_repository_id': 1400642290}}
            run = {'id': 12, 'head_sha': 'a' * 40, 'run_attempt': 1, 'status': 'completed',
                   'conclusion': 'success', 'event': 'workflow_dispatch'}
            with self.assertRaisesRegex(ValueError, message):
                stage(archive, root / 'staged', identity, artifact, run)

    def test_duplicate_archive_entry_and_path_escape_rejected(self):
        self._stage_bad_archive([('file', b'a'), ('file', b'b')], 'Duplicate')
        self._stage_bad_archive([('../outside', b'a')], 'Unsafe')

    def test_unbound_artifact_payload_rejected(self):
        manifest = ('format=custom-music-software-materials-manifest-v1\nworkflow_run_id=12\n'
                    'run_attempt=1\ncheckout_sha=' + 'a' * 40 + '\noriginal_exit_code=0\n')
        self._stage_bad_archive([('diagnostic-manifest.txt', manifest), ('extra', b'x')], 'Unbound')

    def test_exact_xml_inventory_and_hidden_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'test.xml'
            good = ('<testsuite tests="3" executed="3" failures="0" errors="0" skipped="0">'
                    '<testcase classname="com.veycad.app.CustomMusicSoftwareAudioProbeTest" name="native_mp4"/>'
                    '<testcase classname="com.veycad.app.CustomMusicSoftwareAudioProbeTest" name="overshoot"/>'
                    '<testcase classname="com.veycad.app.CustomMusicSoftwareAudioProbeTest" name="integer_cast"/>'
                    '</testsuite>')
            path.write_text(good); validate(path)
            for broken in (good.replace('overshoot', 'initializationError'),
                           good.replace('name="integer_cast"/>', 'name="integer_cast"><failure/></testcase>'),
                           good.replace('executed="3"', 'executed="2"')):
                path.write_text(broken)
                with self.assertRaises(ValueError): validate(path)


if __name__ == '__main__': unittest.main()
