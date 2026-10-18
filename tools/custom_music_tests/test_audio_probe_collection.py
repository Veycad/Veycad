"""Failure-path collection checks using the actual shell functions, without a device/build."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/run_custom_music_audio_probe.sh"
BASH = shutil.which("bash") or "C:/Program Files/Git/bin/bash.exe"


class AudioProbeCollectionTest(unittest.TestCase):
    def run_failure(self, original=23, retry="failure"):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        # Run the reviewed prelude/collection/EXIT trap, stopping before the first build.
        prelude = SCRIPT.read_text(encoding="utf-8").split("./gradlew --no-daemon", 1)[0]
        harness = root / "collect.sh"
        harness.write_text(prelude + """
adb_probe() {
    if [[ "$probe_retry" == false ]]; then printf '%s\\n' saved-evidence; return 0; fi
    if [[ "$probe_mode" == empty ]]; then return 0; fi
    printf '%s' partial-failed-copy
    return 29
}
phase_started=true
probe_retry=false
collect
probe_retry=true
exit "$probe_original"
""", encoding="utf-8")
        environment = os.environ.copy()
        injection = root / "inject.sh"
        injection.write_text("git() { printf '%s\\n' 0123456789012345678901234567890123456789; }\n")
        environment.update(BASH_ENV=injection.as_posix(), probe_mode=retry,
                           probe_original=str(original), GITHUB_RUN_ID="1234", GITHUB_RUN_ATTEMPT="1")
        result = subprocess.run([BASH, harness.as_posix()], cwd=root, env=environment,
                                capture_output=True, text=True, encoding="utf-8", timeout=15)
        return result, root / "build/reports/custom-music-audio-probe"

    def assert_saved_files(self, report):
        for name in ("audio-probe-report.json", "tests.xml", "logcat.txt"):
            self.assertEqual(b"saved-evidence\n", (report / name).read_bytes())
        self.assertFalse(list(report.glob("*.collecting")))

    def test_exit_retry_failure_preserves_saved_report_and_original_failure(self):
        result, report = self.run_failure()
        self.assertEqual(23, result.returncode, result.stderr)
        self.assert_saved_files(report)
        manifest = (report / "diagnostic-manifest.txt").read_text()
        self.assertIn("original_exit_code=23\ncollection_exit_code=29\n", manifest)

    def test_empty_successful_adb_retry_cannot_erase_saved_evidence(self):
        result, report = self.run_failure(retry="empty")
        self.assertEqual(23, result.returncode, result.stderr)
        self.assert_saved_files(report)
        self.assertIn("collection_exit_code=1\n", (report / "diagnostic-manifest.txt").read_text())

    def test_exit_collection_failure_cannot_turn_success_green(self):
        result, report = self.run_failure(original=0)
        self.assertEqual(29, result.returncode, result.stderr)
        self.assert_saved_files(report)
        self.assertIn("original_exit_code=0\ncollection_exit_code=29\n",
                      (report / "diagnostic-manifest.txt").read_text())


if __name__ == "__main__":
    unittest.main()
