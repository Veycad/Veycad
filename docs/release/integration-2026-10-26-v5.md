# V5 release-candidate integration evidence — 2026-10-26

Not release-ready. Source `8916a4c15ed7fea111c521c640ac74760fd3debc`, local
validation branch `codex/release-integration-v5-local` in
`D:/CodexWork/VeycadRuStore-20261025`. V4 plus PR #94's density follow-up
ca78edb and PRs #95 (scroll to Back), #96 (nonzero-origin fixture), #97
(backup viewport). No unreviewed timer experiments are included; no PR merged.

## Build and package checks

`clean baselineVerify assembleUiTest assembleUiTestAndroidTest assembleRelease`
passed in 8m20s. JVM: 1,392 tests, zero failures/errors/skips. Python: 338 passed.
Lint/security tasks passed. Log: `D:/CodexWork/veycad-integration-v5-build.log`.

All four release APKs contain exactly the 30 production license files with
bytes identical to source. Inventory and SHA-256 hashes:
`D:/CodexWork/veycad-v5-build-artifacts.json`.
Arm64 unsigned release: 220,746,254 bytes, SHA-256
`3e70ef47124afefa50af03ba10304c967500f9fa91cd996dc1ece73ae411d869`.
Package com.veycad.app, version 0.1.16/code 17, minSdk 26;
release LIVE_PREVIEW_ENABLED=true, GALLERY_MVP=false.
Signing and signed-update acceptance remain open.

## Additional V4 evidence informing V5 validation

These are V4-based device results, not a complete V5 device acceptance:
- Samsung at 320x480 dp: all six TextEditActivityDeviceTest cases passed in
  35.215s with unchanged text-editor code. Original display size/density restored.
  Log/XML: `D:/CodexWork/veycad-phone-narrow-text-v4.log` / `.xml`.
- Samsung style-video loop/cancel test passed in 8.092s. Log/XML:
  `D:/CodexWork/veycad-phone-style-loop-v4.log` / `.xml`.
  Both runs restored and hash-verified the 39 performance files.
- Same original V4 x86_64 APK (SHA-256
  `4dedf5235208f48a848aaec6d4b97727d7cb9fc0aafca656c0547f477cd1f262`)
  passed all six text-editor tests in 53.926s after restarting the dedicated
  API36 emulator with SwiftShader, at 320x480/density160/font1 and animations0.
  Log/XML: `D:/CodexWork/veycad-emulator-swiftshader-text-v4.log` / `.xml`.
  The restart also reset temporary read-only emulator state, so this is not
  proof that the GPU backend alone caused the earlier failures.

The 13-case SwiftShader graphics/style diagnostic was deliberately interrupted:
nine GLES cases completed PASS, the tenth was interrupted and three were not
started. Two captured stacks showed emulator graphics-pipe waits, first in
glGetUniformLocation and later glReadPixels; do not call that a proven deadlock.
The four unfinished cases then passed in a separate run on the same V4 APK
(4/4 in 150.798s, XML failures/errors/skips zero). Thus all 12 GLES cases and
the style-loop case have individual PASS evidence across two runs, not an
uninterrupted 13-case run. The interrupted first log remains preserved.
Evidence: `veycad-emulator-swiftshader-gles-style-v4.log`,
`veycad-swiftshader-gles-trace{,-second}.txt`, and
`veycad-emulator-swiftshader-gles-style-remaining-v4.log` under D:/CodexWork.

The original failed V4 full-run evidence was copied before cleaning the build:
`D:/CodexWork/veycad-v4-full-ui-archive/20261026-080232-613/` and its
`ui-tests.json`; exact V4 APKs/hashes are in
`D:/CodexWork/veycad-integration-v4-apks/`.

## Full V5 run

Started `tools/run_ui_tests.ps1 -Serial emulator-5556 -AvdName AutoEditUi_API36`
with explicit Python/SDK/JDK, using the current SwiftShader emulator. Log:
`D:/CodexWork/veycad-integration-v5-full-ui.log`. Run `20261026-100125-685`
completed: permissions 2/2 PASS; screens 267 executed, 266 PASS and one failure,
zero errors/skips/duplicates/missing/unexpected cases. XML totals match the
instrumentation report. Screens took 3671.332s (61m11s), excluding build and
permission setup. No full-run PASS claim: the runner exited 1. Its restart
phases were not run after the screen failure; separate follow-up results appear below.

All run evidence, including preview captures, logcat, XML and native AAC,
was copied to `D:/CodexWork/veycad-v5-full-ui-archive/20261026-100125-685/`;
the summary is `D:/CodexWork/veycad-v5-full-ui-archive/ui-tests.json`.

The active AOSP API36 emulator has no installed or uninstalled package entries
for Google Play Services, Play Store or any com.google package. Inventory:
`D:/CodexWork/veycad-v5-emulator-service-inventory.json`. The current full run
therefore exercised a no-GMS emulator environment; this is not physical Huawei
or other vendor-device acceptance.

## Exact V5 physical regression

Samsung SM-A256E / Android16 passed 28/28 selected tests in 266.969s, with zero
failures/errors/skips in the collected XML:
- GLES compositor: 12.
- Native draft preview: 4.
- Manual editor import/edit/export, 60fps lifecycle and large-font recovery: 3.
- Text editor: 6.
- Portable project backup/restore: 1.
- Native text export with concurrent-cleanup rejection: 1.
- Looping style-video preview/cancel: 1.

The installed base APK was hash-compared directly with the V5 arm64 UI artifact:
SHA-256 `8bd8cdb13e9c68fccd25a0d4f7e2d43783f1cf44512836c4e16ca099038efad4`,
224,705,927 bytes. Evidence: `D:/CodexWork/veycad-phone-v5-installed-apk.json`,
`veycad-phone-v5-regression.log` / `.xml`, and `veycad-phone-v5-preview-evidence/`.
All 39 previous performance files were restored and hash-verified. After the
run: original physical size 1080x2340 with no size override, density450 with
the original override420, font_scale1.0. No new camera recording was performed.
These are selected native/device regressions, not the full 267-case suite or
human visual/audio acceptance of final exported content.

## Independent AAC check of preserved V4 evidence

The existing `tools/verify_source_aac.py` passed against the preserved V4
`20261026-080232-613/screens/source-audio-native-evidence.tar`. All five
artifacts matched their device-recorded hashes before decoding. Native AAC
float decoding yielded 48,000 stereo sample frames; unclamped peak
0.8921767473220825 and longest full-scale run 0. All four 30/60fps boundary
files placed the markers exactly at samples 1600, 24000 and 46400.

Host tool: FFmpeg 9.0.2 essentials from the [Gyan Windows builds page](https://www.gyan.dev/ffmpeg/builds/),
kept outside the repository/application. Download archive SHA-256 matched the
provider checksum: `60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba`.
Tool version and execution log: `D:/CodexWork/ffmpeg-oracle-20261026/`.
Detailed result: `D:/CodexWork/veycad-v4-full-ui-archive/20261026-080232-613/screens/source-audio-host-oracle/result.json`.

This is V4 host AAC evidence only. It does not establish V5 acceptance,
Android float decoding (the recorded Android PCM encoding was 2), or human
listening. The completed V5 archive was independently checked below.

## Independent AAC check of V5

The same existing host oracle passed against the V5 run's own archived evidence.
All five files matched their device-recorded hashes; decoded mixed audio has
48,000 stereo sample frames, unclamped float peak 0.8921767473220825 and longest
full-scale run 0. All four boundary files have exact markers at samples 1600,
24000 and 46400. Mixed AAC SHA-256:
`18e9dbf882c62d2ecc263929d3aa3086b2ef80f7d024b0b928c29f9ec8b94b75`.
Report and decoded PCM:
`D:/CodexWork/veycad-v5-full-ui-archive/20261026-100125-685/screens/source-audio-host-oracle/`.
This confirms the host AAC checks for V5, not Android float decoding or human listening.

## CI follow-up and additional physical checks

The isolated PR heads have not all passed CI:

- PR #94, `ca78edb2b6fd1fe17a16bc58ebefa825a4de43a0`: baseline and legacy-save
  passed. [UI job](https://github.com/Veycad/Veycad/actions/runs/40293241625/job/121473450219)
  exceeded the one-hour limit; before cancellation it reported a recovery-export
  timeout and the old SmartFraming fixture invariant failure.
- PR #95, `4fb794dbf2b9904f3c42fd0324f987ffe7f8a5e7`: baseline and legacy-save
  passed. [UI job](https://github.com/Veycad/Veycad/actions/runs/40294766547/job/121478015529)
  was cancelled; retained output includes the old held-consumer lifecycle timeout
  and SmartFraming fixture invariant failure. No full UI pass is claimed.
- PR #96, `0100245e9cf240bea2c767520492849f0b0c4146`: baseline and legacy-save
  passed; UI was still running when inspected.
- PR #97, `f2c824f5db1065650cadd4bcd4aee9bcc7563a7c`: baseline passed.
  [Legacy-save job](https://github.com/Veycad/Veycad/actions/runs/40301181546/job/121499175822)
  failed before tests because Maven Central returned HTTP 429 while resolving
  dependencies. The requested single-job retry was not started: GitHub rejected
  it because the containing workflow was still running. After that run terminated,
  a single-job retry was accepted (HTTP 201) for the unchanged head. The
  [retry job](https://github.com/Veycad/Veycad/actions/runs/40301181546/job/121526270698)
  completed successfully. Baseline and legacy-save are now green for this head;
  the UI result remains cancelled. This does not establish complete PR acceptance.

On the exact installed V5 Samsung build, two additional selected regressions
passed in 61.299s, with XML confirming two cases and zero failures/errors/skips:
`ProjectRecoveryDeviceTest.explicitPressureReleasesActualPreviewAndRetryPreservesProjectAndClock`
and `SmartFramingDeviceTest.compactCacheUsesExactPhysicalIdentityAndCorruptionDoesNotReachReady`.
Evidence: `D:/CodexWork/veycad-phone-v5-ci-regression.log` / `.xml`.
The first exercised actual preview release/retry followed by native export;
the second exercised rejection of a corrupted physical cache entry. All 39
previous performance files were restored and hash-verified. Camera was not used.
These physical passes do not replace isolated PR CI or resolve the full V5 failure.

## Full-run failure and viewport comparison

The full V5 run `20261026-100125-685` reported a failure in
`ManualMvpScreenTest.importFourFormatsTrimUndoReopenAndLandscapeExportShareOneRevision`:
`Timed out: selected B` at line 80. That predicate requires both the selected
clip label and a ready paused preview; the log alone does not distinguish which
condition failed. The preceding click was recorded at 10:29:17 and the
assertion at 10:29:39 in the device log. Other MotionEvents oversleep warnings exist in the
log, but none was shown at that click, so do not attribute this failure to a
long press without a reproduction. Snapshot log:
`D:/CodexWork/veycad-v5-manual-selection-logcat.txt`. This was the only failure
in the completed 267-case screen phase; restart phases were not run.

The same unchanged V5 test passed on Samsung with the display temporarily set
to 320x480 dp, in 51.740s. The scenario covers selection of B, trim, reorder/undo,
reopening, landscape export and exact shared-file bytes. XML confirms one case
with no failures/errors/skips. Evidence: `D:/CodexWork/veycad-phone-v5-narrow-manual.log`
/ `.xml` and `-display-before.txt`. The original display size/density and all
39 performance-file hashes were restored. Thus a small viewport alone has not
reproduced the failure; the emulator failure remains open.

### Isolated emulator follow-up

The unchanged V5 case failed again in isolation (120.524s), but at a different
step: `Timed out: trim persisted`, line 82. Selection of B passed in this run.
Evidence: `D:/CodexWork/veycad-v5-manual-isolated.log` and `.xml`.
Both wait predicates also require preview readiness; neither timeout by itself
proves a failed selection or failed save.

The failed project's files were archived before further tests in
`D:/CodexWork/veycad-v5-manual-selection-observation/failed-project.tar`
(478,720 B; SHA-256
`07fe1a1bba5efaf7aa6869be7c4a94b303f7f68bde04707c05c47b3029a57a37`).
Targeted binary field inspection against the v9 writer found B spans [60,120)
in revisions 1–5 and [60,119) in revision 6; all six revision blob SHA-256 values
match their filenames. This establishes a stored trimmed revision, not a full
production-code decode or a live preview-state diagnosis. The root cause remains
open. Observer preferences recorded B selected; the screenshot was taken before
the timeout and cannot establish final state. The observer's subsequent
UI hierarchy dump exited 137 and provides no usable evidence.

### Separate process-restart checks

Atomic import restart PASS on unchanged V5: run
`fd4f5db7-1653-4b5b-9769-14fd62abe0a6`, both FILES_PREPARED and
MANIFEST_COMMITTED phases, each with a fresh process, two exact byte checks
and UI checks.

Project recovery run `6113be8b-ac61-4fe5-805f-afde1f2b8c55` FAIL at native
editor readiness. The process changed from PID 25126 to 25475 and six retained
project files matched exactly. The probe launches ManualMvpActivity with literal
`projectId`, whereas that Activity consumes `EXTRA_PROJECT_ID` =
`editable_project_id`. The captured UI shows the project list, not the requested
editor. The test-only fix uses the shared constant in draft [PR #99](https://github.com/Veycad/Veycad/pull/99),
commit `d4b1b92`; `compileUiTestKotlin` and `assembleUiTest` (x86_64) passed.
This defect is separate from the manual trim timeout.

The corrected isolated-main APK (not combined V5), SHA-256
`6ca9069a32606e3caa85c97995f2f783798b31310063f07200f8ef8f50f82dd5`,
opened the requested editor. Its compact 320x480 run still failed the host UI
readiness check: the captured visible hierarchy excluded the preview status.
At 320x960, run `10e6233f-06a0-47d9-a0a7-5b76f846e83d` PASS: PID 27247
changed to 27596, six exact file checks, retired scratch removal and native
paused preview. The emulator override was restored to 320x480. Evidence for
both runs is archived at `D:/CodexWork/veycad-recovery-intent-evidence/`.
Compact-viewport gate support and combined V5 rerun remain open; do not count
this isolated-main result as a complete V5 PASS.

Both reports and their full evidence archives were copied with SHA-256 comparison
to `D:/CodexWork/veycad-v5-full-ui-archive/separate-restarts/`.

## Store-screen draft inspection

Local captures and their dimensions/hashes are inventoried in
`D:/CodexWork/veycad-v5-store-screen-drafts/manifest.json` (1080x2340).
Main-screen and style-selector views are draft candidates. The empty text-editor
view is rejected as a final marketing screenshot until it shows permitted demo
material. These are UI-test APK captures, not signed-release store assets;
retake on the final APK. No screenshots or personal media were published.

A populated text-editor draft is now available as `06-text-demo.png` in that
folder. The bundled silent Heartbeat preview was imported through the system
picker, the title `Dance demo` entered through the title form, and MP4 exported
through the normal UI. Preview provenance: `docs/design/style-previews.md`,
Pexels 8853811 / Los Muertos Crew; [Pexels terms](https://www.pexels.com/license/)
rechecked 2026-10-26. This does not imply endorsement by the depicted person.

The actual output (`text-demo-export.mp4`) decoded to 90 H.264 frames,
320x568, 3.000s; AAC stream duration 3.008s. A frame extracted at 0.8s was
visually inspected and contains the title over the demo video. Size 471,502 B,
SHA-256 `3c56b1c71265e36632ef2a3fa8dd413bebf9f300894850fd45c8f1196f754fbd`.
Metadata, extracted frame and provenance/hashes remain beside the draft captures.
The temporary named demo copy in Downloads was removed after import/export;
personal files were not selected. This verifies a manual title workflow on V5,
not speech recognition, a signed release, final store graphics or human audio acceptance.

## Open acceptance

Complete V5 UI/native/restart regression, signed updates, no-GMS,
TalkBack, rear-camera/orientation, capture-to-montage export and human
visual/audio review remain open. Account/key, public privacy-policy URL,
final store screenshots and unresolved third-party media provenance remain
release prerequisites in the release plan. A build pass is not submission approval.
