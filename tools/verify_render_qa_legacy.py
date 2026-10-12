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
import sys

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


class Evidence:
    """Persist provenance before work and captured diagnostics even when that work fails."""
    def __init__(self, out, mode):
        self.out = out
        self.manifest = {"music_commit": MUSIC, "baseline_commit": BASE, "mode": mode,
                         "sources": [], "results": {}, "phase": "sources", "status": "RUNNING"}

    def save(self):
        (self.out / "manifest.json").write_text(json.dumps(self.manifest, indent=2), encoding="utf-8")

    def __enter__(self):
        self.save()
        return self

    def __exit__(self, kind, error, traceback):
        self.manifest["status"] = "FAIL" if error is not None else "PASS"
        if error is not None:
            self.manifest["error"] = {"type": kind.__name__, "message": str(error)}
        self.save()

    def phase(self, name):
        self.manifest["phase"] = name
        self.save()

    def source(self, metadata):
        self.manifest["sources"].append(metadata)
        self.save()

    def run(self, phase, command, timeout):
        self.manifest[f"{phase}_command"] = command
        self.phase(phase)
        stdout = stderr = b""
        try:
            result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=timeout)
            stdout, stderr = result.stdout, result.stderr
            self.manifest["results"][phase] = {"status": "EXITED", "returncode": result.returncode}
            return result
        except subprocess.TimeoutExpired as error:
            stdout, stderr = error.stdout or b"", error.stderr or b""
            self.manifest["results"][phase] = {"status": "TIMEOUT", "timeout_seconds": timeout}
            raise
        except OSError as error:
            self.manifest["results"][phase] = {"status": "LAUNCH_FAILED", "message": str(error)}
            raise
        finally:
            (self.out / f"{phase}.log").write_bytes(stdout + stderr)
            self.save()
            sys.stdout.buffer.write(stdout + stderr)
            sys.stdout.buffer.flush()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("red", "green"), required=True)
    parser.add_argument("--evidence", type=Path, default=ROOT / ".superpowers/sdd/2026-10-10-manual-montage/task-4d-evidence")
    args = parser.parse_args()
    out = args.evidence.resolve() / args.mode
    out.mkdir(parents=True, exist_ok=True)
    with Evidence(out, args.mode) as evidence:
        verify(args.mode, out, evidence)


def verify(mode, out, evidence):
    manifest = evidence.manifest
    # Evidence must remain ignored; immutable source snapshots never enter test sources.
    subprocess.run(["git", "check-ignore", "-q", str(out)], cwd=ROOT, check=True)
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
        evidence.source({"path": (SOURCE / name).as_posix(), "blob": blob, "sha256": hashlib.sha256(data).hexdigest()})
    selector = ROOT / SOURCE / "RenderQaSampling.kt"
    if mode == "red":
        selector = out / "RenderQaSampling.kt"
        selector.write_bytes(git("show", f"{BASE}:{SOURCE.as_posix()}/RenderQaSampling.kt"))
    sources.append(selector)
    if mode == "green":
        sources += [ROOT / "tools/fixtures/render-qa-legacy/RenderQaGraphEvents.kt",
                    ROOT / "tools/fixtures/render-qa-legacy/LegacyRenderQaCheck.kt"]
    manifest["inputs"] = [{"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in sources]
    evidence.phase("dependencies")
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
    manifest["compiler_dependencies"] = [{"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in dependencies]
    evidence.phase("java")
    java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
    java_result = evidence.run("java", [str(java), "-version"], 15)
    java_result.check_returncode()
    version = (java_result.stdout + java_result.stderr).decode()
    if 'version "25' not in version:
        raise RuntimeError(f"JDK25 required: {version}")
    manifest["java"] = version
    command = [str(java), "-Xmx512m", "-cp", os.pathsep.join(map(str, dependencies)),
               "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
               "-classpath", str(stdlib), "-jvm-target", "17", "-d", str(out / "check.jar"), *map(str, sources)]
    result = evidence.run("compile", command, 180)
    log = result.stdout + result.stderr
    if mode == "red":
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
        result = evidence.run("run", run, 60)
        assert result.returncode == 0, f"Runtime checks failed ({result.returncode})"


if __name__ == "__main__":
    main()
