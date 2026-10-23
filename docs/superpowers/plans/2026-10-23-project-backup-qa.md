# Project backup and recovery QA implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve native preview evidence, expose a portable verified project backup and safe fresh-project recovery, and verify controlled memory recovery.

**Architecture:** Collect preview evidence before the isolated app is reset. Stream an exact, inventoried project bundle and restore through the canonical current codec into a fresh ID without overwriting existing projects. Reuse existing preview retirement and resource accounting for controlled pressure.

**Tech Stack:** Kotlin/JVM, Android SAF, standard ZIP/JSON/hash APIs, PowerShell and existing JUnit/device runners.

**Spec:** Owner-approved issue #66 plan, 23 October 2026: raw backup with source/sidecar hashes, fresh-project restore, read old/write current v9, no lossy downgrade or broad store GC.

## Global constraints

- Work in the shared `codex/issue-66-completion` tree; only named owned paths.
- Root owns HybridRevision/codec audio-mix persistence; use the canonical codec rather than copying schema logic.
- Features owns ManualMvpActivity; preview owns DraftPreviewController. Coordinate hooks instead of concurrent edits.
- Implement the feature first, then one consolidated verification pass. No heavy build, ADB, commit or remote operation until coordinated.
- Existing data is never overwritten or deleted by restore; physical/OEM and human acceptance remain separate.

## Review focus

- A successful process restart must not erase evidence before host collection.
- Truncated, duplicate, traversing, linked or hash-invalid bundles publish nothing.
- Historical/export-only revisions, source music, rich text and authored sidecars survive a fresh-ID restore.
- Output-name collisions never overwrite published movies or their metadata.
- Cleanup errors retain ownership/accounting and can be retried; injected trim on API36 is not an actual LMK claim.

### Task 1: Preview evidence retention

- Modify `tools/run_ui_tests.ps1`; extend `tools/test_ui_runner.ps1`.
- [ ] Collect once before the restart gate; keep a non-destructive finally fallback.
- [ ] Require named successful-route artifacts and publish path/size/SHA256 inventory.
- [ ] Exercise the real collection/routing with a host boundary that erases device fixtures at pm clear; retain the original failure and legacy-only behavior.

### Task 2: Portable backup and fresh restore

- Create `HybridProjectBackup.kt`; add narrow locked snapshot/fork operations to `HybridProjectStore.kt`.
- Interface: `export(projectId: String, output: OutputStream): Receipt`, `restore(input: InputStream): Receipt` with a fresh project ID.
- [ ] Stream raw project files and referenced published outputs with a bounded JSON inventory; reject links and unstable content.
- [ ] Validate archive paths/counts/lengths/hashes in staging. Canonically read old/write current, preserving immutable revision/source/sidecar bytes.
- [ ] Publish only a verified fresh project and fresh result names; rollback only this operation's new publications on error.
- [ ] Add real-file tests for v7/v8/current migration, all persisted fields/export-only revisions, corruption, traversal, duplicate entries, collisions and injected failure.

### Task 3: User access and policy

- Create `BackupRecoveryActivity.kt`, backup strings and manifest entry. Features adds Manual library/project entry points.
- [ ] Use SAF Create/OpenDocument; serialize background work and preserve operation ownership through completion.
- [ ] Explain that recovery adds a new project; export a compatible backup before changing app versions. Never present current-schema output as an old schema.
- [ ] Add user-route and backend device acceptance once the shared build is ready.

### Task 4: Controlled memory recovery

- [ ] Coordinate explicit main-thread release hook with features and acknowledged controller stop with preview owner.
- [ ] Add same-budget real-GLES denial/retry and explicit trim recovery tests, preserving sources/revision/clock and releasing native owners.
- [ ] Add fresh-process saved-project recovery proof with real PID and byte/hash evidence.
- [ ] Record injected-signal versus actual system/OEM behavior accurately; no global-host exhaustion.

### Consolidated verification

- [ ] Review named diffs and trust-boundary tests.
- [ ] Run host/JVM checks after implementation. Physical-device and emulator execution is excluded by the owner's latest instruction; native acceptance remains OPEN.
- [ ] Root coordinates baseline/full exact-head CI, independent review, commits and remote delivery.
