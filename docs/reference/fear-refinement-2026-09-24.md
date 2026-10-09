# FEAR refinement — 2026-09-24

## Result

The FEAR director now uses semantic subject scale, signed head pose, gesture, motion, face
confidence and image quality when choosing source moments. The title favours a clean close face;
the cascade starts clean and then alternates moments by scale, pose and motion contrast.

Strong defocus is limited to the three phrase entries measured in the recovered reference:

- 5.100 s — title to cascade;
- 10.966667 s — exit from the first shutter phrase;
- 15.866667 s — entrance to the second shutter phrase.

Ordinary cascade cuts no longer receive the 140 ms strong-defocus envelope. The light lens effect,
chromatic split and selected zoom trails remain. This v4 snapshot predates the measured
short-zoom and title-texture pass in `fear-effects-plan-2026-09-24.md`.

## Emulator verification

The final control render used `veypad-test-2.mp4` and the preserved author audio on the Android 16
/ API 36 x86_64 emulator.

| Check | Result |
| --- | --- |
| Frames | 549 |
| Measured/matched shutter frames | 30 / 30 |
| Five-frame finale | pass |
| Missing or wrong-luma pulses | none |
| Unexpected black frames | none |
| Beat hit rate | 96.55% |
| Author accent hit rate | 100% |
| Reference timeline recall | 100% |
| Face loss | 5.79% |
| A/V drift | 30.658 ms |
| Duration error | 33.334 ms |

Decoded contact sheets were compared at 5.0–6.2 s, 6.4–8.9 s, 10.6–11.6 s and
15.6–17.1 s. The repeated heavy blur visible after intermediate cuts in the previous render is
gone. The retained phrase-entry blur, alternating-black shutter blocks, `WW/B/WW` finale and black
tail match the recovered reference structure.

## Playback

The final encoded MP4 completed Android platform playback at both review speeds:

| Speed | Media duration | Activity wall time | Result |
| --- | ---: | ---: | --- |
| 1× | 18.321 s | 19.336 s | completed |
| 0.5× | 18.321 s | 37.371 s | completed |

## Local evidence

Ignored evidence is stored under `artifacts/fear/`:

- `selection-v3-2026-09-24/` — three semantic-selection renders and inspection reports;
- `effect-comparison-2026-09-24/` — recovered-reference comparison sheets;
- `effects-v4-2026-09-24/` — final MP4, result report and decoded contact sheets.
