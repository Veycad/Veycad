# Bounded prepared Hybrid preview

```kotlin
// Existing analysis-free API remains supported.
val prepared = HybridPreviewPreparation.prepare(project, exportSettings, projectAssetStore)
val graph = prepared.graph
val plan = prepared.framePlan
val orderedSources = prepared.sources
// Decode plan frames against orderedSources[frame.sourceIndex].file.
// Use that slot's metadata.geometry for encoded size, rotation and pixel aspect ratio.
```

For an analyzed winner, enter the existing GL worker and pass **its one caller-owned budget**:

```kotlin
// Reuse the GL-worker budget shared with the other preview resources.
val prepared = HybridPreviewPreparation.prepare(project, exportSettings, projectAssetStore, sharedPreviewMemoryBudget)
val plan = prepared.framePlan // Existing canonical HighQualityFramePlan, built from the frozen graph.
val mask = plan.frames.first().attachments?.mask
val pixel = mask?.get(0) // Allocation-free indexed read; mask.size is also available.
// Stop ALL consumers first, then, on that same GL worker:
prepared.close()
```

Preparation accepts only installed `project.current` at its saved 30/60 FPS. It returns project/revision IDs, export settings, a detached graph snapshot, unmodifiable ordered source bindings and the existing `HighQualityFramePlan`. Each binding retains selection identity/ownership, asset ID, physical file path, SHA-256 and inspected metadata (geometry and existing physical PTS facts). Every selected file is stream-verified against size/SHA-256, preserving unused slots and distinct selections of one physical file. Null timing facts remain unknown; legacy missing selection order or metadata is rejected.

The three-argument API keeps its analysis-free behavior and explicit rejection of analyzed graphs. The four-argument API supports bounded analyzed winners and existing manual edits, subject to the same compiler constraints. It preserves global and every sourceIndex timeline, mask refinements, literal PTS, mask/depth/flow, confidence, face/quality/occlusion/IoU, blend targets/progress and opacity semantics without filtering or changing effects/provenance. Initially empty lists are also detached. Shared plane objects and input arrays/vector lists are frozen once by identity; interpolated planned frames reuse those payloads through existing interpolation and the sole frame planner. SourceIndex keeps its association with the full saved selection table. Rich-text integration remains outside this seam.

Prepared `plane[index]` and `plane.size` do not allocate. `plane.values` is a **detached compatibility copy**, never a backing view; `component3()` and the default `copy()` likewise detach. Producer arrays remain mutable before freezing; constructors/copy/destructuring/value equality remain supported. Flow vectors and timeline/plan/source lists are unmodifiable borrowed views. Retained compatibility arrays or other consumer-created copies are the consumer's memory responsibility. Compositor upload and mask projection use indexed reads without per-pixel defensive array copying.

Before compiler, payload copies and growing snapshot/index/plan allocations, preparation uses checked conservative byte accounting and one pinned reservation in the supplied shared budget. Accounting includes unique plane headers/pixels, vector containers, attachment/list/identity-index metadata, compiler dense timing/maps/tracks, source bindings, hash scratch and canonical/temporary plan metadata. Constant-space identity counting is capped at 1024 total frame/refinement rows across global/source timelines and 64 source timelines before the quadratic scan (at most 9,437,184 plane identity comparisons). Ordinary 50–100-sample sparse timelines fit this row bound; actual byte capacity also depends on other active resources. Oversized history, over-budget or validation failure installs nothing and releases only this handoff's reservation, preserving project/current/source bytes. Some valid large winners cannot fit: this path rejects them and provides no paging or new pool. These reservation allowances do not measure or guarantee the whole app's 32 MiB peak.

The reservation remains pinned for the prepared lifetime, preventing LRU eviction of active values. Construct/use the shared budget and prepare/close on the same GL worker; callers handle worker entry and disposal. Stop all consumers before `prepared.close()` or explicit shared-budget release/clear. Disposal drops owned plane arrays/vector/container storage, invalidates prepared graph/plan/source getters and previously borrowed payload/container reads, and is idempotent. Already retained immutable scalar values cannot be revoked. Closing or budget mutation on another thread is rejected. Preparation opens no codec, decoder, GL resource, muxer or output file.

The caller must serialize preparation against editing and retain project source bytes unchanged until every preview/export consumer stops; stop consumers before deleting/reimporting project files. Prepared values do not lease files or prevent external replacement. Preparation opens no codec, decoder, GL, muxer or output file. New revisions require new preparation.

The producer retains the full saved selection table. The existing `MediaCodecSpeedRampRenderer` still supports master/secondary files only and rebuilds its deterministic frame plan; it is not wired to consume this prepared instance. Do not drop/deduplicate slots to fit that limit. No renderer, Activity or export integration, on-device playback, or runtime visual acceptance is established. New attachment behavior tests are authored but unrun locally under the resource restriction; final exact-head remote baseline/UI gates and independent scoped review remain required.
