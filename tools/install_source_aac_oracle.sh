#!/usr/bin/env bash
# CI-only independent AAC oracle. Ubuntu's FFmpeg 6.1 does not discard a partial final AU
# from MOV edit duration; 9.0.2 does. Keep the sample-count and waveform gates unchanged.
set -euo pipefail

readonly version=9.0.2
readonly source_sha256=8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e
readonly source_url="https://ffmpeg.org/releases/ffmpeg-${version}.tar.xz"
readonly repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
readonly prefix="${repo_root}/build/tools/source-aac-oracle"
readonly report="${repo_root}/build/reports/source-aac-oracle"

if [[ "$(uname -s)" != Linux ]]; then
    echo 'The pinned oracle installer requires a Linux build host.' >&2
    exit 1
fi
mkdir -p -- "$prefix" "$report"
work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/source-aac-oracle.XXXXXXXX")"
readonly work
trap 'rm -rf -- "$work"' EXIT
readonly archive="${work}/ffmpeg-${version}.tar.xz"
printf 'version=%s\nsource=%s\nsha256=%s\n' "$version" "$source_url" "$source_sha256" > "$report/source.txt"
curl --fail --location --retry 3 --proto '=https' --tlsv1.2 "$source_url" --output "$archive"
printf '%s  %s\n' "$source_sha256" "$archive" | sha256sum --check --strict | tee "$report/source-sha256.txt"
tar -xf "$archive" -C "$work"
cd -- "$work/ffmpeg-${version}"

# Build only the independent MOV/AAC -> unclamped interleaved float path used by the oracle.
# No external libraries, network protocols, assembly tools, encoder AAC or production backend.
readonly -a options=(
    "--prefix=$prefix"
    --disable-autodetect --disable-everything --disable-network
    --disable-doc --disable-debug --disable-x86asm --disable-shared --enable-static
    --disable-avdevice --disable-swscale
    --enable-ffmpeg --enable-ffprobe
    --enable-decoder=aac --enable-demuxer=mov
    --enable-encoder=pcm_f32le --enable-muxer=pcm_f32le
    --enable-protocol=file,pipe
    --enable-filter=aformat,anull,aresample,atrim
    --enable-swresample
)
printf '%s\n' "${options[@]}" > "$report/configure-options.txt"
if ! ./configure "${options[@]}" > "$report/configure.txt" 2>&1; then
    cat "$report/configure.txt" >&2
    cp -- ffbuild/config.log "$report/config.log"
    exit 1
fi
cp -- ffbuild/config.log "$report/config.log"
# Bound memory use even when the runner advertises many CPUs.
make -j2 > "$report/build.txt" 2>&1 || { tail -n 100 "$report/build.txt" >&2; exit 1; }
make install > "$report/install.txt" 2>&1
"$prefix/bin/ffmpeg" -version > "$report/ffmpeg-version.txt"
"$prefix/bin/ffprobe" -version > "$report/ffprobe-version.txt"
grep -q '^ffmpeg version 9\.0\.2' "$report/ffmpeg-version.txt"
grep -q '^ffprobe version 9\.0\.2' "$report/ffprobe-version.txt"
sha256sum "$prefix/bin/ffmpeg" "$prefix/bin/ffprobe" > "$report/binary-sha256.txt"
cat "$report/ffmpeg-version.txt"