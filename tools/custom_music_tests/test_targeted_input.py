import dataclasses
import struct
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

MODULE = Path(__file__).with_name("prepare_targeted_input.py")
if MODULE.exists():
    spec = importlib.util.spec_from_file_location("targeted_input", MODULE)
    import sys
    api = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = api
    spec.loader.exec_module(api)
else:
    api = None


class TargetedInputTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(api, "Targeted retained-input verifier is not implemented")
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def fixture(self, mutate=None):
        payloads = {name: name.encode() for name in api.FILES}
        frames = [{"output_us": round(i * 1_000_000 / 30), "source_us": i * 33_333,
                   "decoded_source_us": i * 33_333, "clip": 0, "blackout": 0.0}
                  for i in range(480)]
        for clip, start in zip([1, 5, 9, 12], [46, 166, 286, 406]):
            for i, strength in [(start, .86349), (start + 1, .50158)]:
                frames[i].update(clip=clip, blackout=strength)
        evidence = {"quality_gate": False, "frames": 480,
                    "decoded_source_clock_complete": True,
                    "decoded_source_clock_entries": 480,
                    "execution": {"frames": frames}, "graph": {"clips": [1]},
                    "acceptance": {"accepted": False, "issues": ["weak-rendered-transitions"],
                                   "metrics": {"transitionPeak": .015068494},
                                   "reference": {"minimumTransitionPeak": .16}},
                    "visual_samples": [{"outputTimeUs": i * 100_000} for i in range(160)],
                    "files": {role: {"name": name, "bytes": len(payloads[name]),
                                      "sha256": hashlib.sha256(payloads[name]).hexdigest()}
                              for role, name in [("source", "editor-source.mp4"),
                                                 ("music", "editor.wav"),
                                                 ("output", "native-editor.mp4")]}}
        if mutate:
            mutate(evidence)
        payloads["native-editor-evidence.json"] = json.dumps(evidence).encode()
        lines = ["format=custom-music-ci-manifest-v1", f"workflow_run_id={api.PINNED.run_id}",
                 "run_attempt=1", f"checkout_sha={api.PINNED.checkout_sha}",
                 f"github_sha={api.PINNED.checkout_sha}", "ui_exit_code=0"]
        lines += [hashlib.sha256(payloads[n]).hexdigest() + " *" + n for n in api.FILES]
        lines += ["collection_exit_code=0"]
        payloads["ci-manifest.txt"] = ("\n".join(lines) + "\n").encode()
        archive = self.root / "input.zip"
        with zipfile.ZipFile(archive, "w") as z:
            for name, data in payloads.items():
                z.writestr(api.PREFIX + name, data)
        identity = dataclasses.replace(api.PINNED,
            zip_sha256=hashlib.sha256(archive.read_bytes()).hexdigest(),
            evidence_sha256=hashlib.sha256(payloads["native-editor-evidence.json"]).hexdigest(),
            output_sha256=hashlib.sha256(payloads["native-editor.mp4"]).hexdigest())
        run = {"id": identity.run_id, "run_attempt": 1, "head_sha": identity.head_sha,
               "status": "completed", "conclusion": "success", "event": "pull_request",
               "pull_requests": [{"head": {"sha": identity.head_sha},
                                  "base": {"sha": identity.base_sha}}]}
        commit = {"sha": identity.checkout_sha,
                  "parents": [{"sha": identity.base_sha}, {"sha": identity.head_sha}]}
        return archive, identity, run, commit

    def test_exact_targets_neighbors_and_original_false_gate(self):
        archive, identity, run, commit = self.fixture()
        result = api.prepare(archive, self.root / "out", run, commit, identity)
        self.assertEqual(8, len(result["targets"]))
        self.assertEqual(16, len(result["requests"]))
        self.assertEqual([1533333, 1566667, 5533333, 5566667, 9533333, 9566667,
                          13533333, 13566667], [t["output_us"] for t in result["targets"]])
        self.assertEqual(1500000, result["targets"][0]["previous_us"])
        self.assertEqual(1600000, result["targets"][1]["next_us"])
        self.assertFalse(result["native_snapshot"]["quality_gate"])
        self.assertEqual(.16, result["native_snapshot"]["acceptance"]["reference"]["minimumTransitionPeak"])
        self.assertEqual(160, len(result["native_visual_samples"]))
        self.assertEqual(b"native-editor.mp4", (self.root / "out/native-editor.mp4").read_bytes())
        self.assertTrue((self.root / "out/targeted-input.json").is_file())

    def test_corrupt_archive_fails_before_output(self):
        archive, identity, run, commit = self.fixture()
        archive.write_bytes(archive.read_bytes() + b"changed")
        with self.assertRaisesRegex(ValueError, "ZIP SHA"):
            api.prepare(archive, self.root / "out", run, commit, identity)
        self.assertFalse((self.root / "out").exists())

    def test_manifest_hash_mismatch_fails(self):
        archive, identity, run, commit = self.fixture()
        with zipfile.ZipFile(archive) as z:
            payloads = {i.filename: z.read(i) for i in z.infolist()}
        payloads[api.PREFIX + "editor.wav"] += b"tamper"
        with zipfile.ZipFile(archive, "w") as z:
            for n, data in payloads.items():
                z.writestr(n, data)
        identity = dataclasses.replace(identity, zip_sha256=hashlib.sha256(archive.read_bytes()).hexdigest())
        with self.assertRaisesRegex(ValueError, "manifest SHA"):
            api.prepare(archive, self.root / "out", run, commit, identity)

    def test_missing_target_is_not_filled_by_neighbor(self):
        archive, identity, run, commit = self.fixture(lambda e: e["execution"]["frames"][46].update(blackout=0))
        with self.assertRaisesRegex(ValueError, "eight BLACKOUT"):
            api.prepare(archive, self.root / "out", run, commit, identity)

    def test_duplicate_pts_fails(self):
        archive, identity, run, commit = self.fixture(lambda e: e["execution"]["frames"][47].update(output_us=1533333))
        with self.assertRaisesRegex(ValueError, "strictly increasing"):
            api.prepare(archive, self.root / "out", run, commit, identity)

    def test_wrong_merge_parent_and_source_run_fail(self):
        archive, identity, run, commit = self.fixture()
        commit["parents"][1]["sha"] = "0" * 40
        with self.assertRaisesRegex(ValueError, "merge parents"):
            api.prepare(archive, self.root / "out", run, commit, identity)
        commit["parents"][1]["sha"] = identity.head_sha
        run["id"] += 1
        with self.assertRaisesRegex(ValueError, "source run"):
            api.prepare(archive, self.root / "out", run, commit, identity)

    def test_visual_projection_preserves_plane_bits_and_only_removes_audio(self):
        def add_plane(e):
            e["graph"] = {"audioTrack": {"sourceStartUs": 15000000},
                          "frameAttachments": {"frames": [{"sourceTimeUs": 123,
                              "mask": {"width": 2, "height": 2, "confidence": .48,
                                       "values": [.25, -.0, .75, 1.]}}]},
                          "metadata": {"generator": "veycad-event-director-dynamic"}}
        archive, identity, run, commit = self.fixture(add_plane)
        api.prepare(archive, self.root / "out", run, commit, identity)
        binary = (self.root / "out/frozen-planes.f32").read_bytes()
        self.assertEqual(struct.pack("<4f", .25, -.0, .75, 1.), binary)
        wrapper = json.loads((self.root / "out/frozen-graph.json").read_text())
        self.assertIsNone(wrapper["graph"]["audioTrack"])
        self.assertEqual("veycad-event-director-dynamic", wrapper["graph"]["metadata"]["generator"])
        plane = wrapper["graph"]["frameAttachments"]["frames"][0]["mask"]
        self.assertEqual(.48, plane["confidence"])
        self.assertEqual({"format": "f32le", "offset_bytes": 0, "count": 4,
                          "sha256": hashlib.sha256(binary).hexdigest()}, plane["values"])
        self.assertFalse(wrapper["origin"]["native_quality_gate"])


if __name__ == "__main__":
    unittest.main()
