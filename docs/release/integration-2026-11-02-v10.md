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

An offline `:app:dependencies --configuration releaseRuntimeClasspath` run
also passed. All 19 coordinates recorded in the Google notice inventory match
the resolved release versions. The graph contains 126 distinct coordinates;
this comparison does not establish notice/license completeness for that graph.
Receipt: `D:/CodexWork/veycad-v10-notice-dependency-check-20261102.json`.
Dependency report: `D:/CodexWork/veycad-v10-release-dependencies-20261102.log`.

## Completed full UI run: three failures

Run `20261102-001132-799` completed on the dedicated API36/x86_64 emulator
with NVIDIA RTX3060 host graphics, 320x480/density160/font1.0 and 4096-byte pages.
The two permission cases passed. All 269 screen cases executed in 2728.025s:
266 passed, three failed, with no errors or skips. Source was unchanged.

- `StyleVideoPreviewScreenTest.a_manual_preview_keeps_moving_after_one_loop_and_releases_the_view_on_cancel`:
  two sampled texture signatures matched after the first cycle. Live looping
  versus sampling/decoder behavior remains under investigation.
- `TextEditActivityDeviceTest.editStylesCuesRestoreDraftCancelAndPublishNewMp4`:
  main-loop idle timeout while opening the font choice.
- `TextEditActivityDeviceTest.richAppearanceUnicodeValidationAndExplicitCaptionReview`:
  save-click failed with the same main-loop idle timeout.

The earlier direct first/reverse/source-change frame case passed in this run.
The full result is still FAIL; a pass on host graphics does not close phone
performance or erase the earlier SwiftShader failure. Restart/recovery follow-up
phases gated on a successful screen suite were not run.

Environment: `D:/CodexWork/veycad-v10-full-host-environment-20261102.json`.
Runner log: `D:/CodexWork/veycad-v10-full-host-ui-20261102.log`.
Summary: `D:/CodexWork/veycad-v10-full-host-result-20261102.json`.
Authoritative XML: `build/reports/ui-tests/runs/20261102-001132-799/screens/tests.xml`.

## Independent retained AAC check

The collection script could not launch its Python validator because this host's
`python` command resolves to the Windows Store alias. Its error record was retained.
Before any subsequent app install/test, the exact-run isolated app evidence was
recollected as a binary tar and validated with the installed Codex Python.
The original UI run remains failed; this recovery is not a rerun of its tests.

The independent FFmpeg float decoder passed the retained AAC check: 48000 decoded
sample frames, all four 30/60 fps audio/container boundary marker sets at their
authored samples, unclamped peak 0.89218 and no full-scale run. Android reported
PCM encoding 2; no Android float-output or human listening acceptance is inferred.
Result: `build/reports/ui-tests/runs/20261102-001132-799/screens/source-audio-host-oracle/result.json`.
Log: `D:/CodexWork/veycad-v10-aac-host-oracle-20261102.log`.

## Follow-up fixes and preview diagnosis

The text idle failures have an isolated fix in draft
[PR #107](https://github.com/Veycad/Veycad/pull/107), commit
`f7f38f08149ef8b42d18a44293e69cc25f0a92c1`: paused playback no longer redraws
unchanged text time. With identical instrumentation, the original app failed
the new paused-draw regression (10 draws in one second); the fixed app passed
while retaining seek and playback updates. All seven text device cases passed,
including both cases that failed on V10. `baselineVerify` also passed.
Receipt: `D:/CodexWork/veycad-text-paused-receipt-20261102.json`.
These are owning-branch results, not a successful combined V10 rerun.

Draft [PR #106](https://github.com/Veycad/Veycad/pull/106), commit
`0083768dd4f20cd5334d89be740acf8be52fd984`, preserves two missing dependency
notice texts and their provenance. Its four unsigned release APKs contain the
original bytes. This does not establish complete license compliance or resolve
third-party music rights. Receipt:
`D:/CodexWork/veycad-runtime-notices-package-check-20261102.json`.

The style preview failure reproduced separately on the original V10 app.
Test-only probes showed a fixed SurfaceTexture timestamp and identical pixels
while the player position advanced. Early samples showed the same freeze before
the first loop; the failure is not established as an automatic-loop defect.
Replacing automatic looping with a completion restart did not help and was
reverted. Window/frame delivery on the host emulator remains under investigation;
no preview production fix or physical-device verdict is claimed.
Logs: `D:/CodexWork/veycad-style-early-frame-clock-20261102.log` and
`D:/CodexWork/veycad-style-explicit-loop-test-20261102.log`.

The original V10 app and instrumentation APKs then passed all three style-preview
cases on freshly started emulators in both modes: SwiftShader (13.484s) and host
NVIDIA (13.747s). A graphics-specific cause is therefore not established. Restart
clears the observed failure; accumulated emulator state or earlier test activity
remains to be investigated through combined verification. The original full-run
failure remains recorded. Receipt:
`D:/CodexWork/veycad-v10-style-preview-diagnosis-20261102.json`.

Local V11 combines V10 with PR #106 and PR #107. Its build, baseline and release
security checks have started; no V11 verdict is available at this checkpoint.

## Remaining acceptance boundaries

The empty-editor prompt's focused 200% font check belongs to isolated PR #105
(2.603s); that exact check was not repeated on V10. V9 native export/speech
checks and 16 KB static inspection retain their
original APK/source scope. The completed V10 screen run failed as described above.
No 16 KB runtime, physical-device, human media or signed-update acceptance is
inferred. The SDK image download
was still active and incomplete at this checkpoint.

V10 remains a local integration branch. No merge, permanent signing, store
submission or owner approval occurred. Account/key, media rights, final images,
public policy and device/media acceptance still prevent publication.
