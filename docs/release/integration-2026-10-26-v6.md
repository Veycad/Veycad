# V6 combined release validation

Status: running, not release-ready. No PR merged and no store submission approved.

Source: `6ce47f5d4fa278c19488a2c8c0d778638095f9a8`, local branch
`codex/release-integration-v6-local` in `D:/CodexWork/VeycadRuStore-20261025`.
It combines V5 (`8916a4c`) with the four native-preview wait corrections from
[PR #100](https://github.com/Veycad/Veycad/pull/100), recovery intent/compact
viewport corrections from [PR #99](https://github.com/Veycad/Veycad/pull/99),
and the full CI job time budget from [PR #98](https://github.com/Veycad/Veycad/pull/98).
The four-file diff contains tests, the recovery host script and CI configuration;
`app/src/main` and `app/build.gradle.kts` are unchanged from V5. This does not
transfer exact-binary acceptance to a newly built artifact.

## Validation in progress

Started a clean `baselineVerify`, UI APK/instrumentation and unsigned release
assembly. On success, the existing full `run_ui_tests.ps1` runs on dedicated
API36 AOSP emulator `emulator-5556` / `AutoEditUi_API36`. A build failure prevents
the UI phase from starting. Explicit JDK/SDK/Python paths are configured.

Logs: `D:/CodexWork/veycad-integration-v6-build.log` and, once started,
`D:/CodexWork/veycad-integration-v6-full-ui.log`. No V6 PASS or APK identity is
claimed until terminal results and generated artifacts are inspected.

Before cleaning, 545 previous report/artifact files (804,460,595 bytes) were
preserved under `D:/CodexWork/veycad-v5-before-v6-build/`, with a SHA-256 manifest.
Earlier full V5 evidence and standalone diagnostic/fix runs remain in the
[previous receipt](integration-2026-10-26-v5.md).

## Prior supporting evidence

- V5 full UI: permissions 2/2, screens 266/267; one manual-preview wait failure.
- V5 plus PR #100: complete manual scenario 1/1 PASS in 358.117s at 320x480,
  including native export, exact shared bytes, reopen and rotation.
- Isolated-main PR #99: process recovery PASS at both 320x960 and 320x480;
  fresh process, six unchanged files, retired scratch and native paused preview.
- PR #98 and #99 exact-head baseline/legacy-save CI passed at the latest check;
  their UI jobs and all three PR #100 jobs were still running.

These are scoped results, not a replacement for the combined V6 run.

## Remaining release acceptance

Signed final APK, install/update preservation, physical no-GMS acceptance,
rear-camera/orientation and capture-to-montage acceptance, TalkBack and human
visual/audio review remain open. Developer account/permanent key, final store
assets, public privacy URL and third-party music rights remain unresolved.
The owner has not approved merges or store submission.
