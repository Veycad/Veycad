# Live preview integration with the common project

The common project is owned by the Hybrid Mode workstream in [PR #15](https://github.com/rexarmakedonskij-hue/Veycad/pull/15). This adapter adopts the reviewed public model, storage and command checkpoint `95e2ba2a1698290206a5d277e6ff63000d9b45bf`, including A1 selection/visual/text persistence and the codec aggregate-capacity fix. See the [shared contract](../superpowers/specs/2026-10-10-shared-project-contract.md) and [public value provenance](../hybrid-public-values-provenance.md) for exact APIs and remaining integration work.

`DraftProject(project: HybridProject)` is a read-only projection. `HybridProject.id`, `HybridRevision.id`, `nextRevisionId`, `ProjectClock`, assets and `HybridClip.span/sourceMap` remain authoritative. The adapter reads `current.visualSettings` and `current.textState` directly and preserves graph, edited clips, music, normalized text, rich text, style and locked cuts. Revision zero is valid. No parallel visual/geometry/semantic maps, private revision allocator, history or preview project store remain.

`HybridProject.selectedVideos` alone defines the immutable ordered `sourceIndex` table, including unused selections. `SourceId` wraps the selection ID; `SelectedVideo.assetId` refers to the physical asset. Multiple selections may reference one physical video while keeping independent labels, capture provenance and framing. Clip reordering does not reorder this table. Mixed audio/video asset order never defines graph indices. Null selection order is unknown legacy state: the adapter rejects it with `Selected source order is unknown`, without synthesizing IDs or metadata. Restoring inspected legacy bindings requires a new project/fork; published same-ID bindings cannot be rewritten.

`ProjectAsset.videoMetadata` owns encoded dimensions, rotation, pixel aspect ratio, size, MIME, transfer and audio availability. Physical duration and SHA-256 belong to `ProjectAsset`; selection ownership and optional `CaptureOrigin` belong to `SelectedVideo`. Captured take ordinals are 1-based. New selected imports use inspected metadata through `ProjectAssetStore.importVideo` and `SelectedVideo.fromInspected`.

`HybridRevision.visualSettings` persists aspect, explicit format choice and separate parameters for all framing modes per selection/aspect. `withAspect` and `withFraming` allocate values only. Apply a proposal with `HybridEditCommands.commitRevision(project, project.current.copy(visualSettings = proposal))`; core allocates the ID/parent and history, and `HybridProjectStore.save(project, expectedRevisionId)` enforces CAS. No-op consumes no revision; undo/redo selects retained IDs without resetting `nextRevisionId`. Core owns automatic-reset provenance, phase/source samples and the existing bounds on storage/history/codec capacity. Original and retained revision payloads keep both visual and text state; genuine v1/v2/v3 bytes remain readable without a rewrite or invented source table.

The adoption tests exercise actual import, create, save, reopen, stale CAS rejection, revision snapshots, undo/redo and independent framing for repeated physical assets. Synthetic source bytes validate persistence; they do not establish media inspection, native decoding or artistic acceptance.

Still required from the shared runtime/compiler integration:

- `savedOutputTimeUs` as session/runtime state outside undoable composition identity, and the active-draft pointer/replacement policy.
- Immutable compilation/freeze, compact lazy semantic references and shared ownership under the preview's 32 MiB budget. The current eager core analysis limit does not establish this runtime budget.
- Preview/export leases, crash-safe import/cleanup and lifecycle integration; semantic references must come from this reviewed shared runtime rather than a separate adapter map.
- Preparation, factory, export and lifecycle consumers using the common store/compiler without rebuilding manual music, text or cuts.

Preview continues to own framing mathematics, frame planning, GL composition, decoder scheduling and playback control. This dependency adoption does not enable the feature gate or complete runtime/audio/export/UI integration. Exact merged-head CI, device/runtime evidence, physical-device performance and human visual acceptance remain separate gates.
