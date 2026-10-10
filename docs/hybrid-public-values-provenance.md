# Hybrid A1 public value provenance

This checkpoint imports only the following owner values. The text/gallery imports
remain part of the independent A1 review surface; copying a committed declaration
does not certify its review status.

| Source checkpoint | Imported declarations | Integration adaptation |
| --- | --- | --- |
| `df81b2d2bbaf559b10439648830f7516914ea677`, `ProjectFormat.kt` | `ProjectAspect`, `ExportQuality`, `OutputSize`, `ProjectFormatSelection`, `ProjectFormats` | Original fields, defaults, ranges and recipe policy preserved. General hybrid revision default is portrait; recipe factory wiring belongs to Task 6. |
| `df81b2d2bbaf559b10439648830f7516914ea677`, `DraftProject.kt` | `SourceId`, `SourceGeometry`, `SourceOwnership`, `CaptureOrigin`, `FramingMode`, `FramingSettings`, `FramingKey`, `SourceFramingSettings`, `ProjectVisualSettings`, `DraftVersion` | Value fields/defaults/ranges preserved. `SourceId` now means selection identity, not physical asset identity. `ProjectVisualSettings.copy` defensively snapshots its map. |
| Same DraftProject checkpoint | Read-only `DraftProject` projection contract | Constructor is now `DraftProject(project)`. Visuals, ordered selections, geometry and duplicate physical assets derive from the core; unknown legacy order fails explicitly. No second state, history, allocator, or standalone semantic references. |
| `50da56de6f1c5d3da5b241c5f5daa3eebe4577a9`, `TextEditProject.kt` | `TextPosition`, `TextFont`, `TextAnimation`, `TextStyle`, `TextLayer`, `CaptionCue` | Original fields/defaults/ranges preserved. Reusable owned `HybridTextState` contains layers/captions/style/edit flag/language; no standalone `sourcePath`, width, height or duration is persisted. The standalone `TextEditProject` wrapper/store is not imported. |
| `2f0e290a5877daaa66939d1c3d81094d84623cfa`, `MediaSource.kt` | `MediaSource`, `MediaSourceSet` | Declarations preserved. No inspector, gallery policy, activity, renderer, STT or store imported. `ProjectAssetStore.importVideo` accepts this inspected owner DTO. |

`3e72c4fe93d0c47015ae6e171d1df3997624cea9` is the approved framing
geometry implementation checkpoint, but A1 imports no rendering/geometry algorithm
from it. `ProjectFormatTest` starts from the format owner's committed test and adds
recipe/explicit-choice preservation coverage. Other A1 tests cover the imported
DTO validation at their core/storage boundary.

## Canonical ownership and legacy behavior

* `HybridProject.selectedVideos: List<SelectedVideo>?`: index is the durable
  graph `sourceIndex`; null means legacy order is unknown. Every known selection
  has its own `SourceId`, `displayName`, `ownership`, optional `CaptureOrigin`,
  and immutable `assetId`. Equal fingerprints do not merge selections. Unused
  selections remain in the table. A capture requires an origin and an imported
  selection cannot claim one.
* `ProjectAsset.durationUs` and `contentHash` own the physical duration and
  fingerprint. `ProjectAsset.videoMetadata: VideoSourceMetadata?` owns geometry,
  size, MIME, color transfer (nullable when unknown), and audio availability.
  Null means uninspected legacy metadata. A known selection requires metadata
  and a SHA-256 physical fingerprint.
* `ProjectAssetStore.importVideo(MediaSource, pixelAspectRatio)` verifies file
  size and copied bytes against inspection. Physical deduplication uses the
  existing content identity; a stable physical display name avoids conflicting
  records for duplicate provider labels. `SelectedVideo.fromInspected` retains
  the provider's actual selection label and checks its physical binding.
  The existing raw import method remains for legacy callers/audio; new selected
  video assembly must use inspected values and cannot attach unknown metadata.
* `HybridRevision.visualSettings` owns all four aspects, explicit-choice flag,
  and all three saved framing modes per selection/aspect.
* `HybridRevision.textState: HybridTextState` owns rich layers, cues, styles,
  `captionsEdited` and `auto`/`ru`/`en`. Times are absolute project microseconds
  with an exclusive end. Project validation uses its single `ProjectClock`.
  Existing normalized `texts: List<TextItem>` remains separate and preserved.
* Edits use `HybridEditCommands.commitRevision(project, current.copy(...))`.
  Core allocates the ID/parent; `HybridProjectStore.save` performs CAS. No editor
  can import a second history or counter. Ordinary edits clear the inherited
  reset event exactly as Task 3 defines; full `RestoreAutomatic` is unchanged.
* Selection table, including labels and provenance, is immutable after publish.
  Same-ID restoration of unknown legacy order or asset metadata is rejected;
  inspect and create a new project/fork instead. Asset records and old snapshot
  bindings remain immutable even after history pruning.
* Physical codec v4 reads genuine v1/v2/v3 bytes. Legacy new fields decode as
  null order/metadata, portrait non-explicit visuals with no framing, and empty
  rich text with automatic language. Original graphs, phases, source samples,
  reset events and normalized text are unchanged. Unknown versions fail without
  a write. `ProjectMusic.gain` accepts `0..2` without clamping.

Manual payload/backing/shared maps (A2), staging/factory assembly (B/Task 6),
runtime leases (C), rendering, UI and STT remain separate tasks. The renderer
must handle headroom explicitly for gain above one.
