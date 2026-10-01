# Live opening regression — 2026-09-07

Local emulator API 36, arm64. Inputs: original `veypad-test-0/1/2.mp4`
fixtures. Outputs and reports: `artifacts/reference-analysis/face-supported-g*-0907*`.
Current uncommitted implementation, based on commit `1ba335c`.

## Observed results

| Source | Selected mask | Opening requested source PTS | Edge leak | Minimum temporal IoU | Visual finding |
| --- | --- | --- | --- | --- | --- |
| Golden 0 | baseline | 72 distinct | 0.0001643 | 0.98885 | Protected refinement removes black eye patches at 2.0 s; final selected output remains baseline. Hair halo remains. |
| Golden 1 | refined | 72 distinct | 0.0002555 | 0.92460 | Face remains readable; extra light protrusion above crown at 1.0 s compared with baseline. Not visually accepted. |
| Golden 2 | baseline | 72 distinct | 0.0003867 | 0.97792 | No clear improvement from refinement in inspected opening sheets. |

All outputs have 542 frames, duration 18.034 s, AAC/video initial PTS zero and
14,649 us A/V drift. Each overall acceptance report fails on final subject-stage
background isolation (maximum luma 0.11350 / 0.16569 / 0.10771 versus limit 0.09).
These failures are at 16.5–17.2 s, not evidence that the opening passes visually.

Timestamp evidence correction: the existing 72-PTS counter records requested
source times, not actual decoded texture timestamps. It does not by itself prove
72 different live frames. The inspector now records `decoded_source_us` and
`decoded_secondary_source_us` directly from SurfaceTexture after update, plus
the requested-to-decoded offset. Missing measurements are JSON null, never
silently substituted with planned timestamps. New measurement run:
`decoded-clock-g1-0907`; previous reports must not be retroactively interpreted
as containing actual decoder PTS.

Golden 2 diagnostics explain selection: baseline score 0.48089918, refined
0.47197828. Refined edge leak is 0.0006345, temporal IoU 0.97848, whole-video face
loss 0.03448 versus baseline 0.02759. Refinement changes only opening masks, yet
ranking also includes whole-video face detection; do not infer that this face
loss difference proves a new visible face defect in the opening.

## Reference comparison and limits

Reviewed decoded opening sheets and the local reference's 100 ms sheet. Both
use an isolated rising subject followed by background return. Current output has
broader/light-contaminated hair edges and occasional spurious contours. The
reference has a tighter visible silhouette. The reference also holds a different
head scale/entrance position; silhouette quality alone will not establish timing
parity. These are still-frame observations, not a completed 1x/0.5x temporal audit.

The local HTML comparison links the actual MP4s and allows 0–3 s review at 1x and
0.5x, including selected versus refined candidates. No percentage match is claimed.

## Changes verified

- Protect supported face interiors in exact-frame masks without freezing RGB.
- Tie-break otherwise equal candidate scores by decoded matte quality.
- Bind exported inspector and sheets to the selected candidate.
- Log both candidate scores and matte metrics locally for selection diagnosis.
- Avoid copying a Golden output onto itself during export.

All 136 unit tests, lint and assembly pass. APK inspection found no MP4/Golden
fixtures. No new models, dependencies or shader effects added.

## Next required work

1. Diagnose the Golden 1 crown protrusion using exact-source masks and nearby
   temporal samples; current sparse QA misses this visible defect.
2. Separate genuine fine hair from bright source-background contamination on
   Golden 0/1, with local pixel comparisons before changing spill suppression.
3. Retain exact-frame refinement in production only if it demonstrates a stable
   visual benefit; more inference is not itself an improvement.
4. Complete temporal before/after/reference review on all three outputs. Goal
   remains unachieved.
