# Foreground re-entry damages facial texture

Full-resolution local decode of `baseline-clock-g0-0907.mp4` at output 2.0 s
shows black/smeared pixels around the right eyebrow/eye, absent from the source
frame actually decoded at 24,313,766 us. Evidence:
`artifacts/reference-analysis/g0-eye-source-24313766.png` and
`g0-eye-before-2000000.png`. Neck/collar damage and irregular hair edges also
remain, but the current experiment targets only the face interior.

AVFoundation was used locally without interrupting Android regression. Its
returned source PTS is 24,313,766.6667 us (Android records integer microseconds).
The output PNG is decoded at exactly 2.0 s. A query just below the fractional
source boundary returned the previous frame, so that first extraction was not
used as the comparison evidence.

The foreground-reentry shader erodes alpha and borrows neighbouring colour
without excluding face interiors. Existing CPU face support applies only to
refined masks and cannot protect a selected sparse baseline from these GPU
operations. Experiment: a confidence-gated inset face ellipse in shifted mask
coordinates restores locally supported alpha and original graded RGB, leaving
outer silhouette and physical-opacity masks unchanged. No new model added.

Tests/lint/assembly pass. `face-interior-g0-0907` started after the three-source
baseline-clock regression completed. Require decoded full-size 2.0 s comparison
before retaining this GLSL experiment. Do not claim face or collar fixed yet.

## Decoded verification

`face-interior-g0-0907.mp4` completed: 542 frames, 18.034 s, AAC drift 14,649 us.
The selected MP4 was decoded locally with AVFoundation at actual output PTS
2.0 s and compared at full resolution with `g0-eye-before-2000000.png`.
The black/smeared patch at the right eyebrow/eye is absent in this checkpoint;
the eye is readable. Retain the shader change for further regression, not as a
completed matte fix. Hair halo, crown hole and side-of-hair hole remain visible.
This is a still-frame finding; temporal inspection and Golden 1/2 regression
are required before generalising. Overall acceptance remains false for the
separate final-stage background-isolation gate.

Golden 1 completed with the same face-protection APK: selected refined candidate,
18.034 s, AAC drift 14,649 us, face-loss rate 0.037037, reported edge leak zero.
Its decoded opening sheet still shows a hole beside the neck at 2.0 s and
contaminated hair edges. No new broad visual improvement is claimed. The selected
MP4, inspector and opening sheet were pulled to `artifacts/reference-analysis/`.
`face-interior-g2-0907` was started with the SAME installed APK (without installing
the newer fringe-coordinate experiment) to finish the isolated three-source test.

## Rejected generic outline experiment

On the verified Heartbeat role-4 re-entry, the renderer's previously unused
`outlineStrength` was wired to a one-texel matte dilation ring and rendered on
the emulator as `heartbeat-clean-reentry-rim-0907.mp4`. A/B/C inspection at
6.717 s found no material increase in subject separation, while measured maximum
edge leakage increased from `0.000059873786` to `0.003935805`. The shader wiring
was removed. The MP4, inspector and transition sheet remain local under
`artifacts/device-regression/emulator/heartbeat-clean-reentry-rim-0907/` as
negative evidence; do not reintroduce a generic halo as a substitute for a
reference-shaped light/double-exposure treatment.

## Heartbeat role and live-echo result (2026-09-08)

The isolation slot now uses verified role 2 from the same analysed source pool: its source
mask quality is 0.895 with zero occlusion, while animal role 7 remains available in ordinary
cuts. A foreground-only 0.86 virtual-camera scale exposes more shoulders without changing the
source PTS and returns to 1.0 before the original plate is revealed. A moving second silhouette
is sampled from the same advancing decoder texture and physical matte, so neither copy is
frozen. The accepted local comparison is `heartbeat-reentry-ghost-strong-0908`; its 6.717 s
frame shows a readable live duplicate behind the isolated subject and no horizontal banding.

The current generic edge-leak metric reports approximately 0.165 because it compares the
deliberately authored second silhouette against a single expected mask. This is a QA-model
mismatch, not evidence that the main matte has the same leakage. The metric must be taught the
authored two-silhouette geometry before it can gate this shot; visual review remains mandatory.
