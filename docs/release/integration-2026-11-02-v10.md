# V10 integration checkpoint, 2026-11-02

V10 adds [PR #105](https://github.com/Veycad/Veycad/pull/105), the manual editor's
empty-project prompt, to [V9](integration-2026-11-02-v9.md). Applied cleanly as
`31bfb6842c8e923a7e5ed524fb54f703b7b65870` on local branch
`codex/release-integration-v10-local`; owning PR source is `6956f1e`.
An empty editor invites adding video; reopened projects keep their saved-draft
prompt. Explicit operation and recovery messages retain priority.

## Fresh checks

- Release/UI app/instrumentation assembly and `:app:verifySecurityContract`
  passed with `-PsourceSha=31bfb6842c8e923a7e5ed524fb54f703b7b65870` in 47s
  (61 tasks executed, 74 up-to-date). Release lint vital passed.
- All four unsigned release APKs passed package/version/SDK, non-debuggable,
  offline permission, exact ABI, native Whisper, model hash and license checks.
  Generated release config enables the manual editor and records this source.
  `apksigner verify` rejected unsigned artifacts as expected; no key was used.
- The combined test app and instrumentation installed on API36/x86_64 emulator
  `emulator-5556`. Empty-editor and imported/reopened-project prompt cases both
  passed in 33.018s. This is a focused UI check, not full editor acceptance.

Build log: `D:/CodexWork/veycad-v10-build-20261102.log`.
Package hashes/sizes: `D:/CodexWork/veycad-v10-release-20261102/receipt.json`.
Universal release SHA-256:
`211629c0777fb9e450836326b9e1d68b8098f625fec52a4fdbb2cfea2289588f`.
Test APK/log identities: `D:/CodexWork/veycad-v10-prompt-tests-20261102.json`.

## Bundled model and notice preservation follow-up

The four release APK hashes above were revalidated. All 31 source model/license
assets inspected match their packaged bytes in every APK. This includes the
Selfie Multiclass model with expected SHA-256
`c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0` and the
packaged license/attribution files. The generated Whisper model was checked
separately during the package inspection above.

The bundled Google notice index covers 19 recorded artifact coordinates.
All referenced notice file sizes and SHA-256 hashes match; its 1083 indexed
entries have valid text byte ranges and recorded counts. This checks preservation
and internal integrity of the existing notice inventory, not completeness against
the entire resolved dependency graph or permission to distribute music.

Receipt: `D:/CodexWork/veycad-v10-notice-package-check-20261102.json`.

## Full UI run started; no result yet

Run `20261102-001132-799` is executing on the dedicated API36/x86_64 emulator
with NVIDIA RTX3060 host graphics, 320x480/density160/font1.0 and 4096-byte pages.
The two permission cases passed; the remaining 269 cases were still running at
this checkpoint. No full-suite verdict is recorded. Source is unchanged.

Environment: `D:/CodexWork/veycad-v10-full-host-environment-20261102.json`.
Live runner log: `D:/CodexWork/veycad-v10-full-host-ui-20261102.log`.
This renderer-specific execution does not erase earlier SwiftShader failures.

## Acceptance boundaries

The 200% font check belongs to isolated PR #105 (2.603s); it was not repeated
on V10. V9 native export/speech checks and 16 KB static inspection retain their
original APK/source scope. No completed new full UI run, 16 KB runtime, physical-device,
human media or signed-update acceptance is inferred. The SDK image download
was still active and incomplete at this checkpoint.

V10 remains a local integration branch. No merge, permanent signing, store
submission or owner approval occurred. Account/key, media rights, final images,
public policy and device/media acceptance still prevent publication.
