#!/usr/bin/env bash
set -euo pipefail
export ANDROID_SERIAL=emulator-5556
package=com.veycad.app.uitest
report=build/reports/music-editor
mkdir -p "$report"
adb install -r app/build/outputs/apk/uiTest/app-universal-uiTest.apk
adb install -r app/build/outputs/apk/androidTest/uiTest/app-uiTest-androidTest.apk
adb shell run-as "$package" rm -rf app_music-editor-evidence
adb shell svc wifi disable
adb shell svc data disable
adb logcat -c
collect() {
  adb logcat -d -v threadtime > "$report/logcat.txt" || true
  adb exec-out run-as "$package" cat files/ui-test-results.xml > "$report/smoke.xml" || true
  adb exec-out run-as "$package" tar -cf - app_music-editor-evidence > "$report/media-evidence.tar" || true
}
trap collect EXIT
selection='com.veycad.app.CustomMusicScreenTest,com.veycad.app.CustomMusicEditorDeviceTest'
adb shell am instrument -w -r -e listener com.veycad.app.UiXmlRunListener -e class "$selection" \
  "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$report/instrumentation.txt"
adb exec-out run-as "$package" cat files/ui-test-results.xml > "$report/smoke.xml"
python3 - "$report/smoke.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
cases = root.findall('testcase')
expected = {
 'com.veycad.app.CustomMusicScreenTest#importing_wav_changes_to_custom_recipe_and_keeps_the_chosen_fragment_after_recreation',
 'com.veycad.app.CustomMusicScreenTest#cancelling_the_music_picker_keeps_the_authored_style_and_valid_track',
 'com.veycad.app.CustomMusicScreenTest#pending_import_and_offset_analysis_survive_recreation_and_block_all_style_entries',
 'com.veycad.app.CustomMusicEditorDeviceTest#savedCustomMontageReopensAndEditsWithItsMusicOffset',
 'com.veycad.app.CustomMusicEditorDeviceTest#changedMusicOffsetSurvivesStoreCompilerAndNativeExport',
}
actual = {c.attrib['classname'] + '#' + c.attrib['name'] for c in cases}
assert actual == expected and len(cases) == len(expected), (actual, expected)
assert all(root.attrib[k] == '0' for k in ('failures', 'errors', 'skipped')), root.attrib
assert all(not list(c) for c in cases), 'Failed/skipped test'
print('PASS: import, failed import preservation, saved manual edits, native export')
PY
