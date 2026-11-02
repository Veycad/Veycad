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

## Acceptance boundaries

The 200% font check belongs to isolated PR #105 (2.603s); it was not repeated
on V10. V9 native export/speech checks and 16 KB static inspection retain their
original APK/source scope. No new full UI run, 16 KB runtime, physical-device,
human media or signed-update acceptance is inferred. The SDK image download
was still active and incomplete at this checkpoint.

V10 remains a local integration branch. No merge, permanent signing, store
submission or owner approval occurred. Account/key, media rights, final images,
public policy and device/media acceptance still prevent publication.
