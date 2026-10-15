# Inspector compatibility with the project core

Static source checkpoint against published core PR #39 head
`1d1dc9432be64316ff050f9d5d7f60d7d1a616ad`. The inspector, duration helper and
MP4 timing parser were copied from published gallery commit
`27e7cdff744c64c5247fca0a357e3b86a9c9c428` (the same blobs in published PR #43
head `632183d0eb6d7811ad04d3d313918e6d72bce47c`). Existing project models,
store, selection and compiler contracts are unchanged.

## API and caller

`MediaInputInspector.inspectForProject(id: String, file: File, name: String): Pair<MediaSource, VideoSourceMetadata>`
returns core source facts and metadata from one inspector/extractor pass over
the copied file. The original public `inspect` and internal copy-fingerprint
overload still return `MediaSource` and allow an unknown nullable raw endpoint.

```kotlin
val (source, metadata) = MediaInputInspector(cancel)
    .inspectForProject(selectionId, copiedFile, name)
val asset = ProjectAssetStore(projectDir)
    .importVideo(source, metadata.geometry.pixelAspectRatio)
val selected = SelectedVideo.fromInspected(source, asset, SourceOwnership.IMPORTED)
```

For the intended manual 1–2 video flow, retain independent selection IDs and
selection order, append each `asset` to `sourceAssets` and each `selected` to
`selections` in the same order, and pass these to the existing
`EditableMontageImporter.create(..., sources = sourceAssets, selectedVideos = selections)`.
This checkpoint does not wire a gallery screen or introduce source analysis.

Geometry uses encoded width, height and rotation from the selected video
track's original `MediaFormat`. Pixel aspect ratio is `sar-width.toFloat() /
sar-height`, using the same keys and arithmetic as core `VideoDisplayOrientation`.
Both keys must be present and positive, or both absent; only when both are
absent does the existing codec convention default to 1:1. Partial/nonpositive
SAR is rejected. No crop, decoder-output dimensions or rotation supplies SAR.
The existing `SourceGeometry` requires a finite positive ratio.

Project inspection rejects inputs outside `isBaselineInput` (AVC/SDR), or
without independently proven raw timing, before callers publish a source
through the store. First PTS comes from the sample scan; exclusive end comes
only from the existing MP4 timing proof. Declared duration or final-frame
cadence never supplies that endpoint. The original decoder probe, cancellation
identity, SHA-256/size guards and timing parser remain in use.

## Status

NOT RUN by explicit user instruction: tests, compilation, lint, syntax checks,
native/emulator/runtime checks, review, matrices and APK rebuild. This is a
source-only compatibility checkpoint; compilation and device acceptance are
unverified. The already delivered gallery MVP APK and PR #43 remain unchanged.
