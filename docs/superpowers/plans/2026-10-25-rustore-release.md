# RuStore first release implementation plan

> Execute with superpowers:executing-plans, one independently reviewable task at a time. Keep unchecked acceptance items open; a successful build is not a release verdict.

**Goal:** Prepare Veycad for RuStore first, then other Android stores excluding Google Play.

**Architecture:** Keep the existing local Android processing pipeline and application ID `com.veycad.app`. Reuse existing UI/session/storage classes and tests. Prefer one signed APK and certificate across stores; introduce no store SDK without a feature requiring it.

**Tech stack:** Kotlin, Android/Gradle, MediaCodec/GLES, bundled ML Kit/MediaPipe/Whisper, JVM/Python/UI/device checks.

**Spec:** Owner request of 2026-10-25: realistic release plan, GitHub presentation and usability polish, persist as an active goal and start execution. This document records the working scope; it does not grant authority to merge or publish.

## Constraints and decisions

- RuStore first; Google Play excluded. Do not silently reduce existing quality gates or declare unreviewed output accepted.
- Current main at intake: `beca0960fbac96837e39a92ed751909eb9e83715`, source version 0.1.16/code17.
- Existing dirty historical checkouts are preserved. Use the current isolated code and documentation worktrees listed in the [handoff](../../release/release-handoff.md).
- No new editor, account, payments, analytics, cloud upload or aesthetic redesign in this release preparation.
- Primary acceptance paths: gallery montage and text/subtitles. Auto camera and any other exposed feature must pass its own acceptance or have an explicit owner-approved deferral; do not silently remove it.
- Keep Sigma's existing paused status. No revival in this work.
- Owner confirmed no permanent signing key or RuStore account exists yet. Follow [account/signing preparation](../../release/account-and-signing.md). Never place secrets in source, logs or chat. No replacement signing identity without explicit agreement.
- Each coherent implementation gets its own commit/PR. Merge only after owner approval and applicable checks; store submission is a separate approval.
- Apply Ponytail skills when they help keep the complete change small. Reuse existing code; retain validation, accessibility and acceptance gates.

## Текущее состояние

Актуальные ветки, результаты и оставшиеся действия ведутся в одной
[краткой сводке](../../release/release-handoff.md). Сборка и отдельные
успешные проверки не означают готовность к публикации. Подробная история
проверок сохранена в отчётах `docs/release/` и Git; этот план хранит
последовательность работ и критерии приёмки.

Незакрытый пункт может объединять готовую реализацию и ещё не выполненную
проверку подписанного приложения. Количество галочек не является процентом
готовности. Телефон владелец подключит позже; повторно не запрашиваем.

## Remaining work and sequencing

On 2026-10-26 the owner challenged the remaining-time estimate. The conversational
5–10 day estimate was withdrawn as unsupported. The earlier 10–18 day planning
range is also superseded: it was a rough initial allocation, not measured
remaining effort. No release date is currently supported by evidence.

| Work block | Exit condition | Current dependency |
| --- | --- | --- |
| 1. Remaining failures | Explain and resolve native/UI failures without weakening acceptance | API26 AAC/save cases passed across documented runs; #124 remote baseline/API28 legacy 8/8 passed. Current combined 16 KB editor/AAC/speech 11/11 passed, old APK cold control 4/4 passed; previous VerifyError/JIT causes remain unknown. Full current UI pending after #121 first-frame timeout |
| 2. Manual editor acceptance | Reach editor normally; import, preview, edit, rotate, reopen and export | API28 legacy 8/8 and API36 selected cases passed. Current diagnostic helper functional case passed; signed arm64/physical acceptance open |
| 3. Integrated candidate | Combined changes build and pass applicable checks | Production candidate `931e530`: 1397 JVM + 338 Python, four unsigned APKs and static 16 KB alignment passed. Full UI, signed/device acceptance and merge approval remain open |
| 4. Physical-device acceptance | arm64/no-GMS, output review, lifecycle and update preservation | Phone reconnection explicitly deferred; no-GMS, rear camera, TalkBack, human output review and signed updates remain open |
| 5. Store/GitHub materials | Listing, screenshots, policy and repository describe accepted behavior | Listing/policy/README drafts prepared; accepted-build images and public policy URL pending; see material inventory in listing |
| 6. Signing and publication | Account, permanent key, verified signed package, owner-approved submission | Account/key absent; third-party music rights unresolved |

Record completed checks against exact source/APK identities in release receipts.
Estimate remaining effort only after the unresolved failures have concrete fixes
and device access is established; separate engineering work from waiting on owner
setup and store moderation. Do not turn passing-test counts into release readiness.

Materials can progress alongside engineering. Other stores follow the RuStore
candidate acceptance; account eligibility and policies need a current check.
See the [AppGallery/Galaxy Store sequence and gates](../../release/other-android-stores.md).

Completed combined run and subsequent diagnostics: [V6 receipt](../../release/integration-2026-10-26-v6.md).
Previous completed run and diagnosis: [V5 receipt](../../release/integration-2026-10-26-v5.md).

## Review focus

1. Long, corrupt, HDR, rotated and unsupported inputs: early useful refusal, no unbounded work.
2. Low storage or interruption during import/save: preserve original and previously completed edits.
3. Background, screen lock, process death and cancellation: accurate state, no false success or lost completed output.
4. Fonts at 200%, TalkBack and small screens: operable controls and understandable progress.
5. Real arm64 devices, including a device without Google services: local models, codecs, sound and subtitles actually work.

## Task 1 — Establish baseline and release packaging

Files: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `build.gradle.kts`, `.github/workflows/android-baseline.yml`, `docs/release/`.
Consumes: current main and installed Android/JDK/NDK tools. Produces: exact build receipt and release procedure.

- [x] Read current main, repository contribution rules, RuStore publication/signature requirements.
- [x] Isolate changes from historical dirty checkout and create task branch.
- [x] Run `gradlew.bat baselineVerify` with configured JDK/SDK/Python; record failures without calling them regressions until compared to base. [Baseline receipt](../../release/baseline-2026-10-25.md): 1392 JVM + 338 Python, build/lint/security passed; no release/device verdict.
- [x] Inspect candidate `931e530` release manifest, build flags and native/model payload: all four unsigned APKs match the [current receipt](../../release/aac-legacy-2026-11-09.md). This is package inspection, not runtime acceptance.
- [ ] Close model/music rights and full license compliance; packaged notice checks do not establish permission to distribute every asset.
- [x] External signing configuration implemented in draft #73 and combined source; opt-in signing, required inputs and no debug fallback verified by negative probes.
- [x] Build and inspect all four unsigned `931e530` release APKs: application ID/version, non-debuggable state, source SHA, flags/native payload and file hashes recorded. Signature verification rejects them as unsigned; no certificate fingerprint or signed install is claimed.
- [ ] Verify the final signed APK, alias/password, certificate fingerprint and same-key update with the owner-controlled permanent key. The rejected temporary-key probe did not complete this check.
- [x] Measure packaged size and evaluate the minification decision using the [V11 size inventory](../../release/package-size-v11-2026-11-02.md). Keep current settings: DEX plus all Android resources occupy 5.431% of ARM64 APK. No R8 savings or altered-build runtime acceptance is claimed. Optional model compression was measured separately; it is not implemented or a release prerequisite.
- [x] Packaging changes committed in separate draft [PR #73](https://github.com/Veycad/Veycad/pull/73) and [PR #104](https://github.com/Veycad/Veycad/pull/104); both remain open and unmerged. Signed/device acceptance remains separate.

## Task 2 — Make the existing workflow understandable

Files: `MainActivity.kt`, `MontageFailurePresentation.kt`, `MontageStylePresentation.kt`, `StylePickerDialog.kt` under `app/src/main/java/com/example/autoedit/`; corresponding `app/src/test/...` tests; main layout/resources and existing UI tests.
Consumes: existing session states and typed failures. Produces: clearer copy and consistent visible branding without changing engine gates.

- [x] Audit current shared-presentation callers and existing copy assertions: three failure-message callers and two style-presentation callers checked at V12, with matching exact-source JVM results (13 failure-copy and 17 style-copy cases). Repeat the relevant caller check before further edits.
- [x] Replace typed Heartbeat search/candidate jargon with actionable Russian in PR #71, included in V12; keep finite-search/incomplete-search scope explicit rather than declaring the entire source unsuitable.
- [x] Make untyped codec/execution failures actionable while retaining diagnostics and typed business messages: #110/#117/#118 are included in `931e530`. The shared fallback and `untyped_codec_or_execution_failure_does_not_become_a_material_shortfall` now require retry copy; the latest baseline and full PR #120 presentation tests passed. This does not close wider recovery/accessibility acceptance.
- [x] Implement count/duration hints and ordered DUALITY source labels: 15–60 seconds for single-source styles, 6–60 seconds per DUALITY source and numbered selections. Current exact-source style-copy tests pass; final visual/device acceptance remains open.
- [x] Correct the final `VERIFYING` producer event to report N/N rather than N+1/N: [PR #72](https://github.com/Veycad/Veycad/pull/72), included in V12. The actual source event is corrected; UI formatting is not used to hide overflow.
- [x] Add a direct native regression for the final-selection producer event: [PR #72 follow-up](../../release/native-progress-regression-2026-11-02.md), same-instrumentation old-arithmetic FAIL and fixed PASS, clean baseline and read-only review. Scope: owning main-based PR, one-candidate fixture; not integrated V12, multi-candidate or media-quality acceptance.
- [x] Implement visible Veykad → Veycad branding (PR #74, included in V12). Current XML/build labels/README contain no old brand. Nineteen Kotlin occurrences belong to inspector symbols, persisted format IDs/comment or the existing `Movies/Veykad` save path and are intentionally preserved for compatibility. Final visual/signed-device acceptance remains open.
- [ ] Check and fix recovery from error, save/share states, navigation, touch targets, font scaling and accessibility labels using current UI.
- [x] Fix invisible 30/60 fps selection in the manual editor: [PR #108](https://github.com/Veycad/Veycad/pull/108), identical-instrumentation red/green check, 100%/200% text and recreated-screen restoration passed. Integrated into V12: all six manual cases and two large-text cases passed; signed and spoken TalkBack acceptance remain open.
- [ ] Run affected JVM/UI checks, then capture native screens. Commit and PR with visual acceptance still explicit.

Source audit receipt: `D:/CodexWork/veycad-v12-source-readiness-audit-20261102.json`.
It verifies committed source blobs and the matching existing V12 JVM XML/debug
source stamp. It is not a new native run or evidence of final visual acceptance.

## Task 3 — Bound processing and protect user work

Files: existing import/media validation, `EditSession.kt`, source/cache/workspace/storage helpers and their existing tests. Locate owning code and all callers before each fix.
Consumes: supported-input contract and baseline measurements. Produces: measurable limits and safe interruption behavior.

- [ ] Measure analysis/render duration, peak memory/storage and output size on representative sources before choosing limits.
- [x] Verify existing `SourceAnalysisBudget.preflight` limits (60 s/source, 240 observations, 256 MiB raw-cache/128 MiB attachment ceilings and 256 MiB storage reserve), all three callers (`MediaFrameVisualAnalyzer`, `MulticlassMatteProvider`, `SmartFramingSourceAnalysis`) and the matching six passing V12 `SourceAnalysisBudgetTest` cases. Limits remain unchanged; this closes static/JVM verification only, not physical performance/memory measurements above.
- [ ] Exercise corrupt/empty input, rotation, VFR, HDR handling, absent audio, unavailable URI and insufficient storage; add a regression test for each reproduced defect.
- [ ] Exercise cancel, back, background, lock and process death during import/analysis/render/save. Check temporary cleanup and completed-result recovery.
- [ ] Check subtitle RU/EN transcription, manual timing/text correction and exported sound, not just UI appearance.
- [ ] Commit each root-cause fix separately; retain source/output hashes and logs for media checks.

## Task 4 — Accept the exact release candidate

Files: `docs/SupportMatrix.md`, existing `docs/testing/` and `docs/quality/` protocols; new receipt under `docs/release/` without user media.
Consumes: signed candidate SHA and explicit feature scope. Produces: evidence-backed release verdict.

Практический маршрут: [проверка итогового релиза на телефоне](../../release/phone-acceptance.md).
Он дополняет критерии ниже; заполненного результата пока нет.

- [ ] Record at least one constrained and one modern physical arm64 phone, OS/codec capabilities and available space; include no-GMS verification before advertising it.
- [ ] Test fresh install, same-key update preserving edits/settings, permissions declined and revoked, offline use, accessibility and every exposed first-release route.
- [ ] Use varied new authorized media and existing regression fixtures; follow current quality/human-review requirements rather than reusing old approvals.
- [ ] Record output decode, audio/video sync and actual runtime; owner reviews representative final MP4s and approves style quality.
- [ ] Maintain explicit pass/fail/not-run rows with candidate SHA. Any crash/data loss/unusable core export blocks release.

## Task 5 — GitHub and RuStore presentation

Files: `README.md`, `docs/release/rustore-listing.md`, future approved screenshots/privacy/third-party notice material.
Consumes: verified current features and accepted build. Produces: honest listing and understandable repository entry point.

- [x] Move full rights block lower in README unchanged, retain prominent license link; lead with benefit and current availability.
- [x] Draft RuStore name (≤30 chars), short text (≤80), full text (≤4000), support contact, what's new and moderator instructions. [Draft](../../release/rustore-listing.md), text limits rechecked 2026-11-02: 26/77/1800 chars. Final signed-build review and console entry remain open.
- [ ] Inventory data/permissions and third-party terms including music distribution rights; owner confirms any rights not documented in repository. [V10 data/permission worksheet](../../release/rustore-data-safety.md) prepared; final APK/console review and music rights remain open.
- [ ] Prepare privacy text and an accessible policy URL consistent with actual SDK behavior; do not equate local inference with a completed SDK privacy audit.
- [ ] Capture at least three current app screenshots from the accepted build and prepare 512×512 opaque icon. Check current RuStore limits immediately before upload.
- [ ] Use only authorized demo media; do not publish user footage as promotional material without explicit authorization.
- [ ] Update About/topics only to verified facts. No download button claiming an unpublished release exists.
- [ ] Review links, text limits and final rendered README; commit and open PR.

## Task 6 — Controlled release and other stores

- [ ] Owner confirms developer account, support identity, permanent signing key custody/backup and final metadata.
- [ ] All applicable last-commit CI and exact-candidate checks pass; owner approves PR merges.
- [ ] Publish only the exact accepted artifact. If rebuilding after merge/tag changes the APK, bind a new SHA receipt and repeat all applicable Task 4 export/audio/UI/device checks, including install/update; earlier binary acceptance does not transfer automatically.
- [ ] Present final APK/listing/known limitations for explicit submission approval; then submit to RuStore and record moderation result.
- [x] Prepare the [signed update and corrective-release procedure](../../testing/project-backup-recovery.md#signed-update-and-corrective-release-procedure), with increasing versionCode, the same certificate and preserved projects/backups.
- [ ] Execute that procedure on the exact signed candidate; a written protocol and unsigned UI tests do not prove update preservation.
- [ ] After RuStore acceptance, evaluate AppGallery/Galaxy Store and any requested stores for account eligibility, native/no-GMS compatibility, signing and metadata differences. Google Play stays out of scope.

## Sources checked 2026-10-25

- https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication
- https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication/apk-signature
- https://www.rustore.ru/help/developers/publishing-and-verifying-apps/requirement-apps

## Execution notes

- 2026-10-26: [second combined PR #70–84 receipt](../../release/integration-2026-10-26-v2.md). Combined baseline 1392 JVM + 338 Python and instrumentation build pass. First native run: 11/20 pass, nine UI focus failures caused by a documented Android Settings ANR. After stopping that system process, both affected UI classes pass 10/10 unchanged; all 20 selected unique scenarios have a successful result. This is not a single clean native run or full UI acceptance. Next: full integrated UI and direct-frame stability, then exact-candidate device/media acceptance. Public developer/contact confirmed: Leonid Polyakov, whiteking.power12@gmail.com; policy URL/date remain open.

- 2026-10-26: [combined PR #70–81 receipt](../../release/integration-2026-10-26.md). No textual conflicts; combined baseline 1392 JVM + 338 Python, lint/security/build and instrumentation compilation pass. Selected Android tests: 14/18 pass, four fail. This is not a release or full UI pass. Exact heads, tree, APK hash and per-test results are recorded; no PR merged.
- PR #79: reproduced controller shutdown race fixed; 640 stop requests and JVM/lint passed. PR #80: oversized native PCM outputs read in bounded portions; all samples/PTS verified, 15 Android tests and clean baseline passed. PR #81 depends on #79: Activity retirement waits for render callback before closing jobs; corrected owner-boundary regression reproduced the real crash before the fix, then both targeted Android tests and baseline passed. All three regressions also passed in the combined snapshot.
- Next: repair ambiguous custom-music selection in the shared UI fixture, investigate manual preview MP4 assertion, minute-project paused readiness and preview text pixel parity. Investigate the CI/local direct-frame discrepancy. Then repeat affected checks and acceptance on the updated combined tree; do not mark the broad Task 3/4 gates complete from the narrow passes.

- 2026-10-25: active goal created. Historical checkout fetch failed on Codex checkpoint refs; clean clone used instead. Clone completed file checkout but Git blocked automatically installed Git LFS post-checkout hook; clean `git status` and materialized tracked model were verified, no protection override used.
- Signing/account question sent to owner; no secret requested. No store submission, device acceptance or release pass claimed.
- README's previous claim that duration/work limits were absent was stale. Existing owning source and tests confirm 60 s/240-observation preflight; README corrected and Task 3 narrowed to verification and measured fixes.
- Documentation checks: listing title 26/30 characters, short copy 77/80; rights block unchanged. Fresh documentation review identified exact-binary reacceptance gap; Task 6 corrected.
- UX copy execution: separate clean checkout `D:/CodexWork/VeycadRuStore-20261025-shallow`, branch `codex/rustore-ux-copy`, based on the same remote main. Existing failure-copy tests first failed in the two expected cases; full JVM/lint and instrumentation compilation started after the text change. Fresh diff review found no blocking copy issues; existing final progress overflow is tracked above. Native UI acceptance remains open.
- PR #71: 1392 JVM tests and lint pass; targeted emulator UI 9/9 pass at 320x480/density160/font1.0 after replacing the test helper's whole-card scroll/click with title scroll/click. Initial 8/9 failure and fix are recorded in the PR. No physical-device or real-render acceptance inferred.
- PR #72: final VERIFYING count corrected to N/N; JVM suite passed and independent review found no issues. Direct native regression for that producer event remains pending. Decision: keep the arithmetic fix one line rather than introduce a test-only abstraction; existing UI tests exercise formatting but do not prove the real producer's execution.
- PR #73 (`codex/release-signing`): opt-in external signing configuration and build instructions. Negative configuration probes (missing SHA/keystore, invalid flag/path) and default security contract pass; review clean. Positive temporary-key probe was rejected by automatic tool approval without a detailed reason, so actual signing/password/alias/update validation remains open. No production key created.
- PR #74 (`codex/veycad-brand`, checkout `D:/CodexWork/VeycadRuStore-20261025-shallow`): visible Veycad branding, tests and README adjusted; assembleDebug, instrumentation compilation and lint passed. aapt confirms Veycad label with unchanged package/version. Runtime dialog/visual checks pending; storage paths and internal names preserved. Reviews for both PRs found no blocking issues.
- PR #74 follow-up: InformationalDialogsTest 3/3 PASS on API 36 emulator (320x480, density160, font1.0). Physical-device, visual and media acceptance remain open.
- PR #75: corrected stale gallery-permission and diagnostic-export documentation; privacy draft distinguishes private logs, filtered capture report and project ZIP. Owner identity/contact, public policy URL and final APK/SDK verification pending.
- Asset inventory: [asset-rights.md](../../release/asset-rights.md). Model/preview hashes checked; preview provenance found in existing Pexels documentation. Four music tracks require owner confirmation; source identifiers and distribution rights requested. Complete third-party notices and final APK audit remain open.
- PR #76: shared source hints now show 15–60 seconds, or 6–60 seconds per DUALITY source; 23 targeted JVM tests pass, instrumentation compiles, startup UI test and manual small-screen inspection pass. No change to processing limits.
- PR #77: move picker explanation into the scroll area while keeping title/actions fixed. At 320x480/font2.0, the strengthened existing UI test failed before (first title 32% visible) and passed after; font1.0 and lint pass. Button labels wrap at font2.0; TalkBack and physical acceptance remain open.
- PR #78: Apache-2.0 text and model attribution added from the official model card. Both match their merged release assets by hash. This closes only that model notice, not all dependency licenses or final signed-APK verification.
- Owner decision: keep third-party music and do not monetize app functions. Public wording acknowledges third-party ownership; no redistribution permission has been supplied. Do not substitute tracks or treat the disclaimer as rights clearance. Account/key and exact-candidate device/media acceptance are still outstanding.
