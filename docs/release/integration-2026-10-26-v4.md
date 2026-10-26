# Integration V4 — validation in progress

Local source: `472a38e9f465e8561e3d809401eccb941d3632d3`, branch
`codex/release-integration-v4-local`, based on V3 `b9105cdc3bf5b96b8439a10bcc23730141179693`.
Adds draft PRs #89–94: MediaPipe/Google notices, license export, manual editor
release flag, cache fixture consistency and display override restoration.

Two cherry-pick conflicts were resolved by preserving both additions:
- `.gitattributes`: both MediaPipe and Google notices retain exact upstream bytes.
- `app/build.gradle.kts`: optional production signing and release manual editor enabled.

Command: `gradlew.bat clean baselineVerify assembleUiTest assembleUiTestAndroidTest assembleRelease`.
JAVA_HOME and ANDROID_HOME explicitly set; PYTHON uses the bundled working interpreter.
Log: `D:/CodexWork/veycad-integration-v4-build.log`. Completed successfully in 8m 20s.
1392 JVM tests, zero failures/errors/skips; 338 Python tests passed.
Lint/security checks and UI/release APK assembly passed.
Release output is unsigned unless permanent signing is separately configured.
No diagnostic RotationProbe logging/readback or glFinish workaround is included.

Packaged notices, release flags and license export checks are recorded below.
Next: native/full-run acceptance on this integrated candidate.
Known unresolved GLES partial-frame issue is not waived. Physical device, signed
update, media review, store setup and rights acceptance remain open.

This is a local integration snapshot, not a merge into main or a submitted release.


## Packaged notices and device export

All four debug APKs and all four unsigned release APKs contain exactly the 30
production license files, byte-identical to source; no UI-only fixtures in release.
Google index validates all 19 artifact notice pairs; MediaPipe notice hash matches
`73ea53606db6ad3bb5acf15c973d55637e5acf6702dca5a113f7cbca70819558`.
Generated release BuildConfig: LIVE_PREVIEW_ENABLED=true, GALLERY_MVP=false.

On API 36 host-GPU emulator, LicenseExportScreenTest passed 3/3 in 17.614s
(export, cancel, unavailable destination). A separate export was pulled with
binary-safe adb and compared independently against the installed APK: exact
names and bytes for 32 files (30 production notices + two UI test fixtures),
no missing/extra/duplicate entries. This uses a stub document provider; real
DocumentsUI, physical storage and accessibility acceptance remain open.

- Tested x86_64 UI APK SHA256: `4dedf5235208f48a848aaec6d4b97727d7cb9fc0aafca656c0547f477cd1f262`.
- Exported ZIP SHA256: `9b01e0866cbd704be6f76f0c2edcd83bae2460305da3a14d755a7c1569aacb6b`.
- Unsigned arm64 release APK: 220746254 bytes,
  SHA256 `21e3ba5565cc3f8dd18b868d3d2f8c3045ce2e40d0bc71f94bb028cdf8123c27`.
- Reports: `D:/CodexWork/veycad-v4-{debug,release}-licenses.json`,
  `veycad-v4-license-export-results.xml`, `veycad-v4-license-export-completeness.json`.

This verifies notice preservation/export, not completeness of legal clearance.
No permanent key has been created, no release signed or submitted, no PR merged.
Full UI/GLES and physical-device/media acceptance are still open.


## Full UI run — partial, still running

Run ID `20261026-080232-613`; same V4 source above.
Command: `tools/run_ui_tests.ps1 -Serial emulator-5556 -AvdName AutoEditUi_API36`,
with explicit working PYTHON and SDK/JDK paths. Log:
`D:/CodexWork/veycad-integration-v4-full-ui.log`.

At the start of screens scenario 52/267, no terminal failures were recorded.
Scenario 36, CaptureReviewScreenTest.automaticChoiceSurvivesRecreationAfterOpeningSpecificResultTake,
completed PASS. Unlike V3, its long synthetic AVC source finished within the
unchanged 20-second limit and the review/recreation assertions executed.
No change to SyntheticVideo was made. This one pass does not establish the
cause or permanent absence of the previous encoding timeout.

The runner is live; final XML, media collection and restart phases are still
pending. This partial observation is not a full-run acceptance result.
