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

## Shorter graphics reproduction retained

On the same V11 app, a diagnostic instrumentation APK ran the compositor and
style-preview classes (15 cases, 20.693s). It retained the original strict
assertions and failed rotation consistency and display/encoder parity. Saved
RGBA arrays show identical pixels without the glow pipeline, then ten different
pixels with it: red and alpha differ near x90..92/y160..164, with a maximum
unsigned byte difference of 255. Both targets report RGBA8 and enabled dithering;
this does not identify dithering as the cause.

Restoring the original instrumentation APK also reproduced the rotation failure
in the same 15-case selection (20.467s). Five isolated repetitions of that
rotation case subsequently passed. A second diagnostic APK, which additionally
saves rotation pairs, passed three 15-case selections. These intermittent
results do not establish a fix or a synchronization conclusion. No production
code or comparison tolerance changed. The original instrumentation APK was
restored before checking the classes interrupted by the full-run crash.

Receipt: `D:/CodexWork/veycad-v11-frame-parity-investigation-20261102.json`.
Raw captures: `D:/CodexWork/veycad-v11-frame-parity-probe-captures-20261102`.

## Previously interrupted classes passed separately

The original V11 app and original instrumentation APKs then passed all 29 cases
in `TextEditActivityDeviceTest` (7), `TextExportDeviceTest` (4),
`TextLayoutDeviceTest` (3), `VideoDecoderCursorDeviceTest` (10) and
`WhisperDeviceTest` (5), in 273.195s. The 27 cases that never started in the full
run are all included. Installed APK hashes were read from the emulator and
matched the preserved originals. The new paused-redraw case and editor closure
also completed successfully in this selection.

This is separate verification after the emulator restart, not a completed full
suite or a fix for the earlier graphics/system failures.
Receipt: `D:/CodexWork/veycad-v11-unfinished-classes-receipt-20261102.json`.
Log: `D:/CodexWork/veycad-v11-original-unfinished-classes-20261102.log`.

## Separate process-restart and project recovery gates passed

With the same installed original V11 app, both existing host gates passed:

- Import restart: force-stop at FILES_PREPARED and MANIFEST_COMMITTED, with a
  different process after each restart; four exact source-file byte/hash checks
  and restored selection/order/ready controls verified.
- Project recovery: a different process reopened the same project; six retained
  project files matched their exact byte counts and hashes, retired scratch was
  removed, and the real editor reached paused native preview.

These are controlled force-stops in the dedicated API36 emulator. They do not
prove OEM low-memory killing, signed updates, phone behavior or recovery from
the earlier Android system crash.
Retained reports: `D:/CodexWork/veycad-v11-atomic-import-restart-20261102.json`
and `D:/CodexWork/veycad-v11-project-recovery-20261102.json`.
Run archives: `build/reports/ui-tests/restart/55a8534d-2512-418d-9a93-9707ecfd84e4`
and `build/reports/ui-tests/project-recovery/dd1874a1-f955-401d-9cdd-497d1ed9c252`.

## Still open

The [V10 preview freeze investigation](integration-2026-11-02-v10.md) remains
open: the original APKs passed after restarting the emulator in both graphics
modes. The freeze recurred in the full V11 run. A short fresh-start pass does not
resolve it. The V11 text class passed separately after the restart, but its
earlier interruption by the graphics/system crash still prevents full-run
acceptance.

No physical-device, 16 KB runtime, human media, signed update or
store acceptance is inferred from the build. Account, permanent key, media
rights, final store images, public policy and owner approval remain publication
dependencies. No PR merge or store submission occurred.
