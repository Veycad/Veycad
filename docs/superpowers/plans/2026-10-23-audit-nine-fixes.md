# Nine audit fixes implementation plan

> For agentic workers: use the existing audit findings as the approved brief. Implement independent media, analysis and instrumentation tasks in isolated checkouts; integrate and review the whole branch before publication.

**Goal:** Address all nine audit items against current main without touching the original dirty checkout or merging a PR.

**Architecture:** Keep the existing Android editor and stores. Publish source selection as one atomic manifest, bound analysis before decoding, keep native resource ownership explicit, reuse URI document selection and existing result metadata. Retain fixes already present on main and verify them rather than reimplementing them.

**Tech stack:** Kotlin, Android API 26+, MediaCodec/GLES, JUnit, Android instrumentation, Gradle and existing Python tools. No new dependency.

**Spec:** User-approved nine-item audit in this chat; base main `4ed57d70530458a579c8fdeab4b4a8b6d33028f9`.

## Global constraints

- Work only in task-owned clean clones/worktrees and `codex/` branches.
- Preserve existing selected media, completed results, capture links and text sources on errors.
- Separate scoped commits with their regression tests; stage named paths only.
- Run full baseline, applicable UI tests and real synthetic native export; report exact limitations.
- Publish one PR; never merge or enable auto-merge.

## Review focus

- Interrupted two-source replacement must retain one complete selection and its order/labels.
- Failure before a native constructor completes must release already acquired handles.
- Low storage and over-budget sources must fail before creating large decoded caches.
- Old Android export must use a user-selected destination and remain responsive.
- Multiple unsaved/saved results must remain reachable without redundant private MP4 copies.

## Tasks

1. Atomic source selection (`EditSession`, `MainActivity`, new source-draft store and JVM tests): reproduce interruption/mixed generations, publish one synchronized manifest after all copies finish, restore only the committed generation; migrate unambiguous legacy selections conservatively. Commit as storage fix.
2. Analysis budget (`MulticlassMatteProvider`, analyzer/preflight helper and JVM tests): reject excessive duration/cache demand and insufficient available space before full-frame decode; document the enforced bound. Commit independently.
3. Native resource ownership (`MediaCodecSpeedRampRenderer`, AAC helpers and tests): check current constructor rollback, fix remaining gaps and preserve original exceptions. Verify failure cleanup and normal repeated use. Commit independently if changes are needed.
4. Android 8–9 autosave (`MainActivity` and instrumentation): reuse the existing CreateDocument path for automatic save; preserve pending destination across recreation. Test old-API routing and cancellation. Commit as UI fix.
5. Single video picker (`MainActivity`, manifest, fixtures and permission tests): use OpenDocument for one video, remove the full-library gate, prove import succeeds with read-media permission denied. Commit with its tests.
6. Completed results (`CompletedRenderStore`, `EditSession`, library UI/tests): verify all unsaved entries are reachable, retain one durable private MP4 for each new result, and continue reading pre-upgrade library files without deleting user media. Commit as storage fix.
7. AAC progress deadline (`AacEncoderMuxer` and JVM tests): add a deadline renewed by real progress, keep cancellation, prove stall fails and progress extends the deadline. Commit separately from resource ownership.
8. Native export smoke (`androidTest`, existing runner/workflow): generate synthetic media, run the real renderer and AAC mux, decode the result and verify dimensions/tracks/frames; ensure the CI UI suite includes it. Commit as test improvement.
9. Unreachable music generator (`BuiltInMusicCatalog`, its tests): retain four authored resource routes, delete unreachable synthetic catalog/generator and adjust tests to assert actual selected music. Commit as refactor.

## Verification and publication

- For each nontrivial change, add and run a regression against the prior behavior before the fix; run the relevant tests after the fix.
- Integrate the independent commits, inspect the full diff and run `clean baselineVerify`.
- Run applicable instrumentation on the dedicated emulator with the isolated `.uitest` package. Native smoke must exercise the real renderer, not CapturingRenderEngine.
- Perform a fresh whole-branch review and fix actionable findings; rerun affected checks.
- Push the task branch, create and attach a PR with audit-item mapping and exact test evidence, inspect CI for the pushed SHA. Leave merging entirely to the owner.
