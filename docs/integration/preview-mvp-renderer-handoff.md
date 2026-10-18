# Restricted prepared MANUAL export

Stable public API (package `com.veycad.app`):

```kotlin
PreparedHybridExportAdapter.request(
    prepared: PreparedHybridPreview,
    installedProject: HybridProject,
    outputFile: File,
    audioFile: File? = null,
    context: Context? = null,
    checkCancelled: () -> Unit = {}
): MediaCodecSpeedRampRenderer.Request
```

On the caller's background worker, serialize editing, preparation and request creation:

```kotlin
val installed = project // installed immutable current revision under the editing owner
val prepared = HybridPreviewPreparation.prepare(installed, savedExportSettings, assetStore)
val request = PreparedHybridExportAdapter.request(
    prepared, installed, outputFile, audioFile = selectedLocalMusicFile,
    context = context, checkCancelled = checkCancelled
)
val frames = MediaCodecSpeedRampRenderer.render(request)
// Alternatively: renderAsync(request, callerExecutor) { outcome -> ... }
```

This seam is export only. Direct Surface preview and UI integration belong to their separate owner. It creates no proxy preview, revision, store, compiler, renderer, decoder pool or factory. The legacy renderer request continues to build its ordinary plan.

The request consumes the exact `prepared.graph` and `prepared.framePlan` objects, without rebuilding the frame plan, and derives dimensions, video bitrate and saved 30/60 FPS from `prepared.exportSettings`. Every prepared selection slot remains in order, including repeated physical files with distinct `selection.id` values and unused selections. The adapter reads and snapshots `installedProject.current.visualSettings`, checking project/revision IDs, saved FPS, selection order, metadata and content identities against preparation. Matching IDs alone do not prove identical content: the caller must pass the same installed revision used for preparation and serialize these operations against edits. New edits require new preparation/request creation.

Only active MANUAL source framing is accepted. All four project aspects are supported at exact 720/1080 short-edge profiles; aspect mismatches, stretch, 4K, SMART_PERSON and BLURRED_FIT reject explicitly, including unsupported modes on unused selected slots. Missing active framing uses the existing MANUAL default. Existing `FramingPlan`/`ManualFramingTable` preserve each selection's centers/zoom and `metadata.geometry` (encoded dimensions, rotation, SAR). That table goes to the same GLES compositor for incoming, outgoing and temporal source roles; prepared exports do not re-inspect geometry to replace it. Existing graph effects, overlays and temporal layers are retained, not cleared to make admission succeed.

Admission validates graph/frame/source bindings, frame grid and duration, inspected endpoints when known, target profile, existing source sizes, input/output path separation and any supplied music path before opening encoder/output. Preparation has already SHA-256 verified every selected source. Keep those exact source bytes unchanged until **every** preview/export consumer stops; stop consumers before deleting, replacing or reimporting files. This seam does not lease files or rehash against external replacement. Unknown physical endpoints remain unknown.

The producer and adapter explicitly reject analysis-backed inputs; normal analysis-backed automatic winners are unsupported. The adapter rejects revision `texts`, rich text layers and caption cues instead of losing them. Authored effects already present in the graph keep the existing renderer behavior; this API does not introduce text/STT rendering.

`audioFile` uses the existing local AAC music path, looping/cutting to the exact prepared duration. Null deliberately produces video without audio; `context` only enables existing local diagnostics, not resource-music selection. Supply the resolved local original music file yourself. The adapter accepts only music unchanged from the original revision, zero offset, unity gain, zero fades and repeat enabled; music changes reject. It does not apply graph gain, source-video audio, STT or editable audio mixing. The existing AAC encoder uses its own 192 kbps default, not `ProjectExportSettings.audioBitrate`; that saved audio bitrate is not an implemented control in this seam.

The caller owns one encoder/export operation at a time and background execution. Cancellation uses the existing `checkCancelled` callback through admission, video decoding/encoding and local music muxing. An admission failure leaves an existing output untouched. After admission, synchronous prepared-export failure/cancellation removes output and `.video.mp4` intermediate; native owners retain their existing `finally` release. Use a dedicated export destination, never a source file. Async failure retains the existing outcome/cleanup contract.

Initial JVM binding/geometry candidates and one real encoded MP4 pixel/timestamp/cancellation fixture were authored before production edits. Input-alias and explicit temporal-role assertions followed during static review. Local compile, tests, Gradle, NDK, ADB, emulators and renders are **UNRUN** under the closed RAM queue. `tools/run_ui_tests.ps1` discovers `PreparedHybridExportDeviceTest` by its existing `*Test.kt`/`@Test fun` rules. Root runs exact-head remote baseline/UI CI after publication. Baseline now archives its existing ARM64 debug APK only after success, using generated output metadata to select the ABI; this backend APK does not establish a completed live-preview UI or device/artistic acceptance.
