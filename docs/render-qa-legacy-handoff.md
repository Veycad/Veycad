# Common render QA selector: music compatibility handoff

This is an additive source-level handoff from base
`a32a6a849abb3c781c21790c409b484f0fb65488` to the immutable music checkpoint
`20d64c5a5aaea2e2f0b4966fc6f01f0ed09fe0e5` (PR18). It does not integrate the
music collector or establish native MP4 quality. Geometry PR35 is independent.

**Checkpoint status:** meaningful legacy RED is recorded (22 errors confined to
the old selector's unavailable manual API). Actual legacy GREEN and the five main
JVM test classes are **pending**. The shared resource queue currently prohibits
new local compilation. This document describes the prepared integration contract
and required reproduction, not verified compatibility or completed acceptance.

## Files and callable contract

All Kotlin files use package `com.veycad.app`. Production paths are under
`app/src/main/java/com/example/autoedit/`:

* `RenderQaSampling.kt`: the only selector, frame index, transition windows and
  event interval resolver. The unchanged entry point is
  `RenderQaSampling.targets(graph: MontageGraph, plan: HighQualityFramePlan.Plan, intervalUs: Long): List<Long>`.
* `RenderQaGraphEvents.kt`: main adapter, using actual `renderOverlays()` and
  `renderNodes()` filtering, `EffectSampleWindow.contains`, and the existing
  authored peak expression. It returns plain resolved immutable index windows.
* `tools/fixtures/render-qa-legacy/RenderQaGraphEvents.kt`: alternative adapter
  for music20d64 raw `overlays` and `effectGraph.nodes`. Compile this **instead
  of** the main adapter. Both provide the exact internal seam
  `MontageGraph.renderQaEvents(index: RenderQaFrameIndex): List<RenderQaEventWindow>`.

The common `RenderQaEventWindow.resolve(index, startUs, endUs, peakUs = null,
contains = { it in startUs until endUs })` resolves an interval once against the
supplied frame list. Its output has `first`, `last`, and optional `authoredPeak`
indices. The membership predicate is consumed immediately, never stored in the
output. Main passes the exact translated membership; legacy uses the half-open
raw interval. No adapter owns cadence, thresholds, transition policy or author
anchor policy. No reflection, model shims or duplicated selector are required.

The selector consumes the supplied saved-FPS plan without rebuilding it. It
returns sorted unique **planned output PTS** with first/last, cadence, clip
boundaries, retained transitions, phase/matte landmarks and event witnesses.
Transition windows of up to eight frames include every retained frame and both
neighbors; larger windows remain bounded. Frame association keeps the earlier
frame on equal-distance ties. Translated event membership can differ from
display bounds by one microsecond; rounding those bounds is not equivalent.

## Music consumer integration recipe (music owner)

Keep music20d64's complete models, including
`AudioTrack(sourceId: String, gain: Float = 1f, sourceStartUs: Long = 0L)` and its
validation. Take the shared selector plus the legacy adapter only. Do not take
main's graph, store, compiler, manual runtime, or a second collector/compositor.

In `RenderedVisualSampler` collection, build one HQ plan using the actual
`renderFps`, then reuse it for common witnesses and the existing decoded-source
association:

```kotlin
val plan = HighQualityFramePlan.build(graph, renderFps)
val sampleTargetsUs = (samplingTargets(graph, intervalUs) +
    RenderQaSampling.targets(graph, plan, intervalUs)).distinct().sorted()
val framePlan = plan.frames.map { frame ->
    withDecodedSourceTime(frame, graph.frameAttachments, decodedSourceTimes[frame.outputTimeUs])
}
```

Here the existing `samplingTargets` remains responsible for literal raw cadence
and Reference/Heartbeat/FEAR author anchors. Preserve all these anchors when
adding common witnesses. Its current FEAR branch builds another HQ plan at the
reference FPS solely to extract timestamps: replace that operation with the
same reference-clock timestamps (`roundToLong(i * (1_000_000.0 / REFERENCE_FPS))`,
stopping before graph output duration). This preserves the authored grid even
for a saved 60fps export while avoiding a second HQ plan. Preserve its existing
duration endpoint and pulse/scene anchors. This is a recipe for owner integration,
not code already applied to the legacy collector.

Keep source, held-frame and actual decoded PTS provenance and measurement
thresholds unchanged. Common planned targets are not proof that the requested
textures were decoded/measured. After branch integration, retain only the
canonical main adapter; never compile both same-signature adapters together.
The main consumer is unchanged by this handoff: automatic raw author anchors
remain additive, and manual graphs continue their filtered policy.

## Reproduction and source provenance

Run sequentially from the repo root with JDK25 in `JAVA_HOME`, Python3 and the
already populated Gradle dependency cache (Kotlin2.2.10). The tool is offline:

```powershell
. ./.superpowers/sdd/2026-10-10-manual-montage/env.ps1
python tools/verify_render_qa_legacy.py --mode red
python tools/verify_render_qa_legacy.py --mode green
```

Outside this task's locally ignored `.superpowers` directory, use
`--evidence artifacts/render-qa-legacy` (ignored by the repository) for each mode
and set `JAVA_HOME` directly to the installed JDK25 if the task env file is absent.

`tools/verify_render_qa_legacy.py` writes full byte-preserved `git show` snapshots
of 14 actual pure production dependencies to ignored task evidence. It pins
and verifies these blobs, and records every source's blob/SHA256, compiler
dependency hashes, Java version, exact argv, logs and result in `manifest.json`:

| Immutable music source | Git blob |
| --- | --- |
| MontageGraph.kt | `b1cc349b2555f4058c2f0f994ff3ae279f814c70` |
| HighQualityFramePlan.kt | `2320bb273eb2d94fd62df85171f4216618a23972` |
| TransitionTimeline.kt | `b36fc7fba702b4e37beb93566c8ab8f6f0c517e1` |

RED compiles the actual base selector and requires errors confined to its
manual-only dependencies, not missing legacy sources. GREEN compiles the working
selector and legacy adapter against the same immutable sources and executes
`tools/fixtures/render-qa-legacy/LegacyRenderQaCheck.kt`. Heap limits are 512MiB
for the standalone compiler and 256MiB for execution, each with a timeout.

The check uses real 30/60fps plans and `AudioTrack("custom-music", 2f, 1_234_567L)`.
It asserts unchanged audio identity/offset/gain and visual/source clocks,
sorted unique supplied-plan PTS, all eight literal 30fps BLACKOUT interior PTS
(1_533_333, 1_566_667, 5_533_333, 5_566_667, 9_533_333, 9_566_667,
13_533_333, 13_566_667), seven retained 60fps frames per cut plus neighbors,
8/9-frame cap, long foreground matte phases, tie behavior, one-frame effects,
offscreen effects, short clips and bounded long events. No copied graph/plan/
transition definitions or selector algorithm are tracked in the fixtures.

Main regressions: `AutomaticRenderQaSamplingTest`, `ManualQaSamplingTest`,
`ManualRenderOverridesTest`, `ManualMontageAcceptanceTest`, and
`RenderQaGraphEventsTest` (exact translated membership and authored peak).
Existing assertions, QA floors and source/global/original/local clocks remain
unchanged. Results and any environment limits are recorded in the task report.

## Acceptance boundaries

The music owner still owns consumer integration and real native evidence for
the eight interior PTS and neighbors. Custom-music false/device/native/artistic
acceptance, manual persistence and common core/renderer gates remain open.
Full compiled-window acceptance remains Task8B until explicit clock/context
evidence exists. A successful standalone compile/target check or main JVM suite
does not satisfy these gates. Root coordinates independent review, music/core
joint review and publication.
