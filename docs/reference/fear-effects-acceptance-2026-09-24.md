# FEAR effects pass — emulator acceptance, 2026-09-24

The `effects-v13` results below are the earlier effects baseline. The opener-tempo correction
in `fear-opener-tempo-fix-2026-09-24.md` supersedes these exports with `effects-v14`.

## Implemented

- Authored mild push/pull per cascade shot, with short late acceleration on selected cuts;
  the extra scale is reduced for already-close faces or strong source-native radial motion.
- Face-centred scale pivot where semantic face evidence is available.
- A short radial smear under the three established phrase-entry defocus cues at 5.10,
  10.966667 and 15.866667 s; chromatic split and light lens accents only at selected cuts.
- Cooler, fine vertical line/grain texture in the title window. Adaptive FEAR exposure,
  contrast, highlight retention and a soft shadow toe for bright input footage.
- Removed the generic every-fourth-scene delayed-image echo. Enlarged reference frames did
  not establish it as a separate authored effect.

No scene, beat, shutter or finale timestamps changed. The visible shutter frames were not
given independent artificial light pulses: measured brightness changes could not be separated
from source lighting. See `fear-effects-plan-2026-09-24.md` for the reference evidence.

## Device checks

Android 16 / API 36 x86_64 emulator (`Fear_API_36`), three independent source videos, the
preserved author audio, final `effects-v13` build. All exported at 720 × 720.

| Check | Source 0 | Source 1 | Source 2 |
| --- | ---: | ---: | ---: |
| Encoded frames | 549 | 549 | 549 |
| Black shutter frames matched | 30/30 | 30/30 | 30/30 |
| `WW/B/WW` finale | pass | pass | pass |
| Unexpected black frames | none | none | none |
| Acceptance | pass | pass | pass |
| Face loss | 8.89% | 8.58% | 5.56% |
| Maximum artifact score | 0.0521 | 0.0208 | 0.0313 |
| A/V drift | 30.658 ms | 30.658 ms | 30.658 ms |

All three also had a 96.55% beat hit rate, 100% author accent hit rate and 100% reference
timeline recall. Duration error was 33.334 ms, approximately one 30 fps frame.

The first contrast candidate failed the generic dark-block check on two bright sources.
Inspecting the decoded frames and original input showed that the grade had indeed crushed
hair/clothing texture. A FEAR-specific soft shadow toe fixed the visible problem; all three
final renders pass. The decoded block check also compares FEAR with the corresponding source
frame, so naturally dark source regions are not treated as newly introduced black blocks.

Full-size decoded stills are in `artifacts/fear/effect-comparison-2026-09-24/`:
`v13-source2-4700.png`, `v13-source2-9000.png`, `v13-source0-5700.png`, and
`v13-source1-13933.png`. Final MP4s and machine reports are in
`artifacts/fear/effects-v13-2026-09-24/` (ignored local evidence).

## Playback

The final source-2 MP4 completed Android platform playback at both speeds:

| Speed | Media duration | Review wall time | Result |
| --- | ---: | ---: | --- |
| 1× | 18.321 s | 20.149 s | completed |
| 0.5× | 18.321 s | 37.940 s | completed |
