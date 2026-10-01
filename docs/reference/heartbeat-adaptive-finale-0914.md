# Heartbeat adaptive finale grade — 2026-09-14

Local-only API 36 regression on `emulator-5554`.

## Inputs and candidates

- Author-owned reference: `/Users/l-v-polyakov/Downloads/ssstik.io_1788734312850.mp4`.
- Sole tuning source: `/Users/l-v-polyakov/Downloads/IMG_1647.MOV`.
- Before: the VME-044 semantic-role candidate.
- After: ignored artifact
  `artifacts/device-regression/emulator/heartbeat-role-identity-20260914-r2/adaptive-finale/candidate.mp4`.

Reference, source, APK, decoded frames and rendered media remain local and untracked.

## Visible defect

VME-044 corrected the finale composition by selecting the close emotional source window. The
existing fixed exposure was calibrated for the old darker wide window, so the new close-up clipped
the face, hair, shirt and background. This was visible in decoded frames and not rejected by the
aggregate acceptance score.

## Correction

Heartbeat now reads the median luma of confident visual observations inside the selected finale
source window. With at least two samples, it inverts the existing grade transfer function to author
the required entry, plateau, echo-entry and echo-exit targets. Missing or insufficient evidence
keeps the previous fixed grade unchanged; there is no source timestamp, fixture name or person
identity in the rule.

## Decoded A/B/C evidence

| Output time | Reference luma | Before | Adaptive |
| --- | ---: | ---: | ---: |
| 19.000 s | 0.719722 | 0.878995 | 0.692219 |
| 19.400 s | 0.733196 | 0.853906 | 0.642213 |
| 19.700 s | 0.414036 | 0.821580 | 0.373683 |
| 19.800 s | 0.000000 | 0.007843 | 0.000000 |

Across all 67 aligned frames from 18.667 s through the black tail at 19.800 s, luma-trajectory MAE
against the author reference changed from `0.178469` to `0.049241`, a 72% reduction. Exact decoded
frames show that the adaptive version restores facial and room detail at 19.000–19.400 s and reaches
the intended dark punctuation at 19.700 s. Subject identity and pose intentionally remain source
dependent; this evidence proves the grade correction, not complete Heartbeat parity.

## Device and container evidence

- `status=ok`, decoded acceptance true, candidate score `0.7675381`;
- 1,271 video frames, graph duration 21,167 ms, duration error 334 us;
- first video/audio PTS both zero, A/V drift 13,288 us;
- Heartbeat author music present as a separate AAC source;
- beat-hit rate `0.9230769`, author-accent hit rate `1.0`, 26/26 pulses matched;
- `foreground_reentries=0`, `whips=0`, no black block or transition artifact;
- candidate SHA-256:
  `64ef20bbc39edb0d89f84a29eb7ebe6edf7b04b6dc93e1a9d5a8afdedd621520`.

The full unit suite, Android lint and arm64 debug APK assembly pass. This closes the adaptive-finale
exposure defect while leaving broader shot/effect parity work active.
