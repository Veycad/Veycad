"""Evidence lifecycle tests only; mocked failures are NOT legacy compatibility proof."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import verify_render_qa_legacy as qa


class EvidenceLifecycleTest(unittest.TestCase):
    # Known provenance, not a replacement model or compilable source definition.
    source = {"path": "app/src/main/java/com/example/autoedit/MontageGraph.kt",
              "blob": "b1cc349b2555f4058c2f0f994ff3ae279f814c70",
              "sha256": "202295f2f56e61b161edf06f71db31db4bc45f670d6ab80f0ae7ca71cf300e6f"}

    def read(self, out):
        return json.loads((out / "manifest.json").read_text(encoding="utf-8"))

    def test_setup_failure_retains_initial_and_incremental_source_provenance(self):
        for phase, error in (("dependencies", RuntimeError("Missing cached kotlin-stdlib")),
                             ("java", RuntimeError("JDK25 required"))):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as temp:
                out = Path(temp)
                with self.assertRaisesRegex(RuntimeError, str(error)):
                    with qa.Evidence(out, "green") as evidence:
                        self.assertEqual(qa.MUSIC, self.read(out)["music_commit"])
                        evidence.source(self.source)
                        self.assertEqual([self.source], self.read(out)["sources"])
                        evidence.phase(phase)
                        raise error
                saved = self.read(out)
                self.assertEqual("FAIL", saved["status"])
                self.assertEqual(phase, saved["phase"])
                self.assertEqual([self.source], saved["sources"])
                self.assertEqual(str(error), saved["error"]["message"])

    def test_runtime_nonzero_preserves_argv_output_and_assertion_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            command = ["java", "-Xmx256m", "-cp", "real-check.jar", "com.veycad.app.LegacyRenderQaCheckKt"]
            def failed_process(actual, **kwargs):
                # Evidence must be on disk BEFORE the child can fail.
                self.assertEqual(command, self.read(out)["run_command"])
                return subprocess.CompletedProcess(actual, 1, b"before assertion\n", b"AssertionError\n")
            with self.assertRaisesRegex(AssertionError, "Runtime checks failed"):
                with qa.Evidence(out, "green") as evidence:
                    evidence.source(self.source)
                    with patch.object(qa.subprocess, "run", side_effect=failed_process):
                        result = evidence.run("run", command, 60)
                    assert result.returncode == 0, f"Runtime checks failed ({result.returncode})"
            saved = self.read(out)
            self.assertEqual(command, saved["run_command"])
            self.assertEqual(1, saved["results"]["run"]["returncode"])
            self.assertEqual("FAIL", saved["status"])
            self.assertEqual([self.source], saved["sources"])
            self.assertEqual(b"before assertion\nAssertionError\n", (out / "run.log").read_bytes())

    def test_compiler_and_runtime_timeout_preserve_available_bytes_and_argv(self):
        for phase, timeout, stdout, stderr in (
            ("compile", 180, b"compiler\xff\n", b"compile error\x00"),
            ("run", 60, b"runtime progress\n", b"runtime diagnostic\n"),
            ("run", 60, None, b"stderr only\n"),
        ):
            with self.subTest(phase=phase, stdout=stdout), tempfile.TemporaryDirectory() as temp:
                out = Path(temp)
                command = ["java", "-Xmx512m" if phase == "compile" else "-Xmx256m", phase]
                def timed_out(actual, **kwargs):
                    self.assertEqual(command, self.read(out)[f"{phase}_command"])
                    raise subprocess.TimeoutExpired(actual, timeout, output=stdout, stderr=stderr)
                with self.assertRaises(subprocess.TimeoutExpired):
                    with qa.Evidence(out, "green") as evidence:
                        evidence.source(self.source)
                        with patch.object(qa.subprocess, "run", side_effect=timed_out):
                            evidence.run(phase, command, timeout)
                saved = self.read(out)
                self.assertEqual("FAIL", saved["status"])
                self.assertEqual("TIMEOUT", saved["results"][phase]["status"])
                self.assertEqual(timeout, saved["results"][phase]["timeout_seconds"])
                self.assertEqual(command, saved[f"{phase}_command"])
                self.assertEqual([self.source], saved["sources"])
                self.assertEqual((stdout or b"") + stderr, (out / f"{phase}.log").read_bytes())

    def test_expected_red_compile_failure_can_still_finish_as_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            with qa.Evidence(out, "red") as evidence:
                with patch.object(qa.subprocess, "run", return_value=subprocess.CompletedProcess(
                        ["java"], 1, b"", b"expected compiler diagnostic")):
                    result = evidence.run("compile", ["java"], 180)
                self.assertNotEqual(0, result.returncode)
            saved = self.read(out)
            self.assertEqual("PASS", saved["status"])
            self.assertEqual(1, saved["results"]["compile"]["returncode"])

    def test_launch_failure_preserves_command_and_named_phase(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            with self.assertRaises(FileNotFoundError):
                with qa.Evidence(out, "green") as evidence:
                    with patch.object(qa.subprocess, "run", side_effect=FileNotFoundError("java absent")):
                        evidence.run("java", ["missing-java", "-version"], 15)
            saved = self.read(out)
            self.assertEqual("FAIL", saved["status"])
            self.assertEqual("java", saved["phase"])
            self.assertEqual(["missing-java", "-version"], saved["java_command"])
            self.assertEqual("LAUNCH_FAILED", saved["results"]["java"]["status"])
            self.assertEqual(b"", (out / "java.log").read_bytes())


if __name__ == "__main__":
    unittest.main()
