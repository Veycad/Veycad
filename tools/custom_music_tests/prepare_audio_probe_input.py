"""Verified fresh CI20 MP4 for an audio-only Android probe, without a video render."""
import argparse
import json
from pathlib import Path
import shutil
import tempfile

from prepare_targeted_input import EvidenceSpec, prepare, require

PINNED_AUDIO = EvidenceSpec(
    run_id=38059647484,
    artifact_id=11672718841,
    head_sha="20d64c5a5aaea2e2f0b4966fc6f01f0ed09fe0e5",
    base_sha="a0395e26b93463f91520181a2eeb09dfd05e78a3",
    checkout_sha="33b68e3f0643ce95acb46bdf8f3e075448388492",
    zip_sha256="196e22b1f23f68b69b40e474cd8c82b768f29ce828fcdf37d25f143431488e52",
    evidence_sha256="02f556e7b7f188ca5f45b6f3400dde846bc4382e0e4f5e885de8b92e003c1054",
    output_sha256="09bb26563846c418b5452d035beb2a83fb528e4bca68e5d46c37eed6d259f187",
)


def prepare_audio(archive: Path, destination: Path, run: dict, commit: dict, artifact: dict,
                  identity: EvidenceSpec = PINNED_AUDIO) -> dict:
    require(not destination.exists(), "Refusing to reuse diagnostic staging directory")
    require(artifact.get("id") == identity.artifact_id and
            artifact.get("name") == "android-ui-reports" and artifact.get("expired") is False and
            artifact.get("digest") == "sha256:" + identity.zip_sha256 and
            artifact.get("size_in_bytes") == archive.stat().st_size,
            "Unexpected source artifact identity/digest/size")
    workflow = artifact.get("workflow_run", {})
    require(workflow.get("id") == identity.run_id and workflow.get("head_sha") == identity.head_sha and
            workflow.get("head_branch") == "codex/custom-music" and
            workflow.get("repository_id") == workflow.get("head_repository_id") == 1400642290,
            "Unexpected source artifact run/head/repository")
    # Reuse the reviewed archive/run/merge/manifest verifier. Its temporary visual
    # projection stays on the CI host and is discarded; only two audio inputs leave it.
    with tempfile.TemporaryDirectory(prefix="custom-music-audio-verify-") as temporary:
        retained = Path(temporary) / "verified"
        verified = prepare(archive, retained, run, commit, identity)
        result = {"format": "custom-music-native-audio-probe-input-v1",
                  "expected_duration_us": 16_000_000,
                  "source_provenance": verified["source_provenance"],
                  "output_identity": verified["output"],
                  "native_snapshot": verified["native_snapshot"]}
        destination.mkdir(parents=True)
        shutil.copyfile(retained / "native-editor.mp4", destination / "native-editor.mp4")
        (destination / "audio-input.json").write_text(
            json.dumps(result, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--run-metadata", type=Path, required=True)
    parser.add_argument("--commit-metadata", type=Path, required=True)
    parser.add_argument("--artifact-metadata", type=Path, required=True)
    args = parser.parse_args()
    result = prepare_audio(args.archive, args.destination,
                           json.loads(args.run_metadata.read_text(encoding="utf-8")),
                           json.loads(args.commit_metadata.read_text(encoding="utf-8")),
                           json.loads(args.artifact_metadata.read_text(encoding="utf-8")))
    print(json.dumps({"source": result["source_provenance"], "output": result["output_identity"],
                      "original_native_quality_gate": result["native_snapshot"]["quality_gate"]}))
