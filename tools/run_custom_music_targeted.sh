#!/usr/bin/env bash
# Opt-in remote diagnostics: decode the retained MP4, then identical visual inputs on two refs.
set -euo pipefail
report_root="$PWD/build/reports/custom-music-targeted"
input="$report_root/input"
package=com.veycad.app.uitest
base_sha=a0395e26b93463f91520181a2eeb09dfd05e78a3
checkout_sha=$(git rev-parse HEAD)
active_phase=''
active_device_dir=''
active_report_name=''
adb_diag() { adb -s emulator-5556 "$@"; }

collect_phase() {
    local collection_status=0
    [[ -n "$active_phase" ]] || return 0
    adb_diag exec-out run-as "$package" cat "files/$active_device_dir/$active_report_name" \
        > "$report_root/$active_phase/$active_report_name" || collection_status=$?
    adb_diag exec-out run-as "$package" cat files/ui-test-results.xml \
        > "$report_root/$active_phase/tests.xml" || collection_status=$?
    adb_diag logcat -d -v threadtime > "$report_root/$active_phase/logcat.txt" || collection_status=$?
    if [[ "$active_device_dir" == custom-music-colour-control ]]; then
        adb_diag exec-out run-as "$package" cat "files/$active_device_dir/control-output.mp4" \
            > "$report_root/$active_phase/control-output.mp4" || collection_status=$?
    fi
    return "$collection_status"
}

finish() {
    local original_status=$? collection_status=0
    trap - EXIT
    set +e
    collect_phase || collection_status=$?
    {
        printf 'format=custom-music-targeted-ci-manifest-v1\nworkflow_run_id=%s\nrun_attempt=%s\ncheckout_sha=%s\noriginal_exit_code=%s\ncollection_exit_code=%s\n' \
            "${GITHUB_RUN_ID:-unknown}" "${GITHUB_RUN_ATTEMPT:-unknown}" "$checkout_sha" \
            "$original_status" "$collection_status"
        find "$report_root" -type f ! -name diagnostic-manifest.txt -print0 |
            sort -z | xargs -0 -r sha256sum --binary
    } > "$report_root/diagnostic-manifest.txt"
    if (( original_status != 0 )); then exit "$original_status"; fi
    exit "$collection_status"
}
trap finish EXIT

install_apks() {
    local workspace=$1
    adb_diag install -r "$workspace/app/build/outputs/apk/uiTest/app-universal-uiTest.apk"
    adb_diag install -r "$workspace/app/build/outputs/apk/androidTest/uiTest/app-uiTest-androidTest.apk"
}

run_phase() {
    local phase=$1 device_dir=$2 class_name=$3 report_name=$4 build_head=$5
    shift 5
    active_phase=$phase active_device_dir=$device_dir active_report_name=$report_name
    mkdir -p "$report_root/$phase"
    adb_diag shell pm clear "$package"
    adb_diag shell mkdir -p /data/local/tmp/custom-music-diagnostic
    adb_diag shell run-as "$package" mkdir -p "files/$device_dir"
    for name in "$@"; do
        adb_diag push "$input/$name" "/data/local/tmp/custom-music-diagnostic/$name"
        adb_diag shell run-as "$package" cp "/data/local/tmp/custom-music-diagnostic/$name" "files/$device_dir/$name"
    done
    local instrumentation_status=0 collection_status=0
    local model_hashes
    model_hashes=$(python3 - "$build_head" <<'PYTHON'
import json, subprocess, sys
names = ['MediaCodecSpeedRampRenderer', 'MontageGraph', 'HighQualityFramePlan', 'GpuTransitionModel',
         'RenderPassPlanner', 'RenderedVisualSampler', 'RenderedMp4Acceptance', 'FrameAttachments',
         'ParameterTrack', 'GpuEffectGraph', 'LayerCompositorModel', 'VideoDisplayOrientation']
paths = ['app/src/main/java/com/example/autoedit/' + n + '.kt' for n in names]
print(json.dumps({p: subprocess.check_output(['git', 'rev-parse', sys.argv[1] + ':' + p], text=True).strip()
                  for p in paths}, separators=(',', ':')))
PYTHON
)
    printf '%s\n' "$model_hashes" > "$report_root/$phase/model-git-blobs.json"
    local selector="$class_name"
    if [[ "$phase" == retained-decode ]]; then
        selector+='#saved_mp4_provides_every_requested_blackout_frame_and_neighbour'
    fi
    adb_diag shell am instrument -w -r \
        -e listener com.veycad.app.UiXmlRunListener -e class "$selector" \
        -e diagnostic_run_id "${GITHUB_RUN_ID:-unknown}" -e diagnostic_attempt "${GITHUB_RUN_ATTEMPT:-unknown}" \
        -e diagnostic_checkout_sha "$checkout_sha" -e controlBuildRef "$phase" \
        -e controlBuildHeadSha "$build_head" -e controlModelHashesJson "'$model_hashes'" \
        "$package.test/androidx.test.runner.AndroidJUnitRunner" \
        > "$report_root/$phase/instrumentation.txt" 2>&1 || instrumentation_status=$?
    collect_phase || collection_status=$?
    printf 'instrumentation_exit_code=%s\ncollection_exit_code=%s\n' "$instrumentation_status" "$collection_status" \
        > "$report_root/$phase/phase-status.txt"
    # Preserve the instrumentation error; collection/XML failures cannot turn a failed test green.
    if (( instrumentation_status != 0 )); then return "$instrumentation_status"; fi
    if (( collection_status != 0 )); then return "$collection_status"; fi
    python3 tools/custom_music_tests/validate_diagnostic_xml.py "$report_root/$phase/tests.xml" "$class_name"
    python3 -m json.tool "$report_root/$phase/$report_name" > /dev/null
    active_phase='' active_device_dir='' active_report_name=''
}

# Builds happen only on this remote runner, after Android SDK setup by emulator-runner.
./gradlew --no-daemon --console=plain --max-workers=2 -PcustomMusicTargeted=true \
    :app:assembleUiTest :app:assembleUiTestAndroidTest > "$report_root/current-build.txt" 2>&1
sha256sum --binary app/build/outputs/apk/uiTest/app-universal-uiTest.apk \
    app/build/outputs/apk/androidTest/uiTest/app-uiTest-androidTest.apk > "$report_root/current-apks.sha256"
install_apks "$PWD"
run_phase retained-decode custom-music-targeted com.veycad.app.CustomMusicTargetedDecodeTest targeted-report.json \
    "$checkout_sha" targeted-input.json native-editor.mp4
run_phase colour-current custom-music-colour-control com.veycad.app.CustomMusicColourControlTest colour-control-report.json \
    "$checkout_sha" frozen-graph.json frozen-planes.f32 editor-source.mp4

# Copy the identical diagnostic harness into an unmodified base checkout's TEST source set.
# Production files stay at base_sha; the only local build edit adds this test-source directory.
control_sources="${RUNNER_TEMP:?}/custom-music-control-sources"
mkdir -p "$control_sources/com/example/autoedit"
for name in FrozenVisualGraphLoader.kt CustomMusicColourControlTest.kt; do
    cp "app/src/customMusicDiagnostic/java/com/example/autoedit/$name" "$control_sources/com/example/autoedit/$name"
done
base_workspace="${RUNNER_TEMP}/custom-music-control-base"
git worktree add --detach "$base_workspace" "$base_sha"
export CUSTOM_MUSIC_CONTROL_SOURCES="$control_sources"
cat >> "$base_workspace/app/build.gradle.kts" <<'KOTLIN'

// Diagnostic test injection only; original production sources are unchanged.
android.sourceSets.getByName("androidTest").java.srcDir(System.getenv("CUSTOM_MUSIC_CONTROL_SOURCES"))
KOTLIN
(
    cd "$base_workspace"
    chmod +x gradlew
    ./gradlew --no-daemon --console=plain --max-workers=2 :app:assembleUiTest :app:assembleUiTestAndroidTest
) > "$report_root/base-build.txt" 2>&1
git -C "$base_workspace" diff --exit-code -- app/src/main
git -C "$base_workspace" status --short > "$report_root/base-test-injection-status.txt"
sha256sum --binary "$base_workspace/app/build/outputs/apk/uiTest/app-universal-uiTest.apk" \
    "$base_workspace/app/build/outputs/apk/androidTest/uiTest/app-uiTest-androidTest.apk" > "$report_root/base-apks.sha256"
install_apks "$base_workspace"
run_phase colour-base custom-music-colour-control com.veycad.app.CustomMusicColourControlTest colour-control-report.json \
    "$base_sha" frozen-graph.json frozen-planes.f32 editor-source.mp4
python3 tools/custom_music_tests/compare_colour_control.py \
    "$report_root/colour-base/colour-control-report.json" \
    "$report_root/colour-current/colour-control-report.json" "$report_root/colour-comparison.json" \
    --current-sha "$checkout_sha"
