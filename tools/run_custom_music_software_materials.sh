#!/usr/bin/env bash
# Remote preparation only. Never loads the Android candidate or renders video.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || exit 2
relative_root=build/reports/custom-music-software-materials
root="$PWD/$relative_root"
mkdir -p "$root"
finish() {
    local original=$?
    trap - EXIT
    set +e
    {
        printf 'format=custom-music-software-materials-manifest-v1\nworkflow_run_id=%s\nrun_attempt=%s\ncheckout_sha=%s\noriginal_exit_code=%s\n' \
          "${GITHUB_RUN_ID:-unknown}" "${GITHUB_RUN_ATTEMPT:-unknown}" "$(git rev-parse HEAD)" "$original"
        find "$relative_root" -type f ! -name diagnostic-manifest.txt \
          ! -path "$relative_root/build/source/android-build/*" ! -path "$relative_root/build/source/host-build/*" \
          ! -path "$relative_root/build/source/ffmpeg/*" -print0 | sort -z | xargs -0 -r sha256sum --binary
    } > "$root/diagnostic-manifest.txt"
    exit "$original"
}
trap finish EXIT
bash tools/custom_music_tests/build_software_aac_materials.sh "$root/build"
python3 tools/custom_music_tests/prepare_software_aac_reference.py "$root/input" "$root/build" "$root/materials"
python3 tools/custom_music_tests/verify_software_aac_materials.py inspect "$root"
