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

## Still open

No V9 installation, runtime, output review, signed update, TalkBack or physical
device acceptance was performed. Permanent signing, account setup, media rights,
final store images and public policy remain publication dependencies. Passing
package inspection does not settle codec, camera or subtitle inference behavior.
