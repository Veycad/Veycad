# Opening reveal audit — pending renderer correction

Baseline: dc4f0c0, `leonid-multiclass-fixed.mp4`, Android emulator.

Decoded the first three seconds at 100 ms intervals using the new debug-only
`contact_only` mode. Evidence: `artifacts/reference-analysis/opening-audit-fixed-100ms.jpg`.
No diagonal matte stripes are visible. The 2.3 s checkpoint shows the original room
beginning to emerge behind the retained subject. This supports intentional darkness
as the cause of the block score, but does not prove there are no other artifacts.

Two concrete discrepancies remain:

1. QA's foreground-dark-stage exemption ends at progress 0.88 (2.20 s), exactly when
   `GpuTransitionModel` starts revealing the background. A partially revealed background
   can therefore trigger the generic black-block detector. Do not globally disable
   this gate; validate expected background exposure and preserve detection of holes
   inside the subject.
2. `smoothRamp(p, .88f, 1.06f)` cannot complete because `p` is clamped to 1. Its maximum
   is approximately 0.74074. At the transition boundary the renderer switches to the
   complete source plate, leaving a discontinuous exposure step. Correct the envelope
   within the actual window and verify decoded checkpoints around the boundary.

This audit does not establish visual parity. Three-source before/after renders and
reference comparison remain required. Production rendering and QA thresholds were
not changed during the audit.
