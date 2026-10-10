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
for name in editor-source.mp4 editor.wav native-editor.mp4 native-editor-evidence.json native-editor-report.txt; do
    adb -s emulator-5556 pull \
        "/sdcard/Android/data/com.veycad.app.uitest/files/custom-audio-evidence/$name" \
        "$target/$name" || pull_status=$?
done

# A collection error must neither hide a failed gate nor report missing evidence as success.
if (( ui_status != 0 )); then
    exit "$ui_status"
fi
exit "$pull_status"
