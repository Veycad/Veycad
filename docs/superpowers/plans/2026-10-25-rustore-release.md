# RuStore first release implementation plan

> Execute with superpowers:executing-plans, one independently reviewable task at a time. Keep unchecked acceptance items open; a successful build is not a release verdict.

**Goal:** Prepare Veycad for RuStore first, then other Android stores excluding Google Play.

**Architecture:** Keep the existing local Android processing pipeline and application ID `com.veycad.app`. Reuse existing UI/session/storage classes and tests. Prefer one signed APK and certificate across stores; introduce no store SDK without a feature requiring it.

**Tech stack:** Kotlin, Android/Gradle, MediaCodec/GLES, bundled ML Kit/MediaPipe/Whisper, JVM/Python/UI/device checks.

**Spec:** Owner request of 2026-10-25: realistic release plan, GitHub presentation and usability polish, persist as an active goal and start execution. This document records the working scope; it does not grant authority to merge or publish.

## Constraints and decisions

- RuStore first; Google Play excluded. Do not silently reduce existing quality gates or declare unreviewed output accepted.
- Current main at intake: `beca0960fbac96837e39a92ed751909eb9e83715`, source version 0.1.16/code17.
- Existing dirty historical checkout is preserved. Work in clean clone `D:/CodexWork/VeycadRuStore-20261025`, branch `codex/rustore-release-preparation`.
- No new editor, account, payments, analytics, cloud upload or aesthetic redesign in this release preparation.
- Primary acceptance paths: gallery montage and text/subtitles. Auto camera and any other exposed feature must pass its own acceptance or have an explicit owner-approved deferral; do not silently remove it.
- Keep Sigma's existing paused status. No revival in this work.
- Permanent signing key/account availability is pending owner information. Never place secrets in source, logs or chat. No replacement signing identity without explicit agreement.
- Each coherent implementation gets its own commit/PR. Merge only after owner approval and applicable checks; store submission is a separate approval.

## Realistic schedule

Planning estimate, not a release date: 10–18 working days of engineering and review if the current renderer has no major regression, plus account/signing/media-rights dependencies and external moderation. Native crashes or unacceptable output reopen the estimate. Agent coding speed does not eliminate device and human review time.

| Stage | Effort estimate | Exit condition |
| --- | --- | --- |
| 1. Intake and reproducible release baseline | 1–2 days | Exact source/build identity; known build failures; signing route documented |
| 2. Usability and naming | 2–3 days | Clear next action at import, processing, error and result; no inconsistent brand |
| 3. Processing/storage reliability | 2–4 days | Bounded inputs/work; safe cancellation and recovery; storage failures covered |
| 4. Exact-build acceptance | 3–5 days | Native exports, audio/visual review and device scenarios recorded |
| 5. Store/GitHub materials | 1–2 days | Accurate listing, current screenshots, policy and rights inventory |
| 6. Release handoff | 1–2 days | Signed verified package and update test; owner-approved submission |

Stages 2 and 5 can overlap. Other stores are evaluated after the RuStore candidate is accepted; their account eligibility and policies need a separate current check.

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
- [ ] Inspect release manifest, all runtime features/build flags, native payload and model/music licenses.
- [ ] Configure external release signing inputs without debug fallback; fail clearly when signing is requested but inputs are missing. Keep unsigned verification possible without secrets.
- [ ] Build release APK; verify signature, application ID, version, non-debuggable state, debug component exclusion and package size. Record SHA-256, source SHA, certificate fingerprint and tool versions.
- [ ] Evaluate shrinking by measured benefit and native/model regression; do not enable it blindly as a prerequisite.
- [ ] Commit packaging changes with checks and open a separate PR.

## Task 2 — Make the existing workflow understandable

Files: `MainActivity.kt`, `MontageFailurePresentation.kt`, `MontageStylePresentation.kt`, `StylePickerDialog.kt` under `app/src/main/java/com/example/autoedit/`; corresponding `app/src/test/...` tests; main layout/resources and existing UI tests.
Consumes: existing session states and typed failures. Produces: clearer copy and consistent visible branding without changing engine gates.

- [ ] Search every caller and existing copy assertion before editing shared presentation helpers.
- [ ] Replace codec/candidate jargon with actionable Russian; retain technical errors in diagnostics. Never describe a limited failed search as proof that the entire source is unsuitable.
- [ ] Explain source count/duration and style suitability before render; make DUALITY input order clear.
- [ ] Unify visible Veykad → Veycad without renaming storage keys, package IDs or historical evidence.
- [ ] Check and fix recovery from error, save/share states, navigation, touch targets, font scaling and accessibility labels using current UI.
- [ ] Run affected JVM/UI checks, then capture native screens. Commit and PR with visual acceptance still explicit.

## Task 3 — Bound processing and protect user work

Files: existing import/media validation, `EditSession.kt`, source/cache/workspace/storage helpers and their existing tests. Locate owning code and all callers before each fix.
Consumes: supported-input contract and baseline measurements. Produces: measurable limits and safe interruption behavior.

- [ ] Measure analysis/render duration, peak memory/storage and output size on representative sources before choosing limits.
- [ ] Validate existing `SourceAnalysisBudget.preflight` limits (60 s/source, 240 observations, cache/storage budgets) and callers in `MediaFrameVisualAnalyzer`, `MulticlassMatteProvider` and `SmartFramingSourceAnalysis`; run `SourceAnalysisBudgetTest`. Change limits only if device measurements justify it; do not reimplement the existing policy.
- [ ] Exercise corrupt/empty input, rotation, VFR, HDR handling, absent audio, unavailable URI and insufficient storage; add a regression test for each reproduced defect.
- [ ] Exercise cancel, back, background, lock and process death during import/analysis/render/save. Check temporary cleanup and completed-result recovery.
- [ ] Check subtitle RU/EN transcription, manual timing/text correction and exported sound, not just UI appearance.
- [ ] Commit each root-cause fix separately; retain source/output hashes and logs for media checks.

## Task 4 — Accept the exact release candidate

Files: `docs/SupportMatrix.md`, existing `docs/testing/` and `docs/quality/` protocols; new receipt under `docs/release/` without user media.
Consumes: signed candidate SHA and explicit feature scope. Produces: evidence-backed release verdict.

- [ ] Record at least one constrained and one modern physical arm64 phone, OS/codec capabilities and available space; include no-GMS verification before advertising it.
- [ ] Test fresh install, same-key update preserving edits/settings, permissions declined and revoked, offline use, accessibility and every exposed first-release route.
- [ ] Use varied new authorized media and existing regression fixtures; follow current quality/human-review requirements rather than reusing old approvals.
- [ ] Record output decode, audio/video sync and actual runtime; owner reviews representative final MP4s and approves style quality.
- [ ] Maintain explicit pass/fail/not-run rows with candidate SHA. Any crash/data loss/unusable core export blocks release.

## Task 5 — GitHub and RuStore presentation

Files: `README.md`, `docs/release/rustore-listing.md`, future approved screenshots/privacy/third-party notice material.
Consumes: verified current features and accepted build. Produces: honest listing and understandable repository entry point.

- [x] Move full rights block lower in README unchanged, retain prominent license link; lead with benefit and current availability.
- [ ] Draft RuStore name (≤30 chars), short text (≤80), full text (≤4000), support contact, what's new and moderator instructions.
- [ ] Inventory data/permissions and third-party terms including music distribution rights; owner confirms any rights not documented in repository.
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
- [ ] Prepare a rollback/hotfix procedure using increasing versionCode and the same certificate; never require deleting user data as a routine recovery.
- [ ] After RuStore acceptance, evaluate AppGallery/Galaxy Store and any requested stores for account eligibility, native/no-GMS compatibility, signing and metadata differences. Google Play stays out of scope.

## Sources checked 2026-10-25

- https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication
- https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication/apk-signature
- https://www.rustore.ru/help/developers/publishing-and-verifying-apps/requirement-apps

## Execution notes

- 2026-10-25: active goal created. Historical checkout fetch failed on Codex checkpoint refs; clean clone used instead. Clone completed file checkout but Git blocked automatically installed Git LFS post-checkout hook; clean `git status` and materialized tracked model were verified, no protection override used.
- Signing/account question sent to owner; no secret requested. No store submission, device acceptance or release pass claimed.
- README's previous claim that duration/work limits were absent was stale. Existing owning source and tests confirm 60 s/240-observation preflight; README corrected and Task 3 narrowed to verification and measured fixes.
- Documentation checks: listing title 26/30 characters, short copy 77/80; rights block unchanged. Fresh documentation review identified exact-binary reacceptance gap; Task 6 corrected.
