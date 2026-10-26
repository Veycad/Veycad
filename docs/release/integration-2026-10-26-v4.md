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


## Full UI run — completed with failures

Run ID `20261026-080232-613`; same V4 source above.
Command: `tools/run_ui_tests.ps1 -Serial emulator-5556 -AvdName AutoEditUi_API36`,
with explicit working PYTHON and SDK/JDK paths. Log:
`D:/CodexWork/veycad-integration-v4-full-ui.log`.

Terminal result: permissions 2/2 PASS; screens 267 executed, 256 passed,
11 failures, zero errors/skips/duplicates/missing tests. Runner exit code 1.
The failed screens phase prevented atomic-import restart and project-recovery
restart phases from running; both remain unverified in this run.

Failures comprise three GLES pixel comparisons; the text-export Back click;
portable-backup completion scrolling; the looping style preview; four text-editor
UI interactions; and the nonzero-origin decoder regression fixture assertion.
Passing isolated/physical tests do not replace this failed integration result.

Scenario 36, CaptureReviewScreenTest.automaticChoiceSurvivesRecreationAfterOpeningSpecificResultTake,
completed PASS. Unlike V3, its long synthetic AVC source finished within the
unchanged 20-second limit and the review/recreation assertions executed.
No change to SyntheticVideo was made. This one pass does not establish the
cause or permanent absence of the previous encoding timeout.

Final XML, instrumentation log, logcat and the 15,133,696-byte source-audio
archive were collected without collection errors under
`build/reports/ui-tests/runs/20261026-080232-613/screens/` in the integration
checkout. Summary: `build/reports/ui-tests.json`. Audio archive collection is
not a decoded-audio oracle or listening acceptance.


## Packaged manifest and signature inspection

Inspected the exact arm64 release APK identified above with Build-Tools 36.0.0
aapt and apksigner. Package `com.veycad.app`, versionName `0.1.16`, versionCode
17, minSdk 26, targetSdk 36, label `Veycad`, native ABI arm64-v8a.
No debuggable declaration; allowBackup=false. Requested permissions are CAMERA,
POST_NOTIFICATIONS, WAKE_LOCK, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE and
the application-specific DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION.
No INTERNET/ACCESS_NETWORK_STATE, RECORD_AUDIO or broad media/storage permission
is present in this APK. This inspection is not a complete privacy/security audit.

apksigner reports `DOES NOT VERIFY: Missing META-INF/MANIFEST.MF`, consistent
with this unsigned package. It is not install/update/publication acceptance.
Logs: `D:/CodexWork/veycad-v4-release-{badging,permissions,manifest,signature}.txt`.


## First physical arm64 check

Owner connected Samsung SM-A256E, Android 16/API 36, arm64-v8a, Google services
present. This does not cover no-GMS devices. Existing test app was idle; only
`.uitest` and its instrumentation package were updated with `adb install -r`.
No uninstall, production package installation or permanent signing was performed.
Before update, the existing app/test APKs and files directory were backed up
locally under `D:/CodexWork/veycad-phone-before-v4-20261026` with SHA256 inventory.
All 39 existing performance-evidence files remain byte-identical afterward;
only `files/profileInstalled` changed among the 40 original files.

Tested arm64 UI APK SHA256:
`36473d1f8f725866fed3236aec0ed67a0accd6cb6b12160cafc0f78afbd07217`.
- Two previously failing emulator GLES scenarios: 2/2 PASS, 14.331s.
- Entire GlesFrameCompositorDeviceTest: 12/12 PASS, 59.43s,
  XML failures/errors/skipped all zero.
- Evidence: `D:/CodexWork/veycad-v4-phone-gles{,-class}.log`, corresponding
  `-results.xml`, and `veycad-v4-phone-preserved-files.json`.

The parallel API 36 emulator run still reports pixel differences. The physical
result establishes these compositor tests on this phone; it does not prove the
cause of emulator failures or complete UI, camera, audio, performance, lifecycle,
visual/human or signed-release acceptance. Those remain open.


### Physical preview and manual editor route

Same Samsung/API 36/arm64 UI APK:
- DraftPreviewControllerDeviceTest: 4/4 PASS, 34.108s.
- ManualMvpScreenTest.importFourFormatsTrimUndoReopenAndLandscapeExportShareOneRevision:
  1/1 PASS, 54.703s. Enters from the main UI, changes all four aspects,
  trims/reorders/undoes, exports 1920x1080, verifies source hashes and shared-file
  bytes, recreates the editor and checks landscape formats.

Before the UI fixture ran, a MediaStore query found no videos owned by the test
package. The 39 previous performance files were temporarily moved outside its
files-directory cleanup scope, restored in finally and independently hash-checked
against the pre-update archive. No existing gallery videos were removed.

Evidence: `D:/CodexWork/veycad-v4-phone-preview{,-results.xml}` (log uses `.log`),
`veycad-v4-phone-manual.log`, `veycad-v4-phone-manual-results.xml`,
`veycad-v4-phone-manual-evidence/` (24 PNG/text files),
`veycad-v4-phone-manual-export.tar` (MP4 and properties, SHA256
`2b6ff6a77864552df75c773491b655d672f5d24b3c260412b917a3a505f7f97d`).
Two captured editor screens were visually inspected; these use synthetic bars,
not store marketing imagery or human acceptance of a real montage.

The subsequent 60fps/background and large-font checks are recorded below.
TalkBack, live camera/audio/real footage, no-GMS and signed-release update
acceptance remain open.


Physical 60fps/lifecycle follow-up on the same APK:
ManualMvpScreenTest.saved60FpsScrubPlayPauseBackgroundAndRecreateRemainPaused
passed 1/1 in 28.836s (XML failures/errors/skipped zero). Covered exact scrub
frame 61, play/pause, recreation with held consumers/export, close/reopen,
Home/background release and paused return. This is functional 60fps-project
acceptance, not a sustained 60fps performance measurement. The performance
folder was again protected outside fixture cleanup and restored in finally.
Evidence: `D:/CodexWork/veycad-v4-phone-manual60.log`, `-results.xml`,
`veycad-v4-phone-manual60-evidence/` (32 accumulated PNG/text captures).

### Physical large-font and text-cleanup follow-ups

PR #94 head ca78edb2b6fd1fe17a16bc58ebefa825a4de43a0 converts the large-font
fixture's 320x640 dp viewport to physical pixels using the device density.
The old fixture timed out on this phone (420 dpi override); V4 plus the test-only
patch passed 1/1 in 18.350s at 200% font. Original display size, density and font
scale were restored. Evidence: `D:/CodexWork/veycad-phone-font-density-green.log`,
`-results.xml`, and `veycad-v4-phone-font-evidence/`. The captured narrow error
screen was visually inspected for wrapping and reachable controls. This does
not establish TalkBack acceptance or change the V4 source revision above.

MediaWorkSessionDeviceTest.realTextExportRejectsConcurrentCleanupAndFinishes
failed in the full emulator run because Back was outside Espresso's visible
click area. V4 plus a test-only scrollTo before that click passed on the phone:
1/1 in 10.568s, then 1/1 in 10.826s on a repeat. It checks native text export, rejection of concurrent cleanup,
audio/video tracks, duration, decoded first/last frames and preserved output.
Evidence: `D:/CodexWork/veycad-phone-text-scroll-check.log` and `.xml` contain
the latest repeat. The test-only change is published in draft PR #95.
An earlier physical attempt timed out opening the source; its cause remains
unresolved and two successful retries do not establish reliability.

The performance folder was restored after this test. The first post-test
verification script incorrectly compared an adb shell directory listing;
a separate binary tar comparison against the pre-update archive confirmed all
39 original performance files have identical SHA-256 hashes.

### Text-editor timeout investigation (not a release fix)

The unmodified V4 entry-route test passed on Samsung in 7.337s but failed in
isolation on the emulator. An experiment avoiding redraws at unchanged preview
positions passed all six TextEditActivityDeviceTest cases on Samsung in 33.506s;
the emulator still failed four of six. A second experiment also stopped the
preview timer while paused; it did not eliminate emulator UI failures either.
Neither experiment is accepted as a fix or included in a PR. Source changes
were reverted; patches and logs remain outside the repository for diagnosis.
The phone is restored to the V4 app identified above.

Evidence: `D:/CodexWork/veycad-phone-text-entry-before-idle.log`,
`veycad-phone-text-idle-class.log` / `.xml`,
`veycad-emulator-text-entry-before-idle.log`,
`veycad-emulator-text-idle-class.log`, `veycad-emulator-text-idle-v2-class.log`,
and `veycad-text-idle{,-v2}.patch`. These experiments do not close the four
text-editor failures in the full V4 run.
