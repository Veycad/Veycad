# FEAR opener tempo correction — 2026-09-24

This `effects-v14` correction is now the tempo baseline. The subsequent `effects-v18`
opener-style pass adds the reference-inspired zoom and texture without reintroducing slowdown;
see `fear-opener-style-2026-09-24.md`.

## Cause and correction

The authored opener occupies 0–3.6 s, but the director had copied a source candidate only
0.98–1.5 s long into that output interval. The frame scheduler consequently played it at
roughly 0.27–0.42× source speed. In the source-0 control export, the last second looked
almost frozen.

The director now expands the selected motion/occlusion candidate to a continuous 3.6 s
source window, preferably without entering the title source window. It removes this window
from the later cascade candidates and partitions the remaining source into unique short
moments. The opener is sampled at approximately native speed, while the 3.6 s title cut and
all following output timestamps remain fixed.

Decoded 0–3.5 s sheets from the old and corrected source-0 exports are stored at
`artifacts/fear/effect-comparison-2026-09-24/v13-opener-source0.jpg` and
`v14-opener-source0.jpg`. The corrected sheet shows continued turning and motion through
the end of the opener instead of an almost static close-up.

## Emulator verification

Final `effects-v14` build on the Android 16 / API 36 x86_64 emulator. Three independent
source videos, preserved author audio. In all three reports, the opener source span is
3,600 ms for a 3,600 ms output span. All three have 549 encoded frames, 30/30 matched
black shutter frames, the correct `WW/B/WW` finale, no unexpected black frames, full
acceptance, and 30.658 ms A/V drift.

| Source | Previous opener source span | Corrected span | Face loss | Maximum artifact |
| --- | ---: | ---: | ---: | ---: |
| 0 | 981 ms | 3,600 ms | 7.11% | 0.0625 |
| 1 | 1,300 ms | 3,600 ms | 9.73% | 0.0208 |
| 2 | 1,500 ms | 3,600 ms | 7.57% | 0.0729 |

The final MP4s and device reports are stored in the ignored local directory
`artifacts/fear/effects-v14-2026-09-24/`.

Android platform playback of the final source-2 MP4 completed at both 1× (20.053 s wall
time) and 0.5× (38.295 s wall time). Media duration was 18.321 s in both reviews.
