# Additive common QA selector for the custom-music consumer

Authority: coordinator's delegated user decision on 2026-10-11 accepts the
PR37 legacy compatibility gate at abbf7494905a7fa6cb6b0cb0524631392fe0e66a.
The read-only consumer preflight found no P1/P2. This is separate from the
software AAC diagnostic and does not establish native or visual acceptance.

## Constraints

- Edit only the custom-music worktree; preserve the original dirty checkout.
- No local compile, Gradle, emulator, native decode or render processes.
- Use exactly one canonical selector and one legacy event adapter.
- Preserve all models, AudioTrack(sourceId, gain, sourceStartUs), codecs,
  music tail/window timing, thresholds and actual measured clocks.
- Do not import the owner's manual graph, renderer, compiler or main adapter.
- Preserve immutable CI20 native false and separate current measurements.

## Task 1: integrate the pinned selector and collection seam

Import byte-exact RenderQaSampling.kt from PR37's pinned commit (blob
0683539f2bc658b68ecc99e104fe87ef9dda05b6). Install its legacy fixture adapter
tools/fixtures/render-qa-legacy/RenderQaGraphEvents.kt as the sole production
RenderQaGraphEvents.kt (blob a2edaabecf5a3fc0c9d5cd9e3b23e363f6142087).

In RenderedVisualSampler build one HighQualityFramePlan at renderFps, union
the existing raw author samplingTargets with canonical supplied-plan targets,
distinct/sort, and reuse the same plan.frames for decoded-source association.
Keep actual decoder PTS, existing 1us rounding tolerance, decodedSourceTimes,
and held-source provenance. Do not convert requested targets into measurements.

Replace FEAR's extra frame-plan construction with timestamp-only computation:
(index * (1_000_000.0 / FearStrobeProfile.REFERENCE_FPS)).roundToLong(), strictly
before graph duration. Keep raw cadence/duration endpoint, Reference accents
and effects, Heartbeat pulse/node/tail anchors, FEAR pulse/scene anchors.

Add meaningful consumer checks for old anchors remaining in the union at
30/60fps, unchanged FEAR reference clock, canonical BLACKOUT interior frames
and neighbors, and nonzero audio identity retained. Reuse the existing test
patterns. Do not add a second collector or assert only implementation details.
The reviewed owner RED/legacy source pins precede implementation; consumer
tests execute remotely under the existing resource restriction.

## Task 2: review and verify the combined checkpoint

Independently review spec and code quality, including exact imported blobs,
one adapter, supplied-plan reuse and unchanged measured clocks/models. Publish
one scoped commit after lightweight source/syntax checks, then remote baseline
for the final combined head. Report actual results in draft PR18.

The native UI20 graph is 16 seconds / fourteen clips. It needs eight measured
BLACKOUT interior images plus immediate execution neighbors. Unit or owner
17.5-second / five-clip fixtures establish selection contracts only; they do
not prove actual collection, audio/window export, physical-phone acceptance
or fix the immutable colour-jump/native gate. Preserve these explicit gaps.
