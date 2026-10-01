# Heartbeat echo face envelope — 2026-09-14

Local-only API 36 regression on `emulator-5554`.

## Evidence boundary

- Author-owned reference: `/Users/l-v-polyakov/Downloads/ssstik.io_1788734312850.mp4`.
- Sole tuning source: `/Users/l-v-polyakov/Downloads/IMG_1647.MOV`.
- Before: VME-049 motion-cadence candidate.
- Accepted after: ignored artifact
  `artifacts/device-regression/emulator/heartbeat-echo-face-envelope-20260914/candidate.mp4`.

Reference and candidate were reviewed at the same 24 exact timestamps from 11.733 through
19.717 seconds and together at 1x in the loopback-only three-player viewer. Source media,
decoded PNGs, rendered MP4s and APKs remain ignored local artifacts.

## Visible defect and correction

At the start of the author reference's strongest reprise shots, multiple displaced head and body
positions remain visible and then resolve to one figure. Veycad already rendered the radial
three-copy trail, but a constant 70% post-composite face restore pinned one sharp face over its
strongest frames. The result read as a translucent haze instead of displaced motion.

The Heartbeat-only face restore now follows the existing measured layer envelope: 30% at full echo
and progressively back to 70% as the trail resolves. The echo geometry, source timestamps, music,
scene assignment and Sigma shader route are unchanged. This is one shader expression and one
structural regression assertion; no model, dependency or new effect was added.

## A/B evidence

- All 1,271 output frames have identical decoded primary and secondary source PTS before/after.
- Exact 14.250 s frames show multiple eye, mouth and hair positions instead of a single sharp face
  pasted over the trail; the effect recovers its measured resolve as opacity falls.
- The complete 11.75–20.10 s phrase played at reported 1x with zero dropped frames and no browser
  decode error.
- Decoded face-loss changed `0.023255814 → 0.027131783`, remaining below the 0.05 gate.

This is a bounded improvement to echo articulation. The single selfie source still cannot recreate
the reference's separate locations, wardrobe, camera coverage or exact body silhouettes.

## Device and container evidence

- `status=ok`, decoded acceptance true, no acceptance issue;
- 1,271 frames, duration error 334 us;
- Heartbeat author music present; video/audio start PTS both zero;
- A/V drift 13,288 us;
- beat-hit `0.9230769`, author-accent hit `1.0`, all 26 pulses matched;
- maximum artifact/black-block score `0.010416667`;
- `foreground_reentries=0`, `whips=0`;
- candidate SHA-256:
  `9f0fc333f4aa5bbf033df0449414b45b194f9ffd15f36afc189b6e26c8be3eba`.
