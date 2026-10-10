# Integration A1 report

Status: DONE_WITH_CONCERNS. Implementation is complete for the approved A1 scope;
independent review and exact-commit full CI remain pending. No push/merge, peer
worktree changes, renderer/UI/STT, A2 manual/backing/maps, B staging, C runtime,
or new reset behavior was performed.

Base: `01276586783c5de83cdf678204a11a8505aec65a`, clean
`codex/hybrid-mode-design`, verified before edits. Planned commit subject:
`feat(storage): сохранять полный общий проект источников форматов и текста`.

## Implemented

* `HybridProject.selectedVideos: List<SelectedVideo>?` is a defensive ordered
  snapshot, including unused selections. Null means unknown legacy order.
  Selection IDs are unique; physical `assetId` and fingerprint may repeat.
  Known tables validate every clip's original `sourceIndex` against its asset
  binding, graph source-index range, and every framing key against selection IDs.
  Reordering clips preserves the table. Copy rejects changing a known table on
  the same project ID; store rejects any published table change, including null
  to known, labels or capture provenance. A new project/fork is required.
* `ProjectAsset.videoMetadata: VideoSourceMetadata?` stores encoded dimensions,
  0/90/180/270 rotation, PAR, size, MIME, nullable color transfer and audio flag.
  Physical duration/fingerprint remain `ProjectAsset.durationUs/contentHash`.
  Known selections require inspected metadata and SHA-256 identity. Old null
  metadata is never invented. Existing immutable asset record checks protect
  all added metadata and historical bindings.
* `SelectedVideo` owns `SourceId`, `assetId`, actual selection `displayName`,
  `SourceOwnership`, `CaptureOrigin`. Imported/capture selection provenance is
  independent even when bytes are deduplicated. `ProjectAssetStore.importVideo`
  accepts the owner's `MediaSource` plus measured PAR, verifies size and copied
  fingerprint, and preserves stable physical identity. `fromInspected` verifies
  duration/fingerprint/geometry/other facts against that physical record.
* `HybridRevision.visualSettings` stores full `ProjectVisualSettings`: all four
  aspects, explicit choice and all three framing modes/parameters per
  selection/aspect. General default is non-explicit portrait; unchanged
  `ProjectFormats` includes FEAR square and respects explicit author choice.
* `HybridRevision.textState: HybridTextState` stores immutable rich layers/cues,
  every style field, caption style/edit flag and auto/ru/en. Published text DTO
  validation is preserved; absolute project intervals are end-exclusive and
  checked against the single project clock. Existing normalized TextItems stay.
  Copy/equality/hash code include new values and snapshot caller containers.
* `DraftProject(project)` is read-only and derives order/geometry/visuals from
  core; duplicate selections project repeated physical assets correctly. It
  fails explicitly for unknown legacy order and owns no allocator/store/history.
* Physical codec v4 encodes these fields in manifest and revision blobs. Explicit
  version dispatch reads genuine v1/v2/v3 without rewrite, preserves canonical
  revision sharing, signed phase, immutable original and reset event. Unknown
  versions fail without a write. Semantic analysis payload is unchanged.
* `ProjectMusic.gain` now accepts 0..2 unchanged; legacy graph gain 1.5/2 survives
  no-op and codec. Core has no factory in this checkpoint; Task 6 must carry the
  actual gain/recipe default through its factory without a clamp.
* No changes to `HybridEditCommands` implementation: ordinary edits allocate
  through `commitRevision`, retain current.parent/monotonic IDs and perform the
  existing event-clear rule; full `RestoreAutomatic` semantics are unchanged.

Production scope: HybridProject, HybridProjectCodec, HybridProjectStore,
ProjectAssetStore, new ProjectSources, ProjectVisualSettings, ProjectFormat,
HybridTextState, MediaSource and DraftProject. Test scope: HybridIntegrationA1Test,
ProjectSourceImportTest, imported/extended ProjectFormatTest, physical-format
expectations in HybridProjectCodecTest, genuine hybrid-v3 resources.

## Genuine fixture provenance

Before any production edit/encoder change, a temporary
`HybridIntegrationA1Test.captureGenuinePhysicalV3Fixtures` called the unchanged
base production codec. Input was
`restoreAutomatic(moveCut(editProject(), "right", 28))`.
The test passed while the new gain regression failed. It wrote physical-v3
project and original/moved/reset revision payloads, then was replaced by permanent
reader/store tests. No version header patching occurred. Task 3 implementation
at `06811435` remains the event provenance source. Existing v1/v2 fixtures are
untouched. Fixture README records reproduction source and hashes:

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| project.bin | 5223 | 2712f90f465769af3f0cbf1ff3d9b70cebf82d7d1b4c529e683c05a2e3826a3b |
| revision-0.bin | 1553 | 66d681c9acb9ca86036727b88939d95a0dfb3a2dc89d527311dae5e274785b1e |
| revision-1.bin | 1918 | e8906392c427ec595765e9550b6d5f4975df92c7675d59f9ca09c268b05ebf2a |
| revision-2.bin | 1561 | c65942f3b69b43a5aa92d1700a776bb2fd40c7956007b8d42a4e024969bb7be4 |

## RED/GREEN evidence

Every Gradle invocation used this PowerShell environment in the assigned worktree:

```powershell
$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
$env:ANDROID_HOME='C:/Users/rexar/AppData/Local/Android/Sdk'
```

Builds were strictly sequential, prior owned sessions awaited to completion.
No daemon stop or tracked toolchain setting change; incremental workaround was
not needed. Logs below are under this SDD directory.

1. Genuine-fixture capture + gain RED, production unchanged:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.HybridIntegrationA1Test' --max-workers=1 *> '.superpowers/sdd/2026-10-10-hybrid-mode/a1-red-gain-fixtures.log'
```

Actual: `legacyMusicHeadroomSurvivesNoOpAndCodec FAILED`,
`java.lang.IllegalArgumentException at HybridIntegrationA1Test.kt:16`,
`2 tests completed, 1 failed`, `BUILD FAILED in 1m 3s`, exit 1.
XML: tests=2, failures=1, errors=0, skipped=0. Capture test passed. This exercises
real core creation: previously valid legacy gain 1.5 could not enter the project.
XML saved as `a1-red-gain-fixtures.xml`.

2. After model additions, before codec additions, persistence RED:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.HybridIntegrationA1Test' --max-workers=1 *> '.superpowers/sdd/2026-10-10-hybrid-mode/a1-red-persistence.log'
```

Actual failures:

* `selectedBindingsMetadataAndPublishedTableCannotBeRebound` assertion at line
  138: old encoder erased known published table, so later validation lost it.
* `allAspectsAndLanguagesRoundTripWithoutChangingSavedModeParameters` assertion
  at line 117: decoded aspect fell back to portrait.
* `duplicateSelectionsKeepOrderIndependentFramingAndCaptureMetadata` NPE at
  line 56: decoded selection table was null.
* `fullTextAndVisualsSurviveCoreCommitStoreHistoryAndExportSnapshot` rejected at
  line 91: saved table was missing, so persistence could not preserve the edit.

`8 tests completed, 4 failed`, `BUILD FAILED in 16s`, exit 1.
XML: tests=8, failures=4, errors=0, skipped=0, saved as
`a1-red-persistence.xml`. Failures expose data loss through the real codec/store,
not compilation errors or mocks.

3. Codec GREEN:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.HybridIntegrationA1Test' --max-workers=1 *> '.superpowers/sdd/2026-10-10-hybrid-mode/a1-green-integration.log'
```

Actual: `BUILD SUCCESSFUL in 12s`, exit 0; integration tests=8, no failures,
errors or skips. Continued with explicit v3 store/no-rewrite, original rich
payload/reset and imported DTO/import contract coverage.

4. Final covering targeted run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.HybridIntegrationA1Test' --tests 'com.veycad.app.HybridProjectTest' --tests 'com.veycad.app.HybridProjectCodecTest' --tests 'com.veycad.app.HybridProjectStoreTest' --tests 'com.veycad.app.HybridEditCommandsTest' --tests 'com.veycad.app.HybridCutConstraintsTest' --tests 'com.veycad.app.ProjectAssetStoreTest' --tests 'com.veycad.app.ProjectSourceImportTest' --tests 'com.veycad.app.ProjectFormatTest' --max-workers=1 *> '.superpowers/sdd/2026-10-10-hybrid-mode/a1-targeted.log'
```

Actual: `BUILD SUCCESSFUL in 50s`, `26 actionable tasks: 2 executed, 24
up-to-date`, exit 0. XML parsed from app/build/test-results/testDebugUnitTest:

| Class | Tests | Failures/errors/skips |
| --- | ---: | --- |
| HybridCutConstraintsTest | 7 | 0/0/0 |
| HybridEditCommandsTest | 32 | 0/0/0 |
| HybridIntegrationA1Test | 11 | 0/0/0 |
| HybridProjectCodecTest | 11 | 0/0/0 |
| HybridProjectStoreTest | 8 | 0/0/0 |
| HybridProjectTest | 8 | 0/0/0 |
| ProjectAssetStoreTest | 2 | 0/0/0 |
| ProjectFormatTest | 4 | 0/0/0 |
| ProjectSourceImportTest | 3 | 0/0/0 |
| Total | 86 | 0/0/0 |

`git diff --check` passed. XML counts were read after the final process exited.
No production/test semantics changed after that covering run; subsequent changes
were provenance/report documentation and removal of two trailing blank lines.

Warnings observed: Gradle native-access warning; JDK 25/source-target 8
deprecation warning on intermediate compile; pre-existing
CompletedRenderStoreTest nullable File warnings on the initial full test-source
compile. A new nullable File warning in A1 test was corrected before final run.
Git reports LF-to-CRLF normalization advisories. Initial staged check found two
extra EOF blank lines in imported files; removed them before final staged check.

Full local baseline/JVM/UI/emulator checks were not run under the explicit
coordinator resource override. Exact-commit CI remains required, and no media
rendering/artistic/device acceptance is implied by these JVM checks.

## Self-review and downstream contracts

* Imported value provenance/precise adaptations are in
  `docs/hybrid-public-values-provenance.md`. Only specified immutable owner
  commits were read/imported. Format/Draft checkpoint is reviewed upstream;
  copied text/gallery values still need independent A1 review. No WIP owner code.
* The standalone TextEditProject wrapper and GalleryImportPolicy were not
  imported; core owns reusable text state and inspected source values. Owner
  integration must use these canonical declarations, avoiding duplicate classes.
* Preview owner must adapt to `DraftProject(project)` and selection SourceId,
  deriving geometry from owned immutable physical metadata. Semantic analysis
  bindings and v19/blob-v2 serialization remain Task 5, not silently solved.
* Unknown legacy selection/geometry remains null on reopen and new edits. No
  same-project ID restoration API is exposed; inspect and fork with real facts.
  Raw legacy asset import remains compatible, while known new selections cannot
  attach missing inspected metadata. Gallery/staging must use `importVideo` and
  `SelectedVideo.fromInspected` instead of guessing widths or collapsing choices.
* Selection labels/provenance are table values and immutable after publication,
  matching the approved table guarantee. New selections/removals require a new
  project ID. Physical duration and fingerprint are projected through assetId.
* Encoder and decoder still share `MAX_SOURCE_POINTS = MAX_MANIFEST_BYTES / 12`
  and fixed 12-byte per-point encoding with pre-allocation byte checks. The final
  command suite covers exact 120,000-frame fractional samples beyond the generic
  collection budget; malformed point counts and enormous expansion rejection
  passed. A2 durable full backing/shared long maps are not claimed here.
* Existing store hash comparison permits equal legacy revisions while preserving
  their original v1/v2/v3 blobs, so a new v4 save does not mutate history.
* Controller's separate manual RestoreBaseline integration issue is deferred to
  its own scoped reset API checkpoint. Full RestoreAutomatic remains unchanged
  and is covered with non-default rich original/current payloads.
* Gain above 1 is preserved; renderer headroom/clipping policy is Task 8.
* No currently known failing scoped checks. Independent review and required CI
  are the remaining concerns before integration acceptance.
