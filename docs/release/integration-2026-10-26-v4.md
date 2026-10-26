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
Log: `D:/CodexWork/veycad-integration-v4-build.log`. Build started; no pass claimed yet.
Release output is unsigned unless permanent signing is separately configured.
No diagnostic RotationProbe logging/readback or glFinish workaround is included.

Next: check packaged notices against source bytes, generated release flags and
full license export tests on this integrated APK, then native/full-run acceptance.
Known unresolved GLES partial-frame issue is not waived. Physical device, signed
update, media review, store setup and rights acceptance remain open.

This is a local integration snapshot, not a merge into main or a submitted release.
