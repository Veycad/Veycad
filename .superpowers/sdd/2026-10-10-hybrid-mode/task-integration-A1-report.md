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

## Fix round 1 — I1 aggregate rich-history capacity

Status: DONE_WITH_CONCERNS; I1 corrected and covered below, pending independent
scoped re-review and required exact-commit CI. Base was
`6ae1da73717c3524586f1f602cd51340a27fae82`. Read the full independent A1 review.
The controller's modified
`docs/superpowers/plans/2026-10-10-hybrid-integration-checkpoints.md` is excluded
from this fix and its index/commit.

Confirmed issue: writer checked only the per-list limit; reader additionally
spent a hidden 100,000-entry aggregate budget. The demonstrated valid rich
history was unsavable. Existing store preflight already protected CURRENT;
this was **not corrupt publication**. No reset/manual/source-origin changes.

### Decision and capacity contract

Production change is confined to HybridProjectCodec: one private `ItemBudget`
implementation is used by Writer.list and Reader.list. The aggregate capacity is
**262,144 generic collection entries per entire manifest or standalone revision
payload**, summed across every generic list and every retained revision. The
counter does not reset between revisions. Per-list 10,000, manifest/revision
16 MiB, string 1 MiB, analysis bounds, and pre-allocation available-byte checks
remain. Source points continue to use their separate, symmetric fixed 12-byte
point/manifest budget. No physical schema version or byte layout changed.

The 256 Ki-entry ceiling supports both the requested 51 revisions x 2,000 cues
(102,000 cue entries) and the explicitly tested 51 x (2,000 layers + 2,000 cues)
(204,000 rich-text entries), leaving bounded room for their graph/reference
lists. This is an aggregate capacity contract, not a promise that every
combination of the per-list maximum and 50 undo commands fits. Unsupported
aggregate errors explicitly say:

```text
Hybrid payload exceeds 262144 generic collection entries across all revisions
```

Cost: generic parsing/serialization work remains linear in bounded entries and
encoded bytes, with one constant-time budget debit per list. The decoder's
generic-entry ceiling increases by 2.62144x from 100,000, but remains fixed;
there is no per-revision multiplier or unbounded parser. At most 262,144 generic
list entries are materialized before rejection, in addition to the separately
bounded source points and analysis arrays. Object/reference/header and decoded
string overhead still adds to encoded size; the entry limit is not a byte-heap
promise. No device peak-heap claim is made. Tests verified real 4.46 MB/12.10 MB
payloads; unusually dense larger products are rejected. Writer now rejects at
the same aggregate ceiling rather than producing a payload its reader refuses.

### RED and GREEN

Same task-local JAVA_HOME/ANDROID_HOME environment and assigned worktree as
above. All Gradle processes were strictly sequential with `--max-workers=1`;
no toolchain setting changes or daemon stops.

Initial test attempt (before any production fix):

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.HybridCodecCapacityTest' --max-workers=1 *> '.superpowers/sdd/2026-10-10-hybrid-mode/a1-fix1-red.log'
```

Actual: BUILD FAILED in 14s, exit 1, XML 4 tests/4 failures/0 errors/0 skips.
Three failures reproduced I1. The fourth was a test expectation mistake:
truncated primitive input already throws EOFException, while the first test
draft expected IllegalArgumentException. Corrected the expectation, retained
that rejection coverage, and also made the preservation test start from the
real original nextRevisionId=1 so later revisions are otherwise publishable.
Initial XML is saved locally as `a1-fix1-initial-red.xml`.

Confirmed RED after adding both-rich-lists coverage, still before production fix:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.HybridCodecCapacityTest' --max-workers=1 *> '.superpowers/sdd/2026-10-10-hybrid-mode/a1-fix1-confirmed-red.log'
```

Actual: BUILD FAILED in 9s, exit 1, XML 5 tests/4 failures/0 errors/0 skips.
`fullRichHistoryRoundTripsAllFiftyUndoCommands` threw from reader list budget;
`fullRichHistoryCreatesSavesReopensAndRetainsRevisionSnapshots` threw in create
preflight; `twoRichListsPerRevisionFitTheSameBoundedHistoryCapacity` failed the
same decode path; `unsupportedAggregateIsRejectedByBothCodecsWithoutMovingCurrent`
failed because the writer accepted the excessive aggregate. Malformed/per-list
bounds test passed. Saved XML: `a1-fix1-confirmed-red.xml`.

Focused GREEN after the shared-budget implementation:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.HybridCodecCapacityTest' --max-workers=1 *> '.superpowers/sdd/2026-10-10-hybrid-mode/a1-fix1-green.log'
```

Actual: BUILD SUCCESSFUL in 31s, exit 0, 5 tests/0 failures/0 errors/0 skips.
Then strengthened the test-only external writer's validity check and explicit
malformed per-list reader coverage before the final covering run.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.HybridCodecCapacityTest' --tests 'com.veycad.app.HybridIntegrationA1Test' --tests 'com.veycad.app.HybridProjectTest' --tests 'com.veycad.app.HybridProjectCodecTest' --tests 'com.veycad.app.HybridProjectStoreTest' --tests 'com.veycad.app.HybridEditCommandsTest' --tests 'com.veycad.app.HybridCutConstraintsTest' --tests 'com.veycad.app.ProjectAssetStoreTest' --tests 'com.veycad.app.ProjectSourceImportTest' --tests 'com.veycad.app.ProjectFormatTest' --max-workers=1 *> '.superpowers/sdd/2026-10-10-hybrid-mode/a1-fix1-targeted.log'
```

Actual: BUILD SUCCESSFUL in 45s, `26 actionable tasks: 2 executed, 24 up-to-date`,
exit 0. XML totals: **91 tests, 0 failures, 0 errors, 0 skips, 10 classes**.
Counts: HybridCodecCapacityTest 5, HybridCutConstraintsTest 7,
HybridEditCommandsTest 32, HybridIntegrationA1Test 11, HybridProjectCodecTest 11,
HybridProjectStoreTest 8, HybridProjectTest 8, ProjectAssetStoreTest 2,
ProjectFormatTest 4, ProjectSourceImportTest 3. XML was parsed after the owned
process completed. Capacity suite runtime was 4.252 seconds in this run.

Recorded capacity outputs:

```text
two rich lists: revisions=51, layers=2000, cues=2000, bytes=12097131
rich history: current=50, undo=50, cues=2000, bytes=4458351
unsupported aggregate: cues=306000, bytes=13434351, reason=Hybrid payload exceeds 262144 generic collection entries across all revisions
```

Test text/IDs differ from the review probe's 4,004,961-byte payload; counts,
valid intervals and 51-revision history are the same required case. Both rich
lists round-trip in 12,097,131 bytes below 16 MiB. Store coverage creates a full
history, saves another core-allocated revision, reopens through a fresh store,
loads original and export revision snapshots, and persists undo/redo state.

The unsupported 306,000-cue payload is 13,434,351 bytes, also below 16 MiB. It is
independently assembled from individually valid v4 revision blobs to exercise
reader aggregate rejection even though the paired writer now refuses it. The
same test helper successfully decodes the supported history, ruling out a broken
test manifest. Both codecs reject the excessive aggregate. Store save leaves
CURRENT, all prior manifest/revision files, their bytes and file set unchanged.
Per-list overflow, malformed string/list counts, unknown physical version,
truncation and over-limit byte array also reject. Existing source-map bounds and
v1/v2/v3 fixture tests passed in the final run.

### Review disposition and remaining work

I1 is addressed in this scoped fix. No production/test semantics changed after
the final covering run; only this report was appended afterwards. Reviewed the
scoped staged diff and ran whitespace validation before commit.

M1 remains explicitly assigned to runtime C's resource/lease ledger, recorded in
`docs/hybrid-public-values-provenance.md`: a rejected inspected import may leave
an unreferenced content-addressed file. Cleanup must prove a target is orphaned
relative to selections/history/leases before deletion; never blindly delete an
existing deduplicated file. This fix does not modify source import behavior.

M2 remains disclosed: Gradle native-access warning is in final output; JDK 25
source/target 8 deprecation warnings appeared on intermediate compilation.
Pre-existing CompletedRenderStoreTest nullability warnings remain part of the
earlier evidence; no tracked toolchain change was made. Git LF-to-CRLF notices
are normalization advisories.

Controller independently audited imported fields/defaults against the immutable
owner sources and confirmed the existing MediaSource provenance SHA was already
correct; no SHA correction was needed. No further DTO import changes occurred.

Full local baseline/JVM/UI/emulator/device checks remain excluded by the current
resource override. Independent scoped re-review and full exact-commit CI remain
pending. No push/merge or peer changes. The unrelated controller plan edit stays
uncommitted and outside this fix.
