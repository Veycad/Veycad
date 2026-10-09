# Heartbeat subject-cue alignment — 2026-09-14

Local-only API 36 regression on `emulator-5554`.

## Inputs and evidence

- Author-owned reference: `/Users/l-v-polyakov/Downloads/ssstik.io_1788734312850.mp4`.
- Sole tuning source: `/Users/l-v-polyakov/Downloads/IMG_1647.MOV`.
- Before: VME-046 scene-tone candidate.
- After: ignored artifact
  `artifacts/device-regression/emulator/heartbeat-composition-slot-20260914/candidate.mp4`.
- Reference and source were sampled every 250 ms by the existing on-device semantic analyzer. A
  debug-only report addition exports the already-computed face region and composition observations;
  it does not add inference or affect production montage behavior.

No reference, source, rendered MP4, APK or decoded media is tracked by Git.

## Defect and correction

The selected cat interval was a useful live subject change, but Heartbeat placed it at 8.700–9.633 s
and repeated it at 16.717–17.650 s. Both reference windows contain a readable face. Conversely, the
fast 3.733–4.233 s action and its 11.750–12.250 s reprise hide the face, while the previous candidate
used another close portrait there.

The existing content-derived interlude now occupies measured role 4 instead of role 12. This is a
recipe correction, not a new effect or model. Role 12 returns to its independently selected live
human window, and the separately directed finale still returns to its readable hero.

## Decoded A/B/C evidence

At the four affected windows, semantic face-presence agreement with the author reference changed:

| Output cue | Reference | Before | After |
| --- | --- | --- | --- |
| 3.733–4.233 s | no face / moving body | close face | animal/action insert |
| 8.700–9.633 s | face | animal insert | face |
| 11.750–12.250 s | no face / moving body | close face | animal/action insert |
| 16.717–17.650 s | face | animal insert | face |

Agreement is therefore `0/4 → 4/4`. Inspector clocks show that only clips 4, 12, 15 and 23 changed,
for 171 of 1,271 frames. The first phrase still contains 15 distinct source windows; no arbitrary
duplicate was introduced. The visual comparison is stored under the ignored `compare/` directory
beside the candidate.

## Runtime and container evidence

- `status=ok`, decoded acceptance true, no acceptance issue;
- 1,271 frames, graph duration 21,167 ms, duration error 334 us;
- Heartbeat author music present; first video/audio PTS both zero;
- A/V drift 13,288 us;
- beat-hit rate `0.9230769`, author-accent hit rate `1.0`, all 26 pulses matched;
- `foreground_reentries=0`, `whips=0`;
- maximum decoded artifact/black-block score `0.010416667`;
- candidate SHA-256:
  `785238ceff3f7baff414edb1f80ad9fdc851f934afac39694410cd4e60eedf82`.

This closes one reversed subject-role pair. It does not establish full shot-scale, motion or
temporal-effect parity for Heartbeat.
