# Heartbeat measured scene tone — 2026-09-14

Local-only API 36 regression on `emulator-5554`.

## Inputs and candidates

- Author-owned reference: `/Users/l-v-polyakov/Downloads/ssstik.io_1788734312850.mp4`.
- Sole tuning source: `/Users/l-v-polyakov/Downloads/IMG_1647.MOV`.
- Before: VME-045 adaptive-finale candidate.
- After: ignored artifact
  `artifacts/device-regression/emulator/heartbeat-role-identity-20260914-r2/scene-tone/candidate.mp4`.

No reference, source, rendered MP4, APK or decoded media is tracked by Git.

## Defect and correction

The author reference changes tonal weight scene by scene. The bright daylight source stayed near a
uniform `0.54–0.61` luma through most of the current montage, so its measured cuts and pulses did not
produce the same dark/light cadence. This remained visible even after correcting the finale.

The existing Heartbeat recipe now owns a 26-value scene-luma profile measured from decoded reference
frames with 50 ms removed at both cut edges. For every ordinary scene, it derives a constant grade
from the current source window's median local visual-map luma and the existing GLES grade transfer.
The two measured white plates and finale are excluded because they already own dedicated animated
curves. With fewer than two source samples, the previous grade remains unchanged.

## Decoded evidence

- scene-median luma MAE across 26 scenes: `0.157710 → 0.025238` (84% lower);
- aligned frame-luma MAE from 0 through 19.800 s: `0.177755 → 0.065547` (63% lower);
- 21 of 26 scene medians became closer to the reference;
- representative corrections:
  - scene 1: reference `0.336`, before `0.560`, after `0.327`;
  - scene 2: reference `0.340`, before `0.614`, after `0.325`;
  - scene 5: reference `0.310`, before `0.571`, after `0.295`;
  - scene 12: reference `0.265`, before `0.561`, after `0.259`;
  - scene 23: reference `0.250`, before `0.541`, after `0.234`.

The decoded contact sheet retains readable faces and source detail. Face-loss changed
`0.016129032 → 0.02016129`, still below the 5% acceptance limit; the added loss is a single sample
at the deliberately dark 9.633 s boundary. Five scenes remain imperfect and must be treated as
future visible defects rather than hidden by this aggregate improvement.

## Runtime and container evidence

- `status=ok`, decoded acceptance true, no acceptance issue;
- 1,271 frames, graph duration 21,167 ms, duration error 334 us;
- Heartbeat author music present, first video/audio PTS both zero;
- A/V drift 13,288 us;
- beat-hit rate `0.9230769`, author-accent hit rate `1.0`, 26/26 pulses matched;
- `foreground_reentries=0`, `whips=0`;
- maximum decoded black-block/artifact score `0.010416667`, below rejection threshold;
- candidate SHA-256:
  `27ede852e600a4a820507c84c37d8de8cd7109133eb994bc2a8970428ab528ac`.

Full unit tests, Android lint and arm64 debug APK assembly pass. This is a measured tonal-direction
gain, not a claim that shot choice, temporal effects or full Heartbeat parity are complete.
