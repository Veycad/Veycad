"""Remote MVP APK evidence. Uses the existing UI runner's metadata/aapt isolation contract."""
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import zipfile


def sha_stream(stream):
    digest = hashlib.sha256()
    for block in iter(lambda: stream.read(128 * 1024), b""):
        digest.update(block)
    return digest.hexdigest()


apk, report = map(Path, sys.argv[1:3])
sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
aapt = sorted(sdk.glob("build-tools/*/aapt"))[-1]
package = "com.veycad.app.mvp"
metadata = json.loads(Path("app/build/outputs/apk/mvp/output-metadata.json").read_text())
assert metadata["applicationId"] == package
test_metadata_path = Path("app/build/outputs/apk/androidTest/mvp/output-metadata.json")
test_metadata = json.loads(test_metadata_path.read_text())
assert test_metadata["applicationId"] == package + ".test"
badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
assert f"package: name='{package}'" in badging
assert "application-debuggable" in badging
assert "application-label:'Veycad MVP'" in badging
permissions = subprocess.check_output([str(aapt), "dump", "permissions", str(apk)], text=True)
assert "android.permission.INTERNET" not in permissions
assert "android.permission.ACCESS_NETWORK_STATE" not in permissions
test_apk = test_metadata_path.parent / test_metadata["elements"][0]["outputFile"]
tree = subprocess.check_output([str(aapt), "dump", "xmltree", str(test_apk), "AndroidManifest.xml"], text=True)
target = re.search(r'android:targetPackage[^=]*="([^"]+)"', tree)
assert target and target.group(1) == package
expected_model = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"
with zipfile.ZipFile(apk) as archive:
    with archive.open("assets/speech/ggml-base.bin") as stream:
        model_sha = sha_stream(stream)
    assert model_sha == expected_model
    for license_name in ("whisper.txt", "whisper-model.txt"):
        assert len(archive.read("assets/licenses/" + license_name)) > 100
    native = [name for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")]
    assert {"arm64-v8a", "armeabi-v7a", "x86_64"} <= {name.split("/")[1] for name in native}
    assert all(f"lib/{abi}/libveycad_whisper.so" in native
               for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"))
with apk.open("rb") as stream:
    apk_sha = sha_stream(stream)
report.parent.mkdir(parents=True, exist_ok=True)
report.write_text(json.dumps({"apk_sha256": apk_sha, "model_sha256": model_sha,
    "package": package, "offline_permissions": True, "licenses": True, "native_libraries": native,
    "head": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()}, indent=2) + "\n")
