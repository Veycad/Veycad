"""Compile the common QA selector against byte-exact music PR18 sources, offline.

Run with JAVA_HOME=JDK25 and the existing Gradle dependency cache. No Gradle,
Android, downloads, substituted model definitions, or copied selector algorithm.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path("app/src/main/java/com/example/autoedit")
MUSIC = "20d64c5a5aaea2e2f0b4966fc6f01f0ed09fe0e5"
BASE = "a32a6a849abb3c781c21790c409b484f0fb65488"
FILES = [name + ".kt" for name in (
    "MontageGraph", "HighQualityFramePlan", "TransitionTimeline", "GpuTransitionModel",
    "GpuEffectGraph", "LayerCompositorModel", "FrameAttachments", "ParameterTrack",
    "NleProjectModel", "ReferenceMontageProfile", "HeartbeatMontageProfile",
    "EditDurationPolicy", "AudioBeatMap", "ForegroundReentryMotion",
)]
PINNED = {
    "MontageGraph.kt": "b1cc349b2555f4058c2f0f994ff3ae279f814c70",
    "HighQualityFramePlan.kt": "2320bb273eb2d94fd62df85171f4216618a23972",
    "TransitionTimeline.kt": "b36fc7fba702b4e37beb93566c8ab8f6f0c517e1",
}


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("red", "green"), required=True)
    parser.add_argument("--evidence", type=Path, default=ROOT / ".superpowers/sdd/2026-10-10-manual-montage/task-4d-evidence")
    args = parser.parse_args()
    out = args.evidence.resolve() / args.mode
    out.mkdir(parents=True, exist_ok=True)
    # Evidence must remain ignored; immutable source snapshots never enter test sources.
    subprocess.run(["git", "check-ignore", "-q", str(out)], cwd=ROOT, check=True)
    manifest = {"music_commit": MUSIC, "baseline_commit": BASE, "mode": args.mode, "sources": []}
    sources = []
    for name in FILES:
        ref = f"{MUSIC}:{(SOURCE / name).as_posix()}"
        data = git("show", ref)
        blob = git("rev-parse", ref).decode().strip()
        if name in PINNED:
            assert blob == PINNED[name], (name, blob)
        target = out / name
        target.write_bytes(data)
        assert git("hash-object", str(target)).decode().strip() == blob
        sources.append(target)
        manifest["sources"].append({"path": (SOURCE / name).as_posix(), "blob": blob, "sha256": hashlib.sha256(data).hexdigest()})
    selector = ROOT / SOURCE / "RenderQaSampling.kt"
    if args.mode == "red":
        selector = out / "RenderQaSampling.kt"
        selector.write_bytes(git("show", f"{BASE}:{SOURCE.as_posix()}/RenderQaSampling.kt"))
    sources.append(selector)
    if args.mode == "green":
        sources += [ROOT / "tools/fixtures/render-qa-legacy/RenderQaGraphEvents.kt",
                    ROOT / "tools/fixtures/render-qa-legacy/LegacyRenderQaCheck.kt"]
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
    def jar(group, artifact, version):
        matches = list((cache / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
        if len(matches) != 1:
            raise RuntimeError(f"Expected one cached {group}:{artifact}:{version}, found {matches}")
        return matches[0]
    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.10")
    dependencies = [jar("org.jetbrains.kotlin", a, "2.2.10") for a in (
        "kotlin-compiler-embeddable", "kotlin-script-runtime", "kotlin-daemon-embeddable")]
    dependencies += [stdlib, jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
                     jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
                     jar("org.jetbrains", "annotations", "13.0")]
    java = Path(os.environ["JAVA_HOME"]) / "bin/java.exe"
    version = subprocess.check_output([str(java), "-version"], stderr=subprocess.STDOUT).decode()
    if 'version "25' not in version:
        raise RuntimeError(f"JDK25 required: {version}")
    manifest["java"] = version
    manifest["inputs"] = [{"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in sources]
    manifest["compiler_dependencies"] = [{"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in dependencies]
    command = [str(java), "-Xmx512m", "-cp", os.pathsep.join(map(str, dependencies)),
               "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
               "-classpath", str(stdlib), "-jvm-target", "17", "-d", str(out / "check.jar"), *map(str, sources)]
    manifest["compile_command"] = command
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=180)
    log = result.stdout + result.stderr
    (out / "compile.log").write_bytes(log)
    print(log.decode(errors="replace"))
    if args.mode == "red":
        assert result.returncode != 0, "Old selector unexpectedly compiles"
        assert b"EffectSampleWindow" in log and b"renderOverlays" in log and b"renderNodes" in log
        # An unrelated dependency failure is not meaningful RED evidence.
        errors = [line for line in log.decode(errors="replace").splitlines() if "error:" in line]
        assert errors and all("RenderQaSampling.kt:" in line for line in errors), errors
        print("PASS RED: old selector fails only on its manual-only API dependencies")
    else:
        assert result.returncode == 0, f"Compile failed ({result.returncode})"
        run = [str(java), "-Xmx256m", "-cp", os.pathsep.join((str(out / "check.jar"), str(stdlib))),
               "com.veycad.app.LegacyRenderQaCheckKt"]
        manifest["run_command"] = run
        result = subprocess.run(run, cwd=ROOT, capture_output=True, timeout=60)
        (out / "run.log").write_bytes(result.stdout + result.stderr)
        print((result.stdout + result.stderr).decode(errors="replace"))
        assert result.returncode == 0, f"Runtime checks failed ({result.returncode})"
    manifest["status"] = "PASS"
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
