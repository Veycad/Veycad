#!/usr/bin/env bash
# Run the existing gate unchanged, then retain only its synthetic custom-music evidence.
set -uo pipefail

ui_status=0
pwsh -NoProfile -File tools/run_ui_tests.ps1 || ui_status=$?

target=build/reports/ui-tests/custom-music-native
if ! mkdir -p -- "$target"; then
    if (( ui_status != 0 )); then
        exit "$ui_status"
    fi
    exit 1
fi
pull_status=0
files=(editor-source.mp4 editor.wav native-editor.mp4 native-editor-evidence.json
       native-editor-report.txt stream-clock.json stream-clock-copy.json selected-tail.mp4)
for name in "${files[@]}"; do
    adb -s emulator-5556 pull \
        "/sdcard/Android/data/com.veycad.app.uitest/files/custom-audio-evidence/$name" \
        "$target/$name" || pull_status=$?
done

checkout_sha=$(git rev-parse HEAD) || pull_status=1
{
    printf 'format=custom-music-ci-manifest-v1\nworkflow_run_id=%s\nrun_attempt=%s\ncheckout_sha=%s\ngithub_sha=%s\nui_exit_code=%s\n' \
        "${GITHUB_RUN_ID:-unknown}" "${GITHUB_RUN_ATTEMPT:-unknown}" "$checkout_sha" \
        "${GITHUB_SHA:-unknown}" "$ui_status"
    for name in "${files[@]}"; do
        if [[ -f "$target/$name" ]]; then
            (cd "$target" && sha256sum --binary -- "$name") || pull_status=1
        else
            pull_status=1
            printf 'MISSING %s\n' "$name"
        fi
    done
    printf 'collection_exit_code=%s\n' "$pull_status"
} > "$target/ci-manifest.txt" || pull_status=1

# A collection error must neither hide a failed gate nor report missing evidence as success.
if (( ui_status != 0 )); then
    exit "$ui_status"
fi
exit "$pull_status"
