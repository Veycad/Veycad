# Live preview integration with the common project

The common project is owned by the Hybrid Mode workstream in [PR #15](https://github.com/rexarmakedonskij-hue/Veycad/pull/15), based on core commit 74b58038abedc918c0877cfa3cec215a0f4d9a9e. HybridProject.id, HybridRevision.id, HybridProject.nextRevisionId, ProjectClock, ProjectAsset.id and HybridClip.span/sourceMap are authoritative. Preview does not allocate identity, maintain history or persist a separate project.

Alignment reference: the core owner's docs/superpowers/specs/2026-10-10-shared-project-contract.md distinguishes these existing model APIs from pending persistence, command, compiler and resolver extensions. The durable fields described below are integration requirements, not APIs implemented in this checkpoint.

DraftProject is a read-only adapter receiving the common project, visual settings, an explicit ordered list of video asset IDs, source geometry and optional semantic asset references. The video order binds legacy graph sourceIndex to ProjectAsset.id independently of mixed video/audio asset order. The adapter keeps graph, edited clip spans/source maps, music, text, style and locked cuts from the received core revision. Core revision 0 remains valid. The core owner must supply immutable graph/compact semantic sidecar snapshots through the pending freeze/compiler boundary; preview does not duplicate full planes.

ProjectVisualSettings proposes aspect, explicit format selection and per-source/per-aspect/per-mode framing values. withAspect and withFraming return values only. The planned common command will apply a proposed value to a revision, allocate nextRevisionId, record history and return the saved revision. Persistence and commands are not implemented by this task.

The common durable contract must include:

- Revision visualSettings and the stable video source order used by legacy graph indices.
- Per-video encoded size, rotation and pixel aspect ratio; ownership (IMPORTED/CAPTURE) and optional CaptureOrigin with a 1-based take ordinal.
- Semantic asset references and leases for preview/export, tied to existing asset IDs and a frozen revision.
- savedOutputTimeUs in common session/storage state, separate from composition identity.

The common owner will implement atomic replacement, restoration, CAS, leases and command APIs in the common store. Preparation, export and lifecycle integration will consume that store through the adapter. Preview owns framing mathematics, frame planning, GL composition, decoder scheduling and playback control. Independent geometry/render work continues against these value contracts while common persistence is integrated.
