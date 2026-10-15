#!/usr/bin/env bash
set -euo pipefail
# This runner is for the disposable remote emulator. It never resets a production package.
export ANDROID_SERIAL=emulator-5556
package=com.veycad.app.mvp
report=build/reports/mvp
mkdir -p "$report"
adb install -r "$report/app-mvp.apk"
adb install -r app/build/outputs/apk/androidTest/mvp/app-mvp-androidTest.apk
adb shell pm grant "$package" android.permission.READ_MEDIA_VIDEO
adb shell pm grant "$package" android.permission.POST_NOTIFICATIONS
adb shell svc wifi disable
adb shell svc data disable
adb logcat -c
collect_evidence() {
    adb logcat -d -v threadtime > "$report/logcat.txt"
    adb exec-out run-as "$package" cat files/ui-test-results.xml > "$report/smoke.xml"
    adb exec-out run-as "$package" tar -cf - files/mvp-smoke files/render-inspector > "$report/media-evidence.tar"
    for index in 0 1 2; do
        adb exec-out run-as "$package" cat "files/mvp-smoke/input-$index.mp4" > "$report/input-$index.mp4"
    done
}
trap collect_evidence EXIT
selection='com.veycad.app.GalleryMvpSmokeTest,com.veycad.app.TextEditActivityDeviceTest#editStylesCuesRestoreDraftCancelAndPublishNewMp4'
adb shell am instrument -w -r -e listener com.veycad.app.UiXmlRunListener -e class "$selection" \
    "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$report/instrumentation.txt"
adb exec-out run-as "$package" cat files/ui-test-results.xml > "$report/smoke.xml"
python3 - "$report/smoke.xml" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
expected = {
    "com.veycad.app.GalleryMvpSmokeTest#threeSourcePickerRotationRealRenderPreviewSaveAndShare",
    "com.veycad.app.GalleryMvpSmokeTest#cancelAndInvalidSelectionPreservePreviousVideoAndOriginals",
    "com.veycad.app.GalleryMvpSmokeTest#separatelyChosenRawRussianSpeechRecognizedInExistingTextEditor",
    "com.veycad.app.TextEditActivityDeviceTest#editStylesCuesRestoreDraftCancelAndPublishNewMp4",
}
cases = root.findall("testcase")
actual = {case.attrib["classname"] + "#" + case.attrib["name"] for case in cases}
assert actual == expected and len(cases) == len(expected), (actual, expected)
assert root.attrib["executed"] == "4" and root.attrib["tests"] == "4"
assert all(root.attrib[key] == "0" for key in ("failures", "errors", "skipped")), root.attrib
assert all(not list(case) for case in cases), "Failed or skipped MVP smoke case"
log = Path(sys.argv[1]).with_name("instrumentation.txt").read_text()
assert "INSTRUMENTATION_CODE: -1" in log and "FAILURES!!!" not in log and "Process crashed" not in log
print("PASS: 4 selected real MVP cases, no skipped cases")
PY
