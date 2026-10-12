# Restricted prepared Hybrid preview

```kotlin
val prepared = HybridPreviewPreparation.prepare(project, exportSettings, projectAssetStore)
val graph = prepared.graph
val plan = prepared.framePlan
val orderedSources = prepared.sources
// Decode plan frames against orderedSources[frame.sourceIndex].file.
// Use that slot's metadata.geometry for encoded size, rotation and pixel aspect ratio.
```

Preparation accepts only the installed `project.current` revision at its saved 30/60 FPS. It returns project/revision IDs, export settings, an owned graph snapshot, unmodifiable ordered source bindings and the existing `HighQualityFramePlan`. Each binding retains `SelectedVideo` identity/ownership, asset ID, owned file path, SHA-256 and inspected `VideoSourceMetadata` (including geometry and existing physical PTS facts). It stream-verifies every selected file against size/SHA-256, preserving unused slots and distinct selections of one physical file. Null physical timing facts remain unknown; preparation invents no measurements. Legacy missing selection order or metadata is rejected.

This initial seam supports controlled analysis-free fixtures and valid existing manual trim/reorder/plain-cut/flash/local-track edits, subject to the existing compiler's constraints. Global/source analysis frames or mask refinements are rejected explicitly, since exposed plane arrays are mutable. Accepted empty timelines are detached. Current standard automatic winners generally install analysis attachments and therefore fail this guard; analysis-backed automatic preview is one deferred attachment-handoff block. The producer does not silently strip analysis and does not integrate rich text.

The caller must serialize preparation against editing and retain project source bytes unchanged until every preview/export consumer stops; stop consumers before deleting/reimporting project files. Prepared values do not lease files or prevent external replacement. Preparation opens no codec, decoder, GL, muxer or output file. New revisions require new preparation.

The producer retains the full saved selection table. The existing `MediaCodecSpeedRampRenderer` still supports master/secondary files only and rebuilds its deterministic frame plan; it is not wired to consume this prepared instance. Do not drop/deduplicate slots to fit that limit. No renderer, Activity or export integration, on-device playback, or runtime visual acceptance is established by this seam's JVM tests.
