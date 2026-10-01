# Three-source opening review

Target: clean, continuously moving person entrance and original-background return in
the first three seconds of Golden 0, 1 and 2. No percentage of visual parity is assigned.

## Candidate

`GpuTransitionModel` background ramp now ends at progress 1 instead of 1.06.
The old clamped ramp reached only 0.74074 before the full-source branch took over.
Unit tests require a complete, monotonic reveal and less than 4% remaining exposure
step from the last 30 fps transition frame. No QA thresholds changed.

## Evidence and limitations

| Source | Historical comparison | Current full MP4 | Review |
|---|---|---|---|
| Golden 0 | `opening-midcurve-g0-101.mp4` | `reveal-complete-g0.mp4` | 72 distinct opening source PTS; edge leak 0.000189; IoU 0.9883; no black-block flag. Hair still has a visible pale rim against black. Full-MP4 gate fails on finale isolation outside the opening. |
| Golden 1 | `opening-midcurve-g1-102.mp4` | `reveal-complete-g1.mp4` | 72 distinct opening source PTS; edge leak 0.03206 fails the 0.02 gate; IoU 0.91306; no black-block flag. Pale hair fringe and extra background near the crown remain visible. Finale isolation also fails. |
| Golden 2 | `opening-midcurve-g2-103.mp4` | `reveal-complete-g2.mp4` | 72 distinct opening source PTS; edge leak 0.000627; IoU 0.97837; no black-block flag. Hair edge remains coarsely defined, though less pale than Golden 0/1. Full-MP4 gate fails on finale isolation. |

All named media are local under `artifacts/reference-analysis`.
Historical comparisons predate multiclass integration: they are useful before/after
examples, but cannot isolate the reveal-curve change from changes in segmentation.
An exact same-build control is still needed for causal image-level comparison.

Reference: `dexter-matte-opening-100ms.jpg`. It shows live portrait entrance on black,
followed by the source background, with offset silhouette echoes around the landing.
The current checkpoint review does not establish identical motion or fine-hair quality.

Goal remains open until all three current MP4s are reviewed, blocking edge defects
are corrected and before/after plus reference comparisons are delivered.

## Local visual comparison

Historical rows include intervening segmentation changes; this is not an isolated A/B test.

| Source | Earlier opening | Current opening |
|---|---|---|
| Golden 0 | ![Earlier Golden 0](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/artifacts/reference-analysis/opening-midcurve-g0-101-opening.jpg) | ![Current Golden 0](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/artifacts/reference-analysis/reveal-complete-g0-opening.jpg) |
| Golden 1 | ![Earlier Golden 1](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/artifacts/reference-analysis/opening-midcurve-g1-102-opening.jpg) | ![Current Golden 1](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/artifacts/reference-analysis/reveal-complete-g1-opening.jpg) |
| Golden 2 | ![Earlier Golden 2](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/artifacts/reference-analysis/opening-midcurve-g2-103-opening.jpg) | ![Current Golden 2](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/artifacts/reference-analysis/reveal-complete-g2-opening.jpg) |

Reference checkpoints (different source framing; do not interpret scale as pixel parity):

![Reference](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/artifacts/reference-analysis/dexter-matte-opening-100ms.jpg)

## Validation

All three full exports finished: 542 frames, 18.034 seconds, A/V drift 14,649 us.
`testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `git diff --check` passed.
APK archive inspection found no MP4 or Golden fixture entries. The existing three
lint warnings remain. No QA limits were relaxed. This is partial progress, not
completion: visible hair spill and Golden 1's rejected edge remain next priorities.
