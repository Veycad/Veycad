# Heartbeat shot-scale cadence — 2026-09-14

Local-only API 36 regression on `emulator-5554`.

## Inputs and candidates

- Author-owned reference: `/Users/l-v-polyakov/Downloads/ssstik.io_1788734312850.mp4`.
- Sole tuning source: `/Users/l-v-polyakov/Downloads/IMG_1647.MOV`.
- Before: VME-047 subject-cue candidate.
- Accepted after: ignored artifact
  `artifacts/device-regression/emulator/heartbeat-scale-rank-light-pinned-20260914/candidate.mp4`.

No source, reference, rendered MP4, APK or decoded media is tracked by Git.

## Defect and correction

After subject-presence alignment, the output still read as a row of similarly large portraits. The
author reference has a deliberate relative scale cadence, including medium two-person plates, close
emotion, body action and a full-body stair shot. Absolute detector widths cannot be copied from its
square crop to the portrait user source, but their ordering is a stable style signal.

Heartbeat now stores nullable median face widths for the 15 authored roles. Reprised roles use the
median of both measured appearances. The dark opening and fast subjectless action remain null rather
than converting detector failure into invented evidence. With complete source evidence and at least
0.12 face-width span, the director matches source-window and authored-cue ranks while preserving a
one-to-one permutation. It reserves:

- the opening plate;
- the content-derived subjectless action/animal interlude;
- the independently selected finale hero;
- the verified role-9 portrait used by both white light accents.

No virtual zoom, new ML model, source timestamp or duplicate window is introduced.

## Rejected control

The first experiment allowed all ordinary face roles to move. It produced a complete 1,271-frame
MP4 but moved an unsuitable source window into the intense 7.283 and 15.300 s light accents. Decoded
face loss rose to `0.06563707`; QA rejected the candidate. That MP4 was removed from the emulator and
is not the accepted result. Pinning the previously verified light role reduced face loss to
`0.023166023` while retaining the scale-cadence gain.

## Decoded evidence

Across the 12 remappable face roles:

- normalized rank MAE: `0.303030 → 0.189394` (37.5% lower);
- Spearman rank agreement: `0.318740 → 0.707532`;
- the first phrase remains 15/15 distinct source windows;
- the accepted map changes ordinary roles in both the first phrase and authored reprise, while
  leaving the opening, animal/action cue, light accents and finale assignment fixed.

The contact sheet shows more frequent alternation between face size, pose and gesture. The source is
itself primarily a portrait selfie, so this does not create full-body material that was never shot.

## Runtime and container evidence

- `status=ok`, decoded acceptance true, no acceptance issue;
- 1,271 frames, graph duration 21,167 ms, duration error 334 us;
- Heartbeat author music present; first video/audio PTS both zero;
- A/V drift 13,288 us;
- beat-hit rate `0.9230769`, author-accent hit rate `1.0`, all 26 pulses matched;
- face-loss rate `0.023166023`;
- `foreground_reentries=0`, `whips=0`;
- maximum artifact/black-block score `0.010416667`;
- candidate SHA-256:
  `809ae5308d89b731ea5b597389f2210d88e25951d0744e01745a86334e562ff7`.

This improves the relative composition sequence. It does not establish complete semantic action,
motion or temporal-effect parity with the author reference.
