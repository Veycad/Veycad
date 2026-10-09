# Semantic support for physical alpha — 2026-09-05

## Hypothesis

Both independent RVM and MODNet sometimes retain the curtain/wall as foreground.
Test whether the existing person segmentation can veto false alpha away from
the person without thresholding the physical opacity of hair itself. This is
not a new production model, and it does not recover foreground colour.

The single-frame probe uses classes 1..4 (no accessories). The sequence probe
uses the actual `PersonMaskUnion.combine` production union, including its
supported-accessory rule. These two controls are not identical.

## Implementation and controlled inputs

- Added opt-in debug export of `person-<sourcePTS>.png` from the production union.
- Support is confidence >= .5, dilated five semantic pixels, softened one pixel,
  resized to alpha dimensions and multiplied into the physical alpha. No opaque
  core is fabricated. The margin is an experimental parameter, not a calibrated
  production choice.
- Exported 30 / 91 / 30 frames from Golden 0 / 1 / 2 in the Android emulator.
- All 151 exported source PNG hashes match the corresponding earlier RVM input
  PNGs. Golden 1 is ~30fps; the other two are 10fps. Do not treat this as equal-
  density cross-source temporal acceptance.
- External alpha manifests are bound to the full input MP4 SHA256. Missing PTS,
  incompatible dimensions, wrong video hashes and invalid planes are rejected.

## Results before GPU

On Golden 1, source PTS 19,061,488us, the manually selected upper-right wall ROI
x=[235,270), y=[0,55) falls from mean alpha .211875 to 0. Face ROI
x=[100,180), y=[130,250) remains 1.0. This is a local background-exclusion win,
not proof that all foreground is retained.

At 18,994,822us the same upper-right region falls from .846325 to .468969, and
the sampled comparison still visibly retains background above/right of the head.
Both predictors are wrong there; their agreement is not independent ground truth.
Do not accept the first checkpoint while hiding this adjacent failure.

Maximum removed alpha mass: Golden 0 .005753, Golden 1 .023239, Golden 2 .008168.
These are change magnitudes, NOT quality scores. Golden 0/2 sampled comparisons
show little visual improvement; pale/coarse hair boundaries remain.

- [Golden 0 diagnostic sequence](../../artifacts/reference-analysis/rvm-supported-sequence-g0/sequence.gif)
- [Golden 1 diagnostic sequence](../../artifacts/reference-analysis/rvm-supported-sequence-g1/sequence.gif)
- [Golden 2 diagnostic sequence](../../artifacts/reference-analysis/rvm-supported-sequence-g2/sequence.gif)

Columns: source / independent RVM alpha / constrained alpha. GIFs are local
source-time diagnostics, not final montages. Only selected still checkpoints
were visually inspected in this review; no full-speed temporal acceptance claimed.

## Reference gap

Rechecked the local 100ms opening contact sheet and `LIVE_CUTOUT_RISE` card using
the TikTok analysis workflow. Still-frame review of the opening only: reference
has a smaller portrait, a narrow lit hair contour and short layered landing at
background return. This mask experiment neither changes the choreography nor
establishes its parity. Actual source backlighting must not be indiscriminately
darkened to mimic a different source's hair colour.

## Verification

Android debug unit tests, assembleDebug and lintDebug passed after adding the
mask export. Five local support tests pass (empty/full support, fractional alpha,
no invented foreground, invalid margin/planes); four MODNet size-policy tests
also pass. APK listing contains no ONNX/RVM models or MP4 fixtures. No production
model provider or automatic director was changed.

The supported candidate remains unaccepted because false background survives
motion and no three-source rendered visual acceptance has passed.

## Actual GLES/AAC render

Golden 1 completed on emulator-5554 using 91 constrained opacity masks and the
same graph/track as its ordinary-path baseline. As with the existing external
probe, auxiliary attachments are resampled at extra source PTS; this is not a
bit-exact single-variable guarantee for all auxiliary signals.

- [Ordinary app baseline from this run](../../artifacts/reference-analysis/semantic-support-g1-baseline.mp4)
- [Supported-alpha candidate MP4](../../artifacts/reference-analysis/semantic-support-g1-external-matte.mp4)
- [Decoded opening checkpoints](../../artifacts/reference-analysis/semantic-support-g1-external-matte-opening.jpg)
- [Inspector](../../artifacts/reference-analysis/semantic-support-g1-external-matte-inspector.json)
- [Terminal result](../../artifacts/reference-analysis/semantic-support-g1-external-matte.result.txt)

542 output frames; final video PTS 18,033,333us, final audio PTS 18,018,684us
(14,649us endpoint difference, not an encoder-delay-corrected perceptual sync
measurement). At the sampled .5s checkpoint the detached wall is absent; broad
soft/pale hair edges persist at later checkpoints. This is not reference-quality
cutout. No full three-source rendered pass is claimed; Golden 0/2 checks in this
turn are pre-GPU sequence diagnostics. Source-time GIF and contact-sheet review
do not prove absence of flicker during normal playback.
