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

## Full user-flow verification is running

Run `20261102-013300-208` started on the dedicated API36/x86_64 emulator with host
NVIDIA graphics, 320x480/density160/font1.0 and 4096-byte pages. Permission cases
passed; the full screen suite is running. There is no full V11 verdict yet.
The runner receives an explicit working Python executable for AAC collection.

Log: `D:/CodexWork/veycad-v11-full-host-ui-20261102.log`.
Environment: `D:/CodexWork/veycad-v11-full-host-environment-20261102.json`.
Archive: `build/reports/ui-tests/runs/20261102-013300-208`.

## Still open

The [V10 preview freeze investigation](integration-2026-11-02-v10.md) remains
open: the original APKs passed after restarting the emulator in both graphics
modes. A short fresh-start pass does not resolve the earlier full-run failure.
PR #107's isolated text passes must also hold in this combined candidate.

No V11 recovery, physical-device, 16 KB runtime, human media, signed update or
store acceptance is inferred from the build. Account, permanent key, media
rights, final store images, public policy and owner approval remain publication
dependencies. No PR merge or store submission occurred.
