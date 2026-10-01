# Heartbeat motion cadence — 2026-09-14

Local-only API 36 regression on `emulator-5554`.

## Inputs and accepted output

- Author-owned reference: `/Users/l-v-polyakov/Downloads/ssstik.io_1788734312850.mp4`.
- Sole tuning source: `/Users/l-v-polyakov/Downloads/IMG_1647.MOV`.
- Before: VME-048 scale-only assignment.
- Accepted after: ignored artifact
  `artifacts/device-regression/emulator/heartbeat-scale-motion-20260914/candidate.mp4`.

No source, reference, rendered MP4, APK or decoded media is tracked by Git.

## Defect and correction

The scale-only assignment improved shot-size order but ignored how much real motion each selected
source window contained. Across the 12 remappable authored roles, the resulting source-motion order
was inversely related to the reference cadence.

The reference-analysis export now records the existing camera- and residual-subject-motion vectors.
Heartbeat stores the median measured motion magnitude for every authored role. When the current
source has complete and materially varied motion evidence, the director solves a deterministic
one-to-one minimum-cost assignment over the existing source windows. Cost is 60% relative face
scale and 40% real source motion. The opening, content-derived action insert, verified light accent
and independently chosen finale remain pinned. If evidence is incomplete, the accepted scale-only
mapping remains the fallback.

This changes only source-window assignment. It does not synthesize shake, optical-flow frames or
new source timestamps.

## Decoded evidence

Using the actual source PTS decoded for each of the 12 remappable roles:

- face-scale Spearman agreement: `0.692308 → 0.818182`;
- camera-plus-subject-motion Spearman agreement: `-0.195804 → 0.524476`;
- all source windows remain a one-to-one permutation;
- the contact sheet retains readable faces and materially alternates still and active expressions.

## Runtime and container evidence

- `status=ok`, decoded acceptance true, no acceptance issue;
- 1,271 frames, graph duration 21,167 ms, duration error 334 us;
- Heartbeat author music present; first video/audio PTS both zero;
- A/V drift 13,288 us;
- beat-hit rate `0.9230769`, author-accent hit rate `1.0`, all 26 pulses matched;
- face-loss rate `0.023255814`;
- `foreground_reentries=0`, `whips=0`;
- maximum artifact/black-block score `0.010416667`;
- candidate SHA-256:
  `53a4746c44ca2a0382c69f8439bc3aad945b79db6d5c3e721a1169cbbf9184c3`.

This closes the measured scale-and-motion assignment gap. It does not claim complete semantic
action or effect parity with the author reference.
