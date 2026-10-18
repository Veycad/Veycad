#!/usr/bin/env bash
# Optional test APK only; same reviewed candidate binary, no video render.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || exit 2
api=${1:?expected API}
[[ "$api" == 26 || "$api" == 36 ]] || exit 2
relative_root=build/reports/custom-music-software-probe
root="$PWD/$relative_root"
input="$root/staged/android-input"
package=com.veycad.app.uitest
device_dir=custom-music-software-audio
phase_started=false
mkdir -p "$root/decoded" "$root/apks"
adb_probe() { adb -s emulator-5556 "$@"; }
collect_file() {
    local destination=$1 status=0
    shift
    local temporary="${destination}.collecting"
    adb_probe "$@" > "$temporary" || status=$?
    if (( status == 0 )) && [[ ! -s "$temporary" ]]; then status=1; fi
    if (( status == 0 )); then mv -- "$temporary" "$destination" || status=$?; fi
    if (( status != 0 )); then rm -f -- "$temporary"; fi
    return "$status"
}
collect() {
    local status=0
    if [[ "$phase_started" == true ]]; then
        for file in native_mp4-report.json native_mp4-raw.f32le native_mp4-presentation.f32le \
                    overshoot-report.json overshoot-raw.f32le overshoot-presentation.f32le \
                    integer_cast-report.json integer_cast-converted.f32le; do
            collect_file "$root/decoded/$file" exec-out run-as "$package" cat "files/$device_dir/$file" || status=$?
        done
        collect_file "$root/tests.xml" exec-out run-as "$package" cat files/ui-test-results.xml || status=$?
        collect_file "$root/logcat.txt" logcat -d -v threadtime || status=$?
    fi
    return "$status"
}
finish() {
    local original=$? collection=0
    trap - EXIT
    set +e
    collect || collection=$?
    {
        printf 'format=custom-music-software-probe-manifest-v1\nworkflow_run_id=%s\nrun_attempt=%s\ncheckout_sha=%s\nexpected_api=%s\noriginal_exit_code=%s\ncollection_exit_code=%s\n' \
          "${GITHUB_RUN_ID:-unknown}" "${GITHUB_RUN_ATTEMPT:-unknown}" "$(git rev-parse HEAD)" "$api" "$original" "$collection"
        find "$relative_root" -type f ! -name diagnostic-manifest.txt -print0 | sort -z | xargs -0 -r sha256sum --binary
    } > "$root/diagnostic-manifest.txt"
    if (( original != 0 )); then exit "$original"; fi
    exit "$collection"
}
trap finish EXIT
[[ "$(adb_probe shell getprop ro.build.version.sdk | tr -d '\r')" == "$api" ]]
binary_dir=app/build/customMusicSoftwareDiagnostic/jniLibs/x86_64
mkdir -p "$binary_dir"
cp "$root/staged/build/android/x86_64/libsoftware_aac_probe.so" "$binary_dir/"
./gradlew --no-daemon --console=plain --max-workers=2 -PcustomMusicSoftwareAudioProbe=true \
  :app:assembleUiTest :app:assembleUiTestAndroidTest > "$root/current-build.txt" 2>&1
cp app/build/outputs/apk/uiTest/app-universal-uiTest.apk "$root/apks/"
cp app/build/outputs/apk/androidTest/uiTest/app-uiTest-androidTest.apk "$root/apks/"
sha256sum --binary "$root/apks/"*.apk > "$root/current-apks.sha256"
adb_probe install -r "$root/apks/app-universal-uiTest.apk"
adb_probe install -r "$root/apks/app-uiTest-androidTest.apk"
adb_probe shell pm clear "$package"
adb_probe shell mkdir -p /data/local/tmp/custom-music-software-audio
adb_probe shell run-as "$package" mkdir -p "files/$device_dir"
for file in material-input.json audio-input.json native_mp4.mp4 overshoot.mp4; do
    adb_probe push "$input/$file" "/data/local/tmp/custom-music-software-audio/$file"
    adb_probe shell run-as "$package" cp "/data/local/tmp/custom-music-software-audio/$file" "files/$device_dir/$file"
done
phase_started=true
instrumentation=0 collection=0
adb_probe shell am instrument -w -r -e listener com.veycad.app.UiXmlRunListener \
  -e class com.veycad.app.CustomMusicSoftwareAudioProbeTest \
  -e diagnostic_checkout_sha "$(git rev-parse HEAD)" -e diagnostic_run_id "${GITHUB_RUN_ID:-unknown}" \
  "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$root/instrumentation.txt" 2>&1 || instrumentation=$?
collect || collection=$?
printf 'instrumentation_exit_code=%s\ncollection_exit_code=%s\n' "$instrumentation" "$collection" > "$root/phase-status.txt"
if (( instrumentation != 0 )); then exit "$instrumentation"; fi
if (( collection != 0 )); then exit "$collection"; fi
python3 tools/custom_music_tests/validate_software_aac_xml.py "$root/tests.xml"
python3 - "$root/decoded" <<'PYTHON'
import json, pathlib, sys
directory = pathlib.Path(sys.argv[1])
for case in ('native_mp4', 'overshoot', 'integer_cast'):
    report = json.loads((directory / (case + '-report.json')).read_text())
    if report['case'] != case or report['decode_complete'] is not True:
        raise SystemExit('Incomplete case: ' + case)
    if report['unclamped_float_witness_complete'] is not (case != 'integer_cast'):
        raise SystemExit('Wrong witness: ' + case)
    if case != 'native_mp4' and report['audio_quality_pass'] is not False:
        raise SystemExit('Negative control accepted: ' + case)
PYTHON
