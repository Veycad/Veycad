# FEAR opener style pass — 2026-09-24

## Feedback and reference comparison

After the native-speed correction, the opener still felt long and unprocessed. Decoded
0–3.6 s sheets showed why: the previous source-2 selection became almost stationary after
2 s, while the reference has continuous foreground movement and a high-contrast,
grainy orange/cyan edge treatment. The measured title cut remains at 3.6 s.

The director now scores complete 3.6 s source windows for sustained camera/subject motion,
change in subject scale and occlusion, instead of extending the single highest-scoring
short moment. Clean close faces receive a penalty in opener selection so they remain
available to the title and early cascade. On source 2 it selected 9.5–13.1 s, which keeps
the camera and subject moving throughout the opener.

A small authored zoom reaches at most 1.075× at two short accents. Each accent has a
brief chromatic split and light lens distortion. The opener also receives a restrained
dark/cool grade, visible grain and a thin orange/cyan edge; face protection keeps the
subject readable. Source playback remains at approximately 1× and no cut time moves.

The reference and successive decoded opener sheets are in
`artifacts/fear/effect-comparison-2026-09-24/`: `ref-opener-250ms.jpg`,
`v14-opener-250ms.jpg`, `v15-opener-source2-250ms.jpg`, and full-resolution final
`v18-opener-1133.png`, `v18-opener-1750.png`, `v18-opener-2800.png`.

## Emulator acceptance

Final `effects-v18` build, Android 16 / API 36 x86_64 emulator, three source videos and
the preserved author audio. All three exports: 549 frames, 30/30 black shutter frames,
correct `WW/B/WW` finale, no unexpected black frames, full acceptance, and 30.658 ms A/V
drift. The opener source span is 3,600 ms for the 3,600 ms output span on each video.

| Source | Opener source window | Face loss | Maximum artifact |
| --- | --- | ---: | ---: |
| 0 | 15.0–18.6 s | 5.69% | 0.0729 |
| 1 | 8.0–11.6 s | 4.96% | 0.1667 |
| 2 | 9.5–13.1 s | 9.48% | 0.0729 |

The three final MP4s and machine reports are stored locally in
`artifacts/fear/effects-v18-2026-09-24/`.

Android platform playback of the source-2 MP4 completed at 1× (19.785 s wall time) and
0.5× (37.795 s wall time); media duration was 18.321 s in both checks.
