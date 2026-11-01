# V9: unsigned release packaging, 2026-11-02

## Result

The combined unsigned release builds and passes package inspection. This is
not a signed or device-accepted release. No merge or publication occurred.

V9 is V8 plus [PR #104](https://github.com/Veycad/Veycad/pull/104), applied as
`09f65bcb2107beea064149cfa842f54ca98a6e97` on local branch
`codex/release-integration-v9-local`. The owning PR commit is `51aeb0f`.
Earlier runtime results remain scoped to their original builds; see
[V7/V8](integration-2026-11-01-v7.md).

## Defect found before the change

The exact-source V8 universal APK included five dependency JNI libraries under
`lib/x86/`, without `lib/x86/libveycad_whisper.so`. Its packaging assertion failed.
The three ABI-specific APKs contained their intended ABIs. Split configuration
did not exclude dependency x86 libraries from the universal package.

PR #104 excludes `lib/x86/**` from JNI packaging. It preserves arm64-v8a,
armeabi-v7a and x86_64, for which the app builds its own native libraries.
This prevents advertising an incomplete x86 native payload. No physical x86
installation or inference failure was reproduced.

Before-change evidence: `D:/CodexWork/veycad-v8-release-20261102/abi-failure.json`.
The affected V8 universal SHA-256 was
`e6ec61882e3bcfcf6ec1d451dd8b2df5d40349f8cbdadcff05adc88d8a183ed4`.

## Checks

- Isolated PR branch from main: `:app:assembleRelease` passed in 3m10s;
  all 58 tasks executed. Archive assertions passed on all four APKs.
- Combined V9: `:app:assembleRelease :app:verifySecurityContract
  -PsourceSha=09f65bcb2107beea064149cfa842f54ca98a6e97` passed in 34s
  (24 executed, 35 up-to-date). Release lint vital also passed.
- All four V9 packages: application ID `com.veycad.app`, version 0.1.16/code17,
  minSdk26/targetSdk36, no debuggable flag, no INTERNET/ACCESS_NETWORK_STATE
  permissions. Generated release BuildConfig has DEBUG=false, manual editor
  enabled and the exact V9 source SHA.
- Universal contains exactly the three supported ABIs; each separate APK
  contains its declared ABI. Whisper JNI is present for every packaged ABI.
- All four include the expected Whisper model SHA-256 and both Whisper license
  files. `apksigner verify` rejects each as unsigned, as expected; no key was used.

Logs: `D:/CodexWork/veycad-abi-release-build-20261102.log` and
`D:/CodexWork/veycad-v9-release-build-20261102.log`.
Package hashes/sizes and inspection receipt:
`D:/CodexWork/veycad-v9-release-20261102/receipt.json`.
Universal SHA-256:
`be4f8986c706d46ebbcdfd72114de8ad9386182d7c72dcc58af8a2def57e8711`.

## Native payload and 16 KB static follow-up

The four artifact hashes above were revalidated before inspection. No rebuild
or source change occurred for these checks.

- `zipalign -c -P 16 -v 4` passed for all four APKs using Build-Tools 36.0.0.
- All 14 arm64-v8a/x86_64 shared libraries in the universal APK have every
  LOAD segment aligned to at least 16384 bytes (`llvm-readelf -lW`, NDK28.2).
- All 14 RELRO layouts passed inspection: five have a 16 KB aligned end;
  nine occupy their own complete LOAD segment, with no other writable LOAD
  overlapping the protection range rounded to 16 KB pages. A nonzero end
  remainder alone is not a failure in that isolated layout, as explained in
  [Android's RELRO guidance](https://developer.android.com/guide/practices/page-sizes#relro).
- The universal contains seven native libraries for each of its three ABIs.
  Every native library in each separate ABI APK matches the corresponding
  universal entry byte-for-byte (21 SHA-256 comparisons).

Evidence: `D:/CodexWork/veycad-v9-alignment-20261102/receipt.json`, with per-library
headers and per-APK zipalign output in the same directory. These are static
package/ELF results. They do not prove startup, allocation behavior, inference
or export on a system actually running with 16 KB pages. No 32-bit ELF 16 KB
claim is made.

## Fresh V9 API36 runtime follow-up

The same source was built as the debug-signed isolated `uiTest` application and
instrumentation APKs (15s; 21 tasks executed, 56 up-to-date). Both installed on
`emulator-5556`, API36/x86_64, with `getconf PAGE_SIZE` confirmed as 4096.
The release APKs inspected above remain unsigned and were not installed.

Three tests passed in 78.258s:

- `NativeExportSmokeTest.nativeMontageExportsDecodableAvcAndAac`: synthetic
  96x144, one-second AVC/AAC export; actual samples, frame timestamps, decoded
  frame colors and audio tone are checked. This is not a 1080p quality review.
- `WhisperDeviceTest.actualBundledMultilingualModelRecognizesEnglishAndSilenceStaysEmpty`:
  bundled model recognizes the expected English speech word, with valid cue
  bounds; silence produces no subtitles.
- `WhisperDeviceTest.automaticLanguageRecognizesRussianSpeech`: expected Russian
  speech word, cue bounds and backend evidence, including an attenuated fixture.
  Expected-word assertions do not establish general transcription accuracy.

Evidence: `D:/CodexWork/veycad-v9-native-api36-20261102.json` binds source,
application/test APKs, runner log and emulator identity. Build and test logs:
`veycad-v9-16kb-build-20261102.log` and `veycad-v9-native-api36-20261102.log`
in `D:/CodexWork`. The build-log name reflects the intended next environment;
this actual execution used 4 KB pages.

A separate SDK Manager operation is downloading the official API35 Google APIs
16 KB x86_64 image. At this checkpoint it is incomplete; no 16 KB emulator was
created, booted or tested. Do not infer a runtime pass from package availability.

## Still open

No signed-release installation, human output review, signed update, TalkBack,
16 KB runtime or physical device acceptance was performed. Permanent signing, account setup, media rights,
final store images and public policy remain publication dependencies. Passing
package inspection does not settle codec, camera or subtitle inference behavior.
