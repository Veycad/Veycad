# Heartbeat semantic close/wide role swap — 2026-09-14

Local-only regression on API 36 `emulator-5554`.

## Inputs

- Author-owned reference: `/Users/l-v-polyakov/Downloads/ssstik.io_1788734312850.mp4`.
- Sole tuning source: `/Users/l-v-polyakov/Downloads/IMG_1647.MOV`.
- Baseline: `heartbeat-onset-diversity-g2.mp4` retained on the emulator.
- Candidate: ignored artifact
  `artifacts/device-regression/emulator/heartbeat-role-identity-20260914-r2/semantic-role-swap/candidate.mp4`.

No source, reference, rendered MP4 or APK is tracked by Git.

## Defect and correction

The generic DYNAMIC pool had already found two useful unique source windows, but Heartbeat assigned
them to the opposite measured compositions:

- output 3.733–4.233 s (reference close/emotional cue) used the wider source window;
- output 8.300–8.700 s (reference wide action cue) used the close hand-over-face window.

Heartbeat now measures the median confident face width inside both selected windows. It swaps the
pair only when each window has multiple samples and the scale inversion exceeds 0.08. There are no
source timestamps, identities or fixture names in the decision. Missing or ambiguous evidence keeps
the original pool order. The mapped close role is also used for the authored reprise and finale.

The soft bright-portrait fallback had an independent top-down/bottom-up Y-sign mismatch; its
translation now uses the same coordinate convention as the existing portrait transform.

## Decoded A/B/C review

- 3.900 s: baseline shows a wide extended-arm kitchen shot; candidate shows the close emotional
  hand-over-face plate, materially closer to the reference's close dressing gesture.
- 8.500 s: baseline shows the close hand-over-face plate; candidate shows the wider extended-arm
  action plate, matching the reference's brief wide action role.
- 19.000–19.700 s: candidate returns to the close emotional hero under the existing finale exposure
  and echo, instead of using the wider plate.

Baseline and candidate contain the same 1,271 output timestamps. Exactly 258 planned source frames
changed: clips 4 (30), 11 (24), 15 (30), 22 (24), finale clip 25 (67), and the visually black tail
copy clip 26 (83). All other planned source timestamps are identical.

## Runtime evidence

- `status=ok`, decoded acceptance true, no reported issues;
- 1,271 video frames, 21,167 ms graph duration, duration error 334 us;
- Heartbeat author music present, first video/audio PTS both zero;
- A/V drift 13,288 us;
- 26/26 measured pulses, author accent hit rate 1.0;
- beat-hit rate 0.9230769;
- `foreground_reentries=0`, `whips=0`;
- decoded face loss improved 0.024193548 → 0.016129032;
- candidate score improved 0.7570864 → 0.7675381;
- no black block or transition artifact reported;
- candidate SHA-256:
  `aa05ec00a8946da785fd92d2c5c3b5b0b4978211cbe661659715278d82e425c5`.

Full `testDebugUnitTest`, `lintDebug`, `assembleDebug` and `git diff --check` pass. The arm64 APK
contains no Golden/reference/user MOV or MP4. This is an accepted role-assignment improvement, not
a claim of complete visual parity with the reference.
