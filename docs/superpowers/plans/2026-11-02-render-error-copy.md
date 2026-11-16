# Actionable processing failures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace raw unknown processing errors with actionable copy while preserving specific owned validation messages and original diagnostics.

**Architecture:** Keep `MontageFailurePresentation` pure. Mark owned validation text explicitly; reuse bounded `CaptureDiagnostics.failureFields` for exported metadata and Android Log for the original throwable at `EditSession` and `MainActivity` failure boundaries.

**Tech Stack:** Existing Kotlin/JUnit/Android instrumentation/Gradle; no dependencies.

**Spec:** `docs/superpowers/specs/2026-11-02-render-error-copy.md`.

## Global Constraints

- No renderer, processing-limit, package-ID, storage-path or network-permission changes.
- Do not interrupt the active V12 full run to install these APKs.
- Raw throwable messages must not be added to exported LocalDiagnostics fields.
- Keep IllegalArgumentException compatibility for owned validation refusals.

## Review Focus

- Unknown exceptions containing material/duration-like words must not become a false source rejection.
- Low space, overlong/undecodable metadata and missing source retain actionable owned text.
- Cancellation and typed material/search failures remain distinct.
- Diagnostics retain cause types without raw file paths or user text in export.
- Retry and recreation preserve usable error UI without publishing a result.

### Task1: Presentation and diagnostic boundaries

- [x] Extend `MontageFailurePresentationTest` and `MultiSourceRequestTest`; verify assertion failures on the original source.
- [x] Add `UserMessageException.kt`; update `MontageFailurePresentation.kt`, `SourceAnalysisBudget.kt` and the source-file guard in `VeycadAutomaticEditor.kt` without changing limits.
- [x] Migrate legacy duration guards in `EditDurationPolicy.kt`, `DualityLoopDirector.kt` and `ReferenceMontageProfile.kt`, and remove text matching from presentation; verify the existing guard tests and a filename containing duration-like words.
- [x] Record original throwable and safe failure metadata in existing `EditSession.kt` and `MainActivity.kt` failure boundaries.
- [x] Add native cases in `RenderFailurePresentationScreenTest.kt` for unknown failures, owned budget refusal, safe diagnostic fields, retry and recreation. Add JVM marker assertions for owned validation.
- [x] Run targeted JVM, native instrumentation compilation, clean baseline; wait for the V12 run before device installation.
- [x] Run targeted native UI and verify original cause in system log; resolve read-only review's cancellation finding and verify the focused follow-up.
- [ ] Exact staged diff, coherent commit and draft PR.

Ruling: explicit owned validation types replace the legacy duration-text heuristics — a filename containing `at least 15000` reproduced a false short-video message, and an untyped codec error can contain the same words. All three real legacy producers were found and migrated; conditions/thresholds and IllegalArgumentException compatibility remain unchanged.

Review fix: the existing renderer cancellation producer used IllegalStateException, so the generic fallback obscured normal user cancellation. Native `RenderProgressScreenTest.cancellation_unwinds_scratch_and_keeps_the_draft_previous_result_and_saved_library` reproduced this regression (expected «Монтаж отменён», got generic failure, 4.216s). Reuse its existing cancellation callback at all four render boundaries and throw CancellationException with the original message; verify that exact native test again, including source/result/library byte preservation and scratch cleanup.

Ruling: the first four-case native run passed the two new cases and existing search case, but its material-refusal case failed in the main-based fixture's whole-card `scrollTo`. Reuse the title-targeting action already present in the integrated release fixture. No scenario or assertion is dropped; rebuild instrumentation and repeat both negative controls and all positive cases with that identical final APK. The real cancellation test already passes on the corrected app (5.726s) with the prior instrumentation.

## Final verification

- `clean baselineVerify`: PASS,8m41s;1394 JVM +338 Python, zero JVM failures/errors/skips; lint/security.
- Final identical instrumentation: original app fails recovery-copy assertion; intermediate app fails real-cancellation assertion; fixed app passes presentation class4/4 and existing cancellation case1/1. Emulator API36/x86_64,37.2.13.0,NVIDIA host,320x480/density160/font1.0.
- Safe diagnostic fields and original throwable/cause in system log verified. Read-only follow-up: no remaining Important/Critical findings.
- Local native receipt: `C:/Users/rexar/Documents/Codex/veycad-render-error-copy-ui-20261102.json`; APKs, negative controls and logs retained under the same parent.
- Scope: owning branch from main with controlled failures; no integrated V12, real codec/media quality, physical-device, permanent-signature or publication acceptance is implied. Other direct import/save/share error presenters retain their separate acceptance scope.
