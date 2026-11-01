# V7: settings usability integration

Status: baseline and affected settings checks PASS; not release-ready.
No merges or store submission performed.

Source `d224c0c7c5759f49e151d17762ba6ffa02f3584c`, local branch
`codex/release-integration-v7-local` in `D:/CodexWork/VeycadRuStore-20261025`.
Based on [V6](integration-2026-10-26-v6.md), plus:

- [PR #101](https://github.com/Veycad/Veycad/pull/101), `37a48e4`, applied as
  `c0d6f94`: settings/back targets 48dp and matching header spacer.
- [PR #102](https://github.com/Veycad/Veycad/pull/102), `6e3a4f8`, applied as
  `d224c0c`: four montage settings rows grow with text; style/quality values
  appear below their labels instead of squeezing them horizontally.

Both applied without conflicts. V6-to-V7 changes only
`app/src/main/res/layout/activity_main.xml` (19 additions, 15 deletions).
No media processing, lifecycle, signing or feature flags changed.

## Verification

`baselineVerify :app:assembleUiTest :app:assembleUiTestAndroidTest` passed in
4m14s (134 tasks, 40 executed, 94 up-to-date). Python: 338/338 PASS, no skips.
The JVM task was UP-TO-DATE: retained XML records 1,392 passing tests with no
failures/errors/skips, not a new execution of those tests. Lint/security/build
completed successfully. Log: `D:/CodexWork/veycad-v7-integration-build-20261101.log`.

On API36 emulator-5556, 320x480/density160/font1.0, `SettingsScreenTest` passed
7/7 in 47.157s on the combined APK. Coverage includes settings choices,
durable switches, return navigation, protected cache clearing and diagnostics.
Log: `D:/CodexWork/veycad-v7-settings-tests-20261101.log`.

Normal navigation also checked: scroll the home screen, tap “Довести монтаж”,
then observe the manual editor and “Добавить видео”. This confirms the entry in
this UI-test APK only, not a signed release or a new import/export acceptance.
Evidence: `D:/CodexWork/veycad-v7-home-20261101.xml` and
`D:/CodexWork/veycad-v7-editor-20261101.xml`.

| Artifact | SHA-256 |
| --- | --- |
| x86_64 UI app | `6748ada847e9d5592678e3be30074bfdfb09b0ea6373ac72d99a169f9abf43e1` |
| Instrumentation | `1b59936cc169d9f3df7c33e6245e787f1dff7af19fada3235f34d11cc0f2e933` |

Machine-readable local identities:
`D:/CodexWork/veycad-v7-build-identities-20261101.json`.
Independent branch visual/touch receipts:
`D:/CodexWork/veycad-settings-touch-receipt-20261101.json` and
`D:/CodexWork/veycad-settings-font-receipt-20261101.json`.

## Limits and next gates

The full 267-case suite was not rerun for this layout-only delta. V6's full
result remains 266/267, with the documented SwiftShader first-frame failure
and NVIDIA comparison; do not relabel V7 as a full-suite pass. Physical-device,
TalkBack, real media/listening, camera/no-GMS and signed install/update acceptance
remain open. No V7 signed or unsigned release APK was produced in this check.

At the GitHub snapshot during this run, both PRs were drafts and their baseline/UI
jobs were running. Local checks are not a statement of final CI status. Store
account, permanent key, rights, final store images and public policy remain open.
