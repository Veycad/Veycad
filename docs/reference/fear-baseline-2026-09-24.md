# FEAR emulator baseline — 2026-09-24

## Scope

The recovered HD FEAR reference and the current FEAR renderer were checked on the same Android
16 / API 36 x86_64 emulator. The three private control sources were rendered through
`GoldenRenderActivity`; the third result was also played through Android's platform `VideoView` at
1× and 0.5×.

## Reference confirmation

`TikVideo.App_7487309285538450710-hd.mp4` is the same authored montage as the original FEAR
reference, delivered as a 1080 × 1080 transcode. Its decoded structure retains the 3.600 s title
cut, 5.100 s cascade cut, two alternating-black shutter phrases, five-frame `WW/B/WW` finale and
terminal black tail. Full recovery evidence is in `fear-reference-recovery-2026-09-24.md`.

## Fresh render results

| Control source | Frames | Shutter | Finale | Extra black | Beat hit | Face loss | A/V drift | Result |
| --- | ---: | ---: | --- | --- | ---: | ---: | ---: | --- |
| `veypad-test-0.mp4` | 549 | 30/30 | pass | none | 96.55% | 10.30% | 30.658 ms | pass |
| `veypad-test-1.mp4` | 549 | 30/30 | pass | none | 96.55% | 2.63% | 30.658 ms | pass |
| `veypad-test-2.mp4` | 549 | 30/30 | pass | none | 96.55% | 8.65% | 30.658 ms | pass |

Every render has a 33.334 ms duration error, 100% reference-timeline recall and exact author
accent alignment. All scene changes after the opener remain hard cuts. No FEAR render requests a
foreground mask or foreground-reentry transition.

## Playback

The third render completed Android platform playback at both requested speeds:

| Speed | Media duration | Measured activity wall time | Completion |
| --- | ---: | ---: | --- |
| 1× | 18.321 s | 20.495 s | clean |
| 0.5× | 18.321 s | 38.000 s | clean |

The wall time includes activity startup and decoder preparation, so it is longer than the media
clock. Completion, rather than wall-clock equality, is the playback gate.

## Visual comparison and next work

The authored chronology is already stable. The remaining gap is qualitative source direction:

- preserve an occlusion- or motion-led continuous opener when the source supports one;
- favour a clean, comparatively still close-up for the title;
- maximise contrast between adjacent cascade moments in subject scale, pose and motion;
- keep defocus, chromatic split and zoom trails as short cut accents instead of softening the
  whole shot.

The generic `reference_grammar_fit` score is 0.5847 for every FEAR render because that metric
rewards a mixed transition vocabulary, while FEAR intentionally uses only hard cuts. It should not
be used to drive FEAR visual changes; the FEAR-specific frame, pulse, finale and timeline audits
are authoritative.

## Local artifacts

Private ignored artifacts are under `artifacts/fear/`:

- `reference/` contains the recovered HD reference, emulator analysis and 200 ms contact sheet;
- `baseline-2026-09-24/` contains all three MP4 outputs, result files, inspector reports and layer
  sheets, plus a 200 ms contact sheet for the first output.
