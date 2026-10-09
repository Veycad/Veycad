# Independent-frame matte: three-source comparison

Local experiment, 2026-09-05. Not a production-model integration or goal completion.

## What changed the diagnosis

RVM's recurrent state propagated false foreground after a fast pull-back on
Golden 1. Re-running the identical 91-frame / ~30fps sequence with recurrent
state reset before EVERY frame reduced the false wall at source 19,094,821us:
mean alpha in x=[235,270), y=[0,55) fell from .809214 to 0. Central face ROI
x=[100,180), y=[130,250) remained fully opaque in both. These are manually
selected, source-checked ROIs, not ground-truth full-mask accuracy metrics.

Eight adjacent checkpoints, source 19,061,488 through 19,294,819us, were checked.
Seven reset-frame wall ROIs were zero; the first still had mean alpha .211875
(recurrent .869541). Resetting state does NOT eliminate every error, and can
introduce temporal instability elsewhere. No claim of a generally validated
replacement model follows from these results.

## Real rendered comparisons

Each pair uses one analysis/director result and the same graph/track. External
masks are source-SHA256-bound. The diagnostic replacement resamples attachments
at extra mask timestamps; non-mask fields are copied/interpolated from the
original timeline. This is not a bit-exact guarantee for every auxiliary field.
The local 30fps-versus-30fps model comparison above is the clean control for the
recurrent-state hypothesis.

| Source | Before (ordinary app path) | Independent-mask GLES result | Observation from sampled decoded frames |
|---|---|---|---|
| Golden 0 | [MP4](../../artifacts/reference-analysis/matte-reset-g0-baseline.mp4) | [MP4](../../artifacts/reference-analysis/matte-reset-g0-external-matte.mp4) | Hair contour changes, but coarse/pale edge remains; no convincing overall quality leap. |
| Golden 1 | [MP4](../../artifacts/reference-analysis/matte-reset-g1-baseline.mp4) | [MP4](../../artifacts/reference-analysis/matte-reset-g1-external-matte.mp4) | The large wall blob from the rejected recurrent-model render is gone at output .5s; this alone is not proof of improvement over the ordinary app baseline. |
| Golden 2 | [MP4](../../artifacts/reference-analysis/matte-reset-g2-baseline.mp4) | [MP4](../../artifacts/reference-analysis/matte-reset-g2-external-matte.mp4) | Hair remains blunt/coarse after GLES; not reference-quality. |

All three candidates: 542 encoded/shader-traced frames, 72 distinct planned
opening source PTS, final video PTS 18,033,333us and audio PTS 18,018,684us.
Golden 1 uses 91 masks at ~30fps; Golden 0/2 use 30 masks at 10fps, interpolated
by the renderer. This is not a matched-density cross-source quality comparison.
Reports, opening sheets and inspector JSON accompany the files. No new full
perceptual acceptance score is claimed for the external-mask diagnostic.

## Reference comparison (opening scope only)

Uses the local `dexter-matte-reference.mp4` and its 100ms opening contact sheet,
with the existing `LIVE_CUTOUT_RISE` card. This review is based on sampled frames,
not a claim of full-speed human playback review or whole-reference re-analysis.

- Shared grammar: live portrait rises over near-black; original background follows.
- Reference hair is finer and the portrait initially occupies less of the frame.
- Reference has a short layered/echo landing around the background reveal;
  matching that choreography is not established by these mask experiments.
- Our matte edge still loses fine detail; source backlighting also differs.
- No reference pixels/audio were added by this change; fixtures remain local.

[Reference opening sheet](../../artifacts/reference-analysis/dexter-matte-opening-100ms.jpg).

## Next concrete rendering defect

The renderer treats inferred physical alpha as if it were class confidence:
erosion and a rising .66–.89 threshold discard semi-transparent hair. A physical
alpha .5 must mean half coverage, not an uncertain pixel to erase. Introduce
explicit opacity semantics and verify the GPU consumer, leaving the existing
segmentation path unchanged. This is independent of selecting/licensing a
production matting model, which remains unresolved.

## Opacity-consumer implementation and device check

Implemented `FrameAttachments.maskIsOpacity` (default false), propagated through
interpolation and recorded per rendered frame. Confidence and opacity are never
blended across a type boundary. GLES consumes physical opacity directly for
opening/stage compositions; the prior confidence filter remains the default.
Opacity bypasses erosion, thresholding, borrowed edge RGB and confidence-based
edge darkening. Existing app providers still produce confidence masks.

Four unit tests cover fractional-alpha/type preservation, mixed-type boundaries,
backward-compatible defaults and rejection of an opacity claim without a mask.

Real output: `artifacts/reference-analysis/opacity-g1-external-matte.mp4` plus
opening sheet, result and inspector JSON. Same 91 external reset-frame masks;
542 frames, 72 distinct planned opening source PTS, A/V endpoint delta 14,649us.
Inspector records `mask_is_opacity=true` for all 75 opening frames before 2.5s.
This establishes that the new consumer path is exercised, not visual acceptance.

Sampled decoded frames show that the hard cutoff is bypassed, but residual
low-alpha wall contamination at .5s and a broad soft hair halo remain visible.
The candidate is NOT accepted as clean matting. No new model/runtime enters APK.
Next controlled test should preserve full-resolution source RGB for model
refinement while keeping its low-resolution analysis size fixed; the existing
270x480 model input discards source hair detail before inference.
