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

## CI follow-up, 2026-11-02

A separate fresh V7 AAC run on 2026-11-02 completed both native tests:
`actual_aac_markers_match_authored_video_frames_and_duration_at_30_and_60` and
`overlapping_fullscale_source_music_retains_float_aac_evidence_and_premusic_speech`.
Result: 2/2 PASS in 7.185s on API36 emulator-5556. UI/test APK rebuild passed
in 1m42s; source remains `d224c0c`. Fresh APK/archive hashes are in
`D:/CodexWork/veycad-v7-audio-focused-20261102/receipt.json`.
The independent host oracle passed on the retained archive: 48,000 stereo sample
frames, peak 0.8921767473, no full-scale run, exact markers 1600/24000/46400
in prepared/exported 30/60 fps artifacts. Archive and oracle result are retained
under that directory. Test log: `D:/CodexWork/veycad-v7-audio-tests-20261102.log`.
This closes these two controlled V7 audio checks only; Android float decoding,
human listening and real-source media acceptance remain open. The earlier CI
run's missing evidence is not retroactively repaired by this separate run.

Exact-head baseline checks subsequently passed for both PRs:
[101](https://github.com/Veycad/Veycad/actions/runs/41291211349) and
[102](https://github.com/Veycad/Veycad/actions/runs/41291515808).
Their full UI jobs finished cancelled, with the explicit annotation
“The job has exceeded the maximum execution time of 1h0m0s”:
[101](https://github.com/Veycad/Veycad/actions/runs/41291211330) and
[102](https://github.com/Veycad/Veycad/actions/runs/41291515869).
Neither is a completed UI pass; these annotations alone do not establish the
absence of earlier test failures.

The PR #102 job log was inspected separately. Its retained-AAC check failed with
`ValueError: Expected one exact-run AAC evidence archive, got 0`. This is missing
evidence, not an observed audio corruption result; the check remains incomplete.
The log also contains ten distinct failing/error test records before termination.
All ten names were matched against the archived completed V6 XML: nine passed
there; the direct first/reverse/source-change frame case failed there as already
documented. PR #102 is based on main rather than combined V6, so this comparison
does not establish a new layout regression or override either run's failures.
No fresh media acceptance is inferred from the comparison.

Local evidence: `D:/CodexWork/veycad-pr102-ui-job-20261102.log`,
`veycad-pr102-ui-failed-cases-20261102.json` and
`veycad-pr102-v6-comparison-20261102.json` in the same directory.

The isolated branches still use main's 60-minute UI job budget. The existing
[PR #98](https://github.com/Veycad/Veycad/pull/98) changes it to 120 minutes,
and the V7 integration branch was checked to contain that value. No duplicate
timeout fix, rerun, merge or acceptance waiver was introduced here.
Evidence: `D:/CodexWork/veycad-settings-ci-20261102.json` and the corresponding
`veycad-settings-ci-*-jobs.json` / `*-reason.json` snapshots.

[PR #103](https://github.com/Veycad/Veycad/pull/103), source `4c3961d`, separately
adds accessible names to the two settings switches. Its build, installed node
names/checkable states and independent durable-switch test passed locally
(6.554s). Receipt: `D:/CodexWork/veycad-switch-label-receipt-20261102.json`.
It is not included in V7. Its baseline/UI jobs were still running at this
follow-up snapshot; full TalkBack speech/focus and physical acceptance remain open.

## V8 documentation/accessibility follow-up, 2026-11-02

Local branch `codex/release-integration-v8-local`, source
`4ebff1d27f49a3ffba41562c106e29e6d77dd4fc`, combines V7 with PR #103 as
`2e9f17d` and the existing PR #75 contact follow-up `ddb421b` as `4ebff1d`.
The latter was already present in its owning PR but missing from V7. Both applied
without conflicts. Delta: two switch descriptions in the main layout and the
confirmed developer/support contact in `docs/release/privacy-draft.md`.

UI app/instrumentation assembly passed in 8s. The independent durable-switch
test passed on the installed V8 APK in 6.313s. Logs:
`D:/CodexWork/veycad-v8-build-20261102.log` and
`D:/CodexWork/veycad-v8-switches-20261102.log`.
Source, APK and policy hashes: `D:/CodexWork/veycad-v8-identities-20261102.json`.

This is a local integration checkpoint, not a newly accepted release. V7 audio
results remain scoped to V7, and broad V6/V7 results do not become V8 executions.
No signed APK, store submission, policy publication or owner approval occurred.
Policy effective date/public URL, exact signed-build review, TalkBack and device/
media acceptance remain open.
