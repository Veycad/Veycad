import copy
import importlib.util
import json
from pathlib import Path
import unittest

import test_targeted_input as fixtures

MODULE = Path(__file__).with_name("prepare_audio_probe_input.py")
api = None
if MODULE.exists():
    spec = importlib.util.spec_from_file_location("audio_probe_input", MODULE)
    api = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(api)


class AudioProbeInputTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(api, "Audio-only input verifier is not implemented")
        self.fixture = fixtures.TargetedInputTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root

    def inputs(self, mutate=None):
        archive, identity, run, commit = self.fixture.fixture(mutate)
        artifact = {
            "id": identity.artifact_id, "name": "android-ui-reports", "expired": False,
            "size_in_bytes": archive.stat().st_size, "digest": "sha256:" + identity.zip_sha256,
            "workflow_run": {"id": identity.run_id, "head_sha": identity.head_sha,
                             "head_branch": "codex/custom-music",
                             "repository_id": 1400642290, "head_repository_id": 1400642290},
        }
        return archive, identity, run, commit, artifact

    def test_only_audio_inputs_and_unchanged_original_acceptance(self):
        archive, identity, run, commit, artifact = self.inputs()
        result = api.prepare_audio(archive, self.root / "out", run, commit, artifact, identity)
        self.assertEqual({"audio-input.json", "native-editor.mp4"},
                         {p.name for p in (self.root / "out").iterdir()})
        self.assertEqual("custom-music-native-audio-probe-input-v1", result["format"])
        self.assertEqual(16_000_000, result["expected_duration_us"])
        self.assertEqual(identity.head_sha, result["source_provenance"]["head_sha"])
        self.assertFalse(result["native_snapshot"]["quality_gate"])
        self.assertEqual({"accepted": False, "issues": ["weak-rendered-transitions"],
                          "metrics": {"transitionPeak": .015068494},
                          "reference": {"minimumTransitionPeak": .16}},
                         result["native_snapshot"]["acceptance"])
        self.assertEqual(b"native-editor.mp4", (self.root / "out/native-editor.mp4").read_bytes())
        self.assertEqual(result, json.loads((self.root / "out/audio-input.json").read_text()))

    def test_wrong_artifact_identity_digest_size_and_head_fail_before_output(self):
        archive, identity, run, commit, artifact = self.inputs()
        changes = [lambda a: a.update(id=a["id"] + 1),
                   lambda a: a.update(expired=True),
                   lambda a: a.update(name="another-report"),
                   lambda a: a.update(digest="sha256:" + "0" * 64),
                   lambda a: a.update(size_in_bytes=a["size_in_bytes"] + 1),
                   lambda a: a["workflow_run"].update(id=identity.run_id + 1),
                   lambda a: a["workflow_run"].update(head_sha="0" * 40),
                   lambda a: a["workflow_run"].update(head_repository_id=1)]
        for change in changes:
            with self.subTest(change=changes.index(change)):
                bad = copy.deepcopy(artifact)
                change(bad)
                with self.assertRaises(ValueError):
                    api.prepare_audio(archive, self.root / "out", run, commit, bad, identity)
                self.assertFalse((self.root / "out").exists())

    def test_wrong_merge_parent_run_or_archive_fail_without_destination(self):
        archive, identity, run, commit, artifact = self.inputs()
        commit["parents"][0]["sha"] = "0" * 40
        with self.assertRaisesRegex(ValueError, "merge parents"):
            api.prepare_audio(archive, self.root / "out", run, commit, artifact, identity)
        commit["parents"][0]["sha"] = identity.base_sha
        run["head_sha"] = "0" * 40
        with self.assertRaisesRegex(ValueError, "source run"):
            api.prepare_audio(archive, self.root / "out", run, commit, artifact, identity)
        run["head_sha"] = identity.head_sha
        archive.write_bytes(archive.read_bytes()[:-1] + b"x")
        with self.assertRaisesRegex(ValueError, "ZIP SHA"):
            api.prepare_audio(archive, self.root / "out", run, commit, artifact, identity)
        self.assertFalse((self.root / "out").exists())

    def test_positive_historical_gate_is_rejected(self):
        archive, identity, run, commit, artifact = self.inputs(lambda e: e.update(quality_gate=True))
        with self.assertRaisesRegex(ValueError, "negative QA"):
            api.prepare_audio(archive, self.root / "out", run, commit, artifact, identity)
        self.assertFalse((self.root / "out").exists())

    def test_refuse_to_overwrite_existing_destination(self):
        archive, identity, run, commit, artifact = self.inputs()
        (self.root / "out").mkdir()
        sentinel = self.root / "out/sentinel"
        sentinel.write_text("keep")
        with self.assertRaisesRegex(ValueError, "reuse"):
            api.prepare_audio(archive, self.root / "out", run, commit, artifact, identity)
        self.assertEqual("keep", sentinel.read_text())


if __name__ == "__main__":
    unittest.main()
