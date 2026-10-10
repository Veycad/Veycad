"""Host contract for the custom-music CI wrapper; never starts Android/Gradle."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/run_custom_music_ci_tests.sh"
FILES = (
    "editor-source.mp4", "editor.wav", "native-editor.mp4",
    "native-editor-evidence.json", "native-editor-report.txt",
)
BASH = shutil.which("bash") or "C:/Program Files/Git/bin/bash.exe"


class RetentionContractTest(unittest.TestCase):
    def run_wrapper(self, ui_exit=0, missing=None, blocked_destination=False):
        self.assertTrue(SCRIPT.is_file(), "CI must retain native files before emulator shutdown")
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        folder = Path(temporary.name)
        source = folder / "device"
        source.mkdir()
        for index, name in enumerate(FILES):
            if name != missing:
                (source / name).write_bytes(
                    b'{"quality_gate":false}' if name.endswith(".json") else bytes(range(256)) * (index + 1)
                )
        (source / "unrelated-user-media.mp4").write_bytes(b"excluded")
        injection = folder / "commands.sh"
        # The real CLI is replaced because this host check must not start a device/build.
        injection.write_text("""
pwsh() { return "$FAKE_UI_EXIT"; }
adb() {
    [[ "$#" == 5 && "$1" == "-s" && "$2" == "emulator-5556" && "$3" == "pull" ]] || return 90
    [[ "$4" == /sdcard/Android/data/com.veycad.app.uitest/files/custom-audio-evidence/* ]] || return 91
    local name
    name=$(basename -- "$4")
    cp -- "$FAKE_DEVICE/$name" "$5"
}
""", encoding="utf-8")
        environment = os.environ.copy()
        environment.update(BASH_ENV=injection.as_posix(), FAKE_UI_EXIT=str(ui_exit),
                           FAKE_DEVICE=source.as_posix(), LC_ALL="C")
        destination = folder / "build/reports/ui-tests/custom-music-native"
        if blocked_destination:
            destination.parent.mkdir(parents=True)
            destination.write_bytes(b"blocked directory")
        result = subprocess.run([BASH, SCRIPT.as_posix()], cwd=folder, env=environment,
                                capture_output=True, text=True, encoding="utf-8", timeout=15)
        return result, source, destination

    def assert_retained(self, source, destination, names=FILES):
        self.assertEqual(set(names), {file.name for file in destination.iterdir()})
        for name in names:
            self.assertEqual(hashlib.sha256((source / name).read_bytes()).digest(),
                             hashlib.sha256((destination / name).read_bytes()).digest())

    def test_success_retains_exact_binary_files_and_failed_quality_gate(self):
        result, source, destination = self.run_wrapper()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_retained(source, destination)
        self.assertEqual(b'{"quality_gate":false}', (destination / FILES[3]).read_bytes())

    def test_failed_ui_still_retains_evidence_and_keeps_its_exit_code(self):
        result, source, destination = self.run_wrapper(ui_exit=23)
        self.assertEqual(23, result.returncode, result.stderr)
        self.assert_retained(source, destination)

    def test_missing_file_fails_collection_but_keeps_other_evidence(self):
        result, source, destination = self.run_wrapper(missing=FILES[2])
        self.assertNotEqual(0, result.returncode)
        self.assert_retained(source, destination, tuple(name for name in FILES if name != FILES[2]))

    def test_collection_failure_does_not_mask_original_ui_failure(self):
        result, source, destination = self.run_wrapper(ui_exit=23, missing=FILES[2])
        self.assertEqual(23, result.returncode, result.stderr)
        self.assert_retained(source, destination, tuple(name for name in FILES if name != FILES[2]))

    def test_directory_failure_does_not_mask_original_ui_failure(self):
        result, _, _ = self.run_wrapper(ui_exit=23, blocked_destination=True)
        self.assertEqual(23, result.returncode, result.stderr)

    def test_directory_failure_rejects_success_without_evidence(self):
        result, _, _ = self.run_wrapper(blocked_destination=True)
        self.assertEqual(1, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
