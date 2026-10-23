# Expanded direct preview implementation plan

> **For agentic workers:** Use superpowers:executing-plans for this assigned part. Do not commit, push, run ADB or heavy Gradle until the coordinating owner schedules the shared verification pass.

**Goal:** Display the installed saved project's complete existing graph, text and audio directly, with the same frame/effect/audio projection as export.

**Architecture:** Extend the existing prepared handoff, two role-owned decoders, GLES compositor and audio owner. One GL-thread budget owns detached analysis/text resources; the common canonical audio snapshot supplies bounded composed PCM for both preview and export.

**Tech Stack:** Kotlin, existing Android MediaCodec/MediaPlayer/GLES, JUnit and instrumentation fixtures.

**Spec:** Issue #66 and the parent task's explicit shared-tree ownership contract.

## Constraints

- No new renderer, store, dependency or duplicate edit authority.
- Preserve raw source PTS, selection slots, canonical frame grid and revision checks.
- Resource release must be acknowledged on its owner before releasing borrowed media/surfaces.
- Root owns AudioMixSettings/codec, PreparedProjectAudio and final source-audio mux; features owns framing/table/ManualMvpActivity and GLES framing passes.

## Tasks

- [ ] Add pure draw-adapter tests for overlap warm-up, reverse/random seeks, temporal source identity and source endpoint clamping; use existing export temporal rules.
- [ ] Extend DraftPreviewController to execute those draw bindings with at most two decoder roles and preserve generation cancellation/presentation checks.
- [ ] Add lossless legacy text geometry/fps alongside immutable rich text and caption projection; retain output-relative end-exclusive clocks.
- [ ] Bind text/authored visibility and canonical audio to PreparedHybridPreview/Request; validate the complete detached handoff before output mutation.
- [ ] Prepare analyzed handoff on the controller's GL owner and preserve that owner through export/disposal; update only agreed UI seams through the UI owner.
- [ ] Compose canonical audio once per snapshot on the existing audio owner, reuse across seeks, and dispose only after playback stops.
- [ ] Extend existing JNI-backed upload storage for retained RGBA text pixels and test denied construction/growth/retry cleanup.
- [ ] Add native pixel/export parity, real audio clock/lifecycle and saved-project recovery cases; report executed verification separately from pending checks.

## Review focus

Delivery verification is host-only under the owner's latest instruction. Native
test sources may be compiled, but no emulator or physical-device tests are run.

- Text-only revision changes must invalidate compositor reuse.
- The first generic overlap image must match export's retained warm-up hold.
- Two temporal roles may refer to the same source at different raw PTS.
- Producer mutation and budget rejection must not alter an already accepted frame.
- Cancellation/recreation must neither present nor start audio from an obsolete generation.
