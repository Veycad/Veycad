# V6 combined release validation

Status: running with at least one observed screen failure; not release-ready.
No PR merged and no store submission approved.

Source: `6ce47f5d4fa278c19488a2c8c0d778638095f9a8`, local branch
`codex/release-integration-v6-local` in `D:/CodexWork/VeycadRuStore-20261025`.
It combines V5 (`8916a4c`) with the four native-preview wait corrections from
[PR #100](https://github.com/Veycad/Veycad/pull/100), recovery intent/compact
viewport corrections from [PR #99](https://github.com/Veycad/Veycad/pull/99),
and the full CI job time budget from [PR #98](https://github.com/Veycad/Veycad/pull/98).
The four-file diff contains tests, the recovery host script and CI configuration;
`app/src/main` and `app/build.gradle.kts` are unchanged from V5. This does not
transfer exact-binary acceptance to a newly built artifact.

## Clean build PASS; full UI run in progress

Clean `baselineVerify`, UI APK/instrumentation and unsigned release assembly
passed in 8m43s (196 tasks). All 1,392 JVM tests passed with zero failures/errors/
skips across 189 XML suites; all 338 Python tests passed. Lint/security/build
checks completed through the baseline task. Log:
`D:/CodexWork/veycad-integration-v6-build.log`.

Generated release BuildConfig has DEBUG=false, LIVE_PREVIEW_ENABLED=true and
GALLERY_MVP=false. aapt confirms `com.veycad.app`, version 0.1.16/code17,
minSdk26/targetSdk36 and arm64-v8a for the arm64 package, without application-debuggable.
The embedded SOURCE_SHA remains `local-unverified`; source identity is recorded
by this receipt, not claimed to be embedded. These APKs are unsigned and have not
passed signed-release install/update acceptance.

All four release APKs contain the exact 30 production license files from source.
Hashes and UI APK identities: `D:/CodexWork/veycad-v6-build-artifacts.json`.

| Release APK | Bytes | SHA-256 |
| --- | ---: | --- |
| app-arm64-v8a-release-unsigned.apk | 220,746,254 | `8d71101a6c6b5e50ef43c0164fd21bb97881ea2e6f8e456813a9ff44ff5d177c` |
| app-armeabi-v7a-release-unsigned.apk | 209,875,772 | `86ba33bb01b6e6a3dc4f6f41ccc8375cbf6e7d9f60a4c502a3d6561bbc399805` |
| app-universal-release-unsigned.apk | 318,213,150 | `e4c491876f8b63ee62816d85c9cba0bfb7ee566bba95db90758304a09c791fa5` |
| app-x86_64-release-unsigned.apk | 225,953,265 | `88123ee7692c588bde3cc53fc518255ea7e2ec1405c7fd9adee64ee621752951` |

The full `run_ui_tests.ps1` has started on dedicated API36 AOSP emulator
`emulator-5556` / `AutoEditUi_API36`, using explicit JDK/SDK/Python paths.
Live log: `D:/CodexWork/veycad-integration-v6-full-ui.log`. Its terminal result
is still pending; the clean build is not a full UI/native/restart PASS.

Before cleaning, 545 previous report/artifact files (804,460,595 bytes) were
preserved under `D:/CodexWork/veycad-v5-before-v6-build/`, with a SHA-256 manifest.
Earlier full V5 evidence and standalone diagnostic/fix runs remain in the
[previous receipt](integration-2026-10-26-v5.md).

## Failure observed during the running suite

Run `20261026-115601-245`, case 75:
`DraftPreviewControllerDeviceTest.directSurfaceFirstReverseAndSourceChangePreserveInputsWithoutPreviewFile`
failed at its initial `awaitFrame(0, ...)`: no exact ready frame before the
15-second deadline. Captured states had no reported controller error; the worker
stack was in `GLES20.glGetAttribLocation`, through `EglRenderTarget.preparePresentation`
and native frame composition. This is a different test from PR #100's manual UI
scenario, and its root cause is not established by this stack alone.

The same case passed in the previous full V5 XML (whole-case time 29.388s).
That result does not override the V6 failure. A focused repeat must wait until
the currently running suite releases the emulator; no concurrent instrumentation
or timeout change has been applied to this running candidate.

An immutable excerpt, logcat snapshot and hashes are saved at
`D:/CodexWork/veycad-v6-first-frame-failure/`. The suite continued beyond case 97;
final totals and subsequent phases remain unknown. This snapshot is not a
completed full-run receipt. The owner deferred reconnecting the Samsung, so
physical V6 follow-up is pending device availability.

## Prior supporting evidence

- V5 full UI: permissions 2/2, screens 266/267; one manual-preview wait failure.
- V5 plus PR #100: complete manual scenario 1/1 PASS in 358.117s at 320x480,
  including native export, exact shared bytes, reopen and rotation.
- Isolated-main PR #99: process recovery PASS at both 320x960 and 320x480;
  fresh process, six unchanged files, retired scratch and native paused preview.
- PR #98 (`427ce9f`), #99 (`b219e23`) and #100 (`95340e6`) exact-head
  baseline/legacy-save CI passed. PR #99/#100 UI jobs remained running at the
  latest check. The earlier snapshot is `D:/CodexWork/veycad-v6-ci-checkpoint.json`.
- PR #98 UI job [121531142216](https://github.com/Veycad/Veycad/actions/runs/40310783117/job/121531142216)
  subsequently finished FAIL: all 261 cases executed, eight failures, zero errors/
  skips, 3623.291s instrumentation time. It was no longer cut off at one hour.
  This branch contains only the CI time-budget change atop main, not combined V6.
  Its eight failing cases were compared by name against the archived full V5 XML:
  seven passed there; the manual trim/export scenario failed there at a different
  wait, then passed on V5 plus #100. This comparison is supporting evidence,
  not an assertion that PR #98 or V6 is green. Saved comparison and CI excerpt:
  `D:/CodexWork/veycad-ci98-v5-comparison.json` and
  `D:/CodexWork/veycad-ci98-ui-log-excerpt.txt`.

These are scoped results, not a replacement for the combined V6 run.

## Remaining release acceptance

Signed final APK, install/update preservation, physical no-GMS acceptance,
rear-camera/orientation and capture-to-montage acceptance, TalkBack and human
visual/audio review remain open. Developer account/permanent key, final store
assets, public privacy URL and third-party music rights remain unresolved.
The owner has not approved merges or store submission.
