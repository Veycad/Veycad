# V11: text preview fix and retained runtime notices

V11 is a local, unmerged integration candidate at
`fca633adb0eb863562cfe8fe38b18a475fc95c41`, branch
`codex/release-integration-v11-local`. It combines V10 with draft
[PR #106](https://github.com/Veycad/Veycad/pull/106) (runtime notice texts) and
[PR #107](https://github.com/Veycad/Veycad/pull/107) (unchanged paused text time
does not trigger redraw). No preview-loop workaround was retained.

## Completed build and package checks

`baselineVerify :app:assembleRelease :app:assembleUiTest :app:assembleUiTestAndroidTest`
passed offline in 7m21s. All 1392 JVM cases and 338 Python cases passed, with no
failures, errors or skips. Baseline also includes debug lint and the release
manifest/security contract.

All four unsigned release APKs passed independent inspection: application ID
`com.veycad.app`, version 0.1.16/code17, min26/target36, non-debuggable, manual
editor enabled, no INTERNET or ACCESS_NETWORK_STATE permission, expected native
ABIs with Whisper, expected speech model bytes and retained license files.
Both new runtime notice texts and their index match source bytes in every APK.
The [notice retention check](notices-v11-2026-11-02.json) also inspected root
AAR/JAR and nested classes.jar candidates in all 119 unchanged dependency
archives. All 34 nonempty notice texts are contained in packaged license assets;
four source entries are empty and 19 are structured metadata. License asset bytes
match across all four APKs. This does not cover native/deeper archives or establish
complete license compliance.
Signature verification rejected these unsigned APKs as expected; no successful
release signing is claimed.

Universal APK SHA-256:
`4c4a8788da0c2d1f5abc89f3ccc8ffe3d0c8ae64d3a0b817db2e7df771826245`.
Per-APK identities: `D:/CodexWork/veycad-v11-release-20261102/receipt.json`.
Build/test inputs: `D:/CodexWork/veycad-v11-build-inputs-20261102.json`.
Build log: `D:/CodexWork/veycad-v11-baseline-build-retry-20261102.log`.
The first invocation stopped before checks because it named a nonexistent task;
the corrected invocation above is the successful run.

## Full user-flow verification failed with an Android system crash

Run `20261102-013300-208` started on the dedicated API36/x86_64 emulator with host
NVIDIA graphics, 320x480/density160/font1.0 and 4096-byte pages. Both permission
cases passed. The screen run started 243 of 270 expected cases before aborting;
27 never started. Raw instrumentation reported 239 success statuses and four
failure statuses (three assertion failures and a process crash). These are raw
status counts, not a valid completed JUnit XML suite. The screen XML is invalid
after the system crash, and the final runner report contains only the valid
permission phase. The overall verdict is FAIL/incomplete.

- `GlesFrameCompositorDeviceTest.same_scheduled_frame_draws_same_composition_on_display_and_encoder`:
  pixel arrays first differed at byte 412228 (1 versus 0), before compression.
- `StyleVideoPreviewScreenTest.a_manual_preview_keeps_moving_after_one_loop_and_releases_the_view_on_cancel`:
  preview pixels froze again after the longer preceding run.
- `TextEditActivityDeviceTest.mainAndCompletedVideoOpenSameEditor`:
  ActivityScenario could not close the activity; its last state was PAUSED.
- During `pausedPreviewStopsRedrawingButSeekAndPlaybackStillUpdateTextTime`,
  ReferenceQueueDaemon timed out destroying a HardwareRenderer context. This
  crash does not establish a failed paused-draw measurement in that case.

The system_server Watchdog then killed Android's system process: window/display
threads were blocked, with the animation thread inside native layer capture for
a task snapshot. Android reported `INSTRUMENTATION_ABORTED: System has crashed`;
the settings service was unavailable. Host emulator logs also record missing
ColorBuffer handles and a bad window surface handle. These observations do not
establish whether the root cause belongs to app cleanup, emulator graphics or
their interaction. No tolerance was added to the pixel comparison.

Restart/recovery follow-up phases were not run because the screen gate failed.
Preview evidence could not be pulled after the system crash. Original app and
test APKs were preserved with the recorded hashes before diagnostic work.

Log: `D:/CodexWork/veycad-v11-full-host-ui-20261102.log`.
Environment: `D:/CodexWork/veycad-v11-full-host-environment-20261102.json`.
Archive: `build/reports/ui-tests/runs/20261102-013300-208`.
Terminal receipt: `D:/CodexWork/veycad-v11-full-host-result-20261102.json`.
Host logs: `D:/CodexWork/veycad-v11-host-at-failure-20261102.stdout.log` and
`D:/CodexWork/veycad-v11-host-at-failure-20261102.stderr.log`.

After this terminal crash and evidence retention, the dedicated emulator was
restarted in the same host graphics mode. The original, hash-matched V11 app and
instrumentation APKs passed the compositor parity case and the moving style
preview case together (2/2, 10.234s). This fresh-start result does not resolve
the longer-run failures or validate the 27 cases that never started.
Log: `D:/CodexWork/veycad-v11-original-focused-postcrash-20261102.log`.

## Retained AAC verification passed

The explicit Python executable allowed collection and archive validation before
the Android system failure prevented later pulls. Retained tar SHA-256:
`2eadca5605932659192175d61087bee823d59163f95094660d775f6ceea2389e`.
Independent FFmpeg float decoding passed: 48000 sample frames; all four authored
30/60 fps audio/container marker sets were at samples 1600, 24000 and 46400;
unclamped peak 0.89218 and no full-scale run. Android reported PCM encoding 2.
This is not Android float-output or human listening acceptance.
Result: `build/reports/ui-tests/runs/20261102-013300-208/screens/source-audio-host-oracle/result.json`.
Log: `D:/CodexWork/veycad-v11-aac-host-oracle-20261102.log`.

## License export verified through the UI

The original V11 app and instrumentation APKs passed
`LicenseExportScreenTest.savesOnlyBundledLicensesIncludingNestedAndEmptyFiles`
(1/1, 3.930s). The resulting ZIP was retained and independently compared with
every `assets/licenses/` file in that exact app APK: all 35 names and byte
contents match, with no duplicates or extra entries. The three new runtime
notice/index files are included. This UI variant also contains export test
fixtures; the check is not a signed release UI or media-rights acceptance.

ZIP SHA-256: `6763658685a6e9308871e5da117b002ca9fb5f89f47714d566750c5c33281958`.
Receipt: `D:/CodexWork/veycad-v11-exported-license-check-20261102.json`.
UI log: `D:/CodexWork/veycad-v11-original-license-export-20261102.log`.

## Still open

The [V10 preview freeze investigation](integration-2026-11-02-v10.md) remains
open: the original APKs passed after restarting the emulator in both graphics
modes. The freeze recurred in the full V11 run. A short fresh-start pass does not
resolve it. PR #107's isolated text passes also do not close combined acceptance:
the V11 text class was interrupted by the graphics/system crash.

No V11 recovery, physical-device, 16 KB runtime, human media, signed update or
store acceptance is inferred from the build. Account, permanent key, media
rights, final store images, public policy and owner approval remain publication
dependencies. No PR merge or store submission occurred.
