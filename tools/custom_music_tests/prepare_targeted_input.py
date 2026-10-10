"""Pinned synthetic CI143 input for a read-only Android diagnostic, never a QA override."""
import argparse
from array import array
import copy
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import zipfile
import sys

PREFIX = "build/reports/ui-tests/custom-music-native/"
FILES = ("editor-source.mp4", "editor.wav", "native-editor.mp4", "native-editor-evidence.json",
         "native-editor-report.txt", "stream-clock.json", "stream-clock-copy.json", "selected-tail.mp4")


@dataclass(frozen=True)
class EvidenceSpec:
    run_id: int = 38054343649
    artifact_id: int = 11670925739
    head_sha: str = "143a478a9a7d19cb7594703020e2442377e46738"
    base_sha: str = "a0395e26b93463f91520181a2eeb09dfd05e78a3"
    checkout_sha: str = "1a2c18c09bd29568dc2a7a3c1a38fc1efa3cc0c3"
    zip_sha256: str = "3e7e0f2ccb315614ed386101971c3d7955d76c28fdfa6c76da3c83d03585f960"
    evidence_sha256: str = "6417be1095a23fdb9db403e89ccc486409ddbb134cf68f51141d852f0d66ab21"
    output_sha256: str = "c2c9eae62f9e1f0e5ecd253d17b0cacd140a65e282bf0995a03425e4b344ea9f"


PINNED = EvidenceSpec()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def freeze_visual_graph(evidence, destination, identity):
    """Lossless common visual projection; remove audioTrack only, store all planes as f32le."""
    graph = copy.deepcopy(evidence["graph"])
    graph["audioTrack"] = None
    canonical = sha(json.dumps(graph, sort_keys=True, separators=(",", ":"), allow_nan=False).encode())
    binary = destination / "frozen-planes.f32"
    with binary.open("wb") as sink:
        def freeze(value):
            if isinstance(value, dict):
                if all(k in value for k in ("width", "height", "values", "confidence")):
                    values = value["values"]
                    require(len(values) == value["width"] * value["height"], "Invalid plane dimensions")
                    numbers = array("f", values)
                    if sys.byteorder != "little":
                        numbers.byteswap()
                    payload = numbers.tobytes()
                    offset = sink.tell()
                    sink.write(payload)
                    value["values"] = {"format": "f32le", "offset_bytes": offset,
                                       "count": len(values), "sha256": sha(payload)}
                else:
                    for child in value.values():
                        freeze(child)
            elif isinstance(value, list):
                for child in value:
                    freeze(child)
        freeze(graph)
    wrapper = {"schema_version": 1, "format": "custom-music-frozen-visual-graph-v1",
        "origin": {"head_sha": identity.head_sha, "ci_run_id": identity.run_id,
                   "artifact_id": identity.artifact_id, "snapshot_sha256": identity.evidence_sha256,
                   "visual_graph_sha256": canonical, "native_quality_gate": evidence["quality_gate"],
                   "native_issues": evidence["acceptance"]["issues"]},
        "files": {"source": evidence["files"]["source"], "planes": {
            "name": binary.name, "bytes": binary.stat().st_size, "sha256": sha(binary.read_bytes())}},
        "graph": graph}
    (destination / "frozen-graph.json").write_text(json.dumps(wrapper, indent=2, allow_nan=False) + "\n",
                                                encoding="utf-8")


def prepare(archive: Path, destination: Path, run: dict, commit: dict,
            identity: EvidenceSpec = PINNED) -> dict:
    require(run.get("id") == identity.run_id and run.get("run_attempt") == 1 and
            run.get("head_sha") == identity.head_sha and run.get("status") == "completed" and
            run.get("conclusion") == "success" and run.get("event") == "pull_request",
            "Unexpected source run/head/status")
    require(any(pr.get("head", {}).get("sha") == identity.head_sha and
                pr.get("base", {}).get("sha") == identity.base_sha
                for pr in run.get("pull_requests", [])), "Unexpected source PR head/base")
    require(commit.get("sha") == identity.checkout_sha and
            [p.get("sha") for p in commit.get("parents", [])] ==
            [identity.base_sha, identity.head_sha], "Unexpected checkout merge parents")
    require(archive.stat().st_size <= 32 * 1024 * 1024, "Oversized pinned ZIP")
    require(sha(archive.read_bytes()) == identity.zip_sha256, "ZIP SHA mismatch")
    with zipfile.ZipFile(archive) as z:
        data = {}
        for name in (*FILES, "ci-manifest.txt"):
            exact = PREFIX + name
            require(z.namelist().count(exact) == 1, f"Missing/duplicate retained file: {name}")
            require(z.getinfo(exact).file_size <= 64 * 1024 * 1024, f"Oversized file: {name}")
            data[name] = z.read(exact)
    lines = data["ci-manifest.txt"].decode("utf-8").splitlines()
    metadata = {s.split("=", 1)[0]: s.split("=", 1)[1] for s in lines if "=" in s}
    require(metadata == {"format": "custom-music-ci-manifest-v1",
                         "workflow_run_id": str(identity.run_id), "run_attempt": "1",
                         "checkout_sha": identity.checkout_sha, "github_sha": identity.checkout_sha,
                         "ui_exit_code": "0", "collection_exit_code": "0"}, "Invalid source manifest")
    hashes = [s.split(" *", 1) for s in lines if " *" in s]
    require(len(hashes) == len(FILES) and {name for _, name in hashes} == set(FILES),
            "Manifest must contain exactly eight files")
    for digest, name in hashes:
        require(sha(data[name]) == digest, f"manifest SHA mismatch: {name}")
    require(sha(data["native-editor-evidence.json"]) == identity.evidence_sha256, "Snapshot SHA mismatch")
    require(sha(data["native-editor.mp4"]) == identity.output_sha256, "Output MP4 SHA mismatch")
    evidence = json.loads(data["native-editor-evidence.json"])
    for role, expected_name in [("source", "editor-source.mp4"), ("music", "editor.wav"),
                                ("output", "native-editor.mp4")]:
        file = evidence["files"][role]
        require(file == {"name": expected_name, "bytes": len(data[expected_name]),
                         "sha256": sha(data[expected_name])}, f"Snapshot identity mismatch: {role}")
    frames = evidence["execution"]["frames"]
    require(evidence["quality_gate"] is False and evidence["acceptance"]["accepted"] is False,
            "Original negative QA must be preserved")
    require(evidence["frames"] == len(frames) == 480 and
            evidence["decoded_source_clock_complete"] is True and
            evidence["decoded_source_clock_entries"] == 480, "Incomplete source evidence")
    require(all(isinstance(f["output_us"], int) and f["output_us"] >= 0 for f in frames) and
            all(a["output_us"] < b["output_us"] for a, b in zip(frames, frames[1:])),
            "Execution PTS must be strictly increasing")
    indices = [i for i, f in enumerate(frames) if f["blackout"] > 0]
    require(len(indices) == 8 and all(0 < i < len(frames) - 1 for i in indices),
            "Expected eight BLACKOUT targets with neighbours")
    targets, selected = [], {}
    for i in indices:
        f = frames[i]
        targets.append({"output_us": f["output_us"], "previous_us": frames[i-1]["output_us"],
                        "next_us": frames[i+1]["output_us"], "clip": f["clip"], "blackout": f["blackout"]})
        for index, role in [(i-1, "previous"), (i, "target"), (i+1, "next")]:
            point = frames[index]
            request = selected.setdefault(point["output_us"], {"output_us": point["output_us"],
                "roles": [], "execution": {k: point[k] for k in
                                             ("clip", "source_us", "decoded_source_us", "blackout")}})
            request["roles"].append({"target_us": f["output_us"], "role": role})
    require(len(selected) == 16, "Expected sixteen distinct target/neighbor PTS")
    result = {"format": "custom-music-targeted-input-v1", "source_provenance": {
        "repository": "Veycad/Veycad", "head_sha": identity.head_sha,
        "checkout_sha": identity.checkout_sha, "base_sha": identity.base_sha,
        "run_id": identity.run_id, "run_attempt": 1, "artifact_id": identity.artifact_id,
        "zip_sha256": identity.zip_sha256, "evidence_sha256": identity.evidence_sha256,
        "manifest_sha256": sha(data["ci-manifest.txt"])}, "output": evidence["files"]["output"],
        "native_snapshot": {"quality_gate": evidence["quality_gate"], "frames": evidence["frames"],
                            "acceptance": evidence["acceptance"]},
        "graph_sha256": sha(json.dumps(evidence["graph"], sort_keys=True, separators=(",", ":")).encode()),
        "native_visual_samples": evidence["visual_samples"], "targets": targets,
        "requests": [selected[t] for t in sorted(selected)]}
    require(not destination.exists(), "Refusing to reuse diagnostic staging directory")
    destination.mkdir(parents=True)
    (destination / "native-editor.mp4").write_bytes(data["native-editor.mp4"])
    (destination / "targeted-input.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    (destination / "editor-source.mp4").write_bytes(data["editor-source.mp4"])
    freeze_visual_graph(evidence, destination, identity)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--run-metadata", type=Path, required=True)
    parser.add_argument("--commit-metadata", type=Path, required=True)
    args = parser.parse_args()
    result = prepare(args.archive, args.destination,
                     json.loads(args.run_metadata.read_text(encoding="utf-8")),
                     json.loads(args.commit_metadata.read_text(encoding="utf-8")))
    print(json.dumps({"targets": len(result["targets"]), "requests": len(result["requests"]),
                      "native_quality_gate": result["native_snapshot"]["quality_gate"],
                      "source": result["source_provenance"], "output": result["output"]}))
