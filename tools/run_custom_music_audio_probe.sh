#!/usr/bin/env bash
# Optional current-build audio decode/format trace; no video render or QA override.
set -euo pipefail
report_root="$PWD/build/reports/custom-music-audio-probe"
input="$report_root/input"
package=com.veycad.app.uitest
device_dir=custom-music-audio-probe
checkout_sha=$(git rev-parse HEAD)
phase_started=false
adb_probe() { adb -s emulator-5556 "$@"; }

collect_file() {
    local destination=$1
    shift
    local temporary="${destination}.collecting" status=0
    adb_probe "$@" > "$temporary" || status=$?
    if (( status == 0 )) && [[ ! -s "$temporary" ]]; then status=1; fi
    if (( status == 0 )); then
        mv -- "$temporary" "$destination" || status=$?
    fi
    if (( status != 0 )); then rm -f -- "$temporary"; fi
    return "$status"
}

collect() {
    local status=0
    if [[ "$phase_started" == true ]]; then
        collect_file "$report_root/audio-probe-report.json" exec-out run-as "$package" cat \
            "files/$device_dir/audio-probe-report.json" || status=$?
        collect_file "$report_root/tests.xml" exec-out run-as "$package" cat files/ui-test-results.xml || status=$?
        collect_file "$report_root/logcat.txt" logcat -d -v threadtime || status=$?
    fi
    return "$status"
}

finish() {
    local original_status=$? collection_status=0
    trap - EXIT
    set +e
    collect || collection_status=$?
    {
        printf 'format=custom-music-audio-probe-ci-manifest-v1\nworkflow_run_id=%s\nrun_attempt=%s\ncheckout_sha=%s\noriginal_exit_code=%s\ncollection_exit_code=%s\n' \
            "${GITHUB_RUN_ID:-unknown}" "${GITHUB_RUN_ATTEMPT:-unknown}" "$checkout_sha" \
            "$original_status" "$collection_status"
        find "$report_root" -type f ! -name diagnostic-manifest.txt -print0 |
            sort -z | xargs -0 -r sha256sum --binary
    } > "$report_root/diagnostic-manifest.txt"
    if (( original_status != 0 )); then exit "$original_status"; fi
    exit "$collection_status"
}
mkdir -p "$report_root"
trap finish EXIT

./gradlew --no-daemon --console=plain --max-workers=2 -PcustomMusicTargeted=true \
    :app:assembleUiTest :app:assembleUiTestAndroidTest > "$report_root/current-build.txt" 2>&1
sha256sum --binary app/build/outputs/apk/uiTest/app-universal-uiTest.apk \
    app/build/outputs/apk/androidTest/uiTest/app-uiTest-androidTest.apk > "$report_root/current-apks.sha256"
adb_probe install -r app/build/outputs/apk/uiTest/app-universal-uiTest.apk
adb_probe install -r app/build/outputs/apk/androidTest/uiTest/app-uiTest-androidTest.apk
adb_probe shell pm clear "$package"
adb_probe shell mkdir -p /data/local/tmp/custom-music-audio-probe
adb_probe shell run-as "$package" mkdir -p "files/$device_dir"
for name in audio-input.json native-editor.mp4; do
    adb_probe push "$input/$name" "/data/local/tmp/custom-music-audio-probe/$name"
    adb_probe shell run-as "$package" cp "/data/local/tmp/custom-music-audio-probe/$name" "files/$device_dir/$name"
done
model_hashes=$(python3 - "$checkout_sha" <<'PYTHON'
import json, subprocess, sys
names = ['MediaCodecAudioDecoder', 'AudioPcmWindow', 'DecodedAudioQuality']
paths = ['app/src/main/java/com/example/autoedit/' + name + '.kt' for name in names]
print(json.dumps({p: subprocess.check_output(['git', 'rev-parse', sys.argv[1] + ':' + p], text=True).strip()
                  for p in paths}, separators=(',', ':')))
PYTHON
)
printf '%s\n' "$model_hashes" > "$report_root/model-git-blobs.json"
class_name=com.veycad.app.CustomMusicAudioFloatProbeTest
phase_started=true
instrumentation_status=0 collection_status=0
adb_probe shell am instrument -w -r \
    -e listener com.veycad.app.UiXmlRunListener \
    -e class "$class_name#same_saved_native_mp4_audio_float_probe" \
    -e diagnostic_run_id "${GITHUB_RUN_ID:-unknown}" -e diagnostic_attempt "${GITHUB_RUN_ATTEMPT:-unknown}" \
    -e diagnostic_checkout_sha "$checkout_sha" -e audioProbeModelHashesJson "'$model_hashes'" \
    "$package.test/androidx.test.runner.AndroidJUnitRunner" \
    > "$report_root/instrumentation.txt" 2>&1 || instrumentation_status=$?
collect || collection_status=$?
printf 'instrumentation_exit_code=%s\ncollection_exit_code=%s\n' "$instrumentation_status" "$collection_status" \
    > "$report_root/phase-status.txt"
if (( instrumentation_status != 0 )); then exit "$instrumentation_status"; fi
if (( collection_status != 0 )); then exit "$collection_status"; fi
python3 tools/custom_music_tests/validate_diagnostic_xml.py "$report_root/tests.xml" "$class_name"
python3 -m json.tool "$report_root/audio-probe-report.json" > /dev/null
