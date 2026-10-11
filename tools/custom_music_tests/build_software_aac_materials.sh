#!/usr/bin/env bash
# Remote-only diagnostic build. No production dependency is installed.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'Remote GitHub Actions only' >&2; exit 2; }
out="$(realpath -m "${1:?output directory}")"
[[ ! -e "$out" ]] || { echo 'Refusing reused output' >&2; exit 2; }
mkdir -p "$out"/{provenance,android/x86_64,host,source}
exec > >(tee "$out/provenance/build.log") 2>&1
commit=f893221c8d89cb798b829bebe71d55e1a3f242fd
ndk_version=27.2.12479018
ndk="${ANDROID_SDK_ROOT:?}/ndk/$ndk_version"
[[ -f "$ndk/source.properties" ]] || { echo 'Pinned NDK missing'; exit 2; }
cp "$ndk/source.properties" "$out/provenance/ndk-source.properties"
git init "$out/source/ffmpeg"
git -C "$out/source/ffmpeg" remote add origin https://github.com/FFmpeg/FFmpeg.git
git -C "$out/source/ffmpeg" fetch --depth=1 origin "$commit"
git -C "$out/source/ffmpeg" checkout --detach FETCH_HEAD
[[ "$(git -C "$out/source/ffmpeg" rev-parse HEAD)" == "$commit" ]]
git -C "$out/source/ffmpeg" archive --format=tar HEAD > "$out/source/ffmpeg-source.tar"
sha256sum "$out/source/ffmpeg-source.tar" > "$out/provenance/source-archive.sha256"
printf '%s\n' "$commit" > "$out/provenance/source-commit.txt"
cp "$out/source/ffmpeg"/COPYING* "$out/provenance/"
tool="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
"$tool/x86_64-linux-android26-clang" --version > "$out/provenance/android-compiler.txt"
sha256sum "$tool/clang" "$tool/ld.lld" > "$out/provenance/toolchain.sha256"
common=(--disable-everything --disable-autodetect --disable-network --disable-gpl --disable-nonfree --disable-doc --disable-debug --disable-x86asm)
mkdir "$out/source/android-build"; cd "$out/source/android-build"
android=("${common[@]}" --enable-cross-compile --target-os=android --arch=x86_64 --enable-pic --disable-shared --enable-static --disable-programs --disable-avformat --disable-avdevice --disable-avfilter --disable-swscale --disable-swresample --disable-postproc --enable-avcodec --enable-avutil --enable-decoder=aac --cc="$tool/x86_64-linux-android26-clang" --ar="$tool/llvm-ar" --ranlib="$tool/llvm-ranlib" --strip="$tool/llvm-strip")
printf '%s\n' "${android[@]}" > "$out/provenance/android-configure-argv.txt"
../ffmpeg/configure "${android[@]}"
cp config.h config_components.h ffbuild/config.mak "$out/provenance/"
make -j2
repo="${GITHUB_WORKSPACE:?}"
jni_source="$repo/app/src/customMusicSoftwareDiagnostic/cpp/SoftwareAacProbe.cpp"
sha256sum "$jni_source" > "$out/provenance/jni-source.sha256"
jni=( -shared -fPIC -std=c++17 -O2 -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,-soname,libsoftware_aac_probe.so -I../ffmpeg -I. "$jni_source" libavcodec/libavcodec.a libavutil/libavutil.a -lm -lz -latomic -static-libstdc++ -o "$out/android/x86_64/libsoftware_aac_probe.so" )
printf '%s\n' "${jni[@]}" > "$out/provenance/jni-compile-argv.txt"
"$tool/x86_64-linux-android26-clang++" "${jni[@]}"
"$tool/llvm-readelf" -d "$out/android/x86_64/libsoftware_aac_probe.so" > "$out/provenance/android-readelf.txt"
"$tool/llvm-readelf" -h -l -n "$out/android/x86_64/libsoftware_aac_probe.so" > "$out/provenance/android-elf-headers.txt"
"$tool/llvm-readelf" -Ws "$out/android/x86_64/libsoftware_aac_probe.so" > "$out/provenance/android-symbols.txt"
mkdir "$out/source/host-build"; cd "$out/source/host-build"
# Host conversion is deliberately separate from the Android decoder.
# Raw PCM configure component names include pcm_; their CLI format is f32le.
host=("${common[@]}" --enable-ffmpeg --enable-ffprobe --enable-avcodec --enable-avformat --enable-avfilter --enable-swresample --enable-decoder=aac,pcm_f32le --enable-encoder=aac,pcm_f32le --enable-demuxer=mov,pcm_f32le --enable-muxer=mp4,pcm_f32le --enable-protocol=file --enable-filter=anull,aformat,aresample)
printf '%s\n' "${host[@]}" > "$out/provenance/host-configure-argv.txt"
cc --version > "$out/provenance/host-compiler.txt"
../ffmpeg/configure "${host[@]}"
cp config.h "$out/provenance/host-config.h"
cp config_components.h "$out/provenance/host-config_components.h"
cp ffbuild/config.mak "$out/provenance/host-config.mak"
make -j2
cp ffmpeg ffprobe "$out/host/"
"$out/host/ffmpeg" -version > "$out/provenance/host-version.txt"
"$out/host/ffmpeg" -buildconf > "$out/provenance/host-buildconf.txt" 2>&1
cd "$out"
find android host provenance -type f ! -name build.log -print0 | sort -z | xargs -0 sha256sum > binary-and-provenance.sha256
