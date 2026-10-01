# FEAR three-source acceptance — 2026-09-15

## Result

All three supplied local control sources passed the final Android MediaCodec render and decoded
frame acceptance. Outputs are 720 × 720, 30 fps, 549 frames and use the authorised FEAR track.

| Control source | Coverage characteristic | Shutter | Finale | Extra black | Beat hit | Face loss | A/V drift | Result |
| --- | --- | ---: | --- | --- | ---: | ---: | ---: | --- |
| `veypad-test-0.mp4` | Multi-shot / broad coverage | 30/30 | WW/B/WW | none | 96.55% | 13.97% | 30.658 ms | pass |
| `veypad-test-1.mp4` | Low-light / backlit coverage | 30/30 | WW/B/WW | none | 96.55% | 12.44% | 30.658 ms | pass |
| `veypad-test-2.mp4` | Ordinary single-person coverage | 30/30 | WW/B/WW | none | 96.55% | 13.81% | 30.658 ms | pass |

The duration error is 33.334 ms for every output, equal to the one-frame acceptance boundary.
The decoded reference-timeline recall is 100% for all three. No pulse has missing or wrong luma.

## Direction and compositing checks

- Exact mode receives 16 selected source windows and emits 29 visible clips plus the black tail.
- Visible source ranges are non-overlapping; opener and title use reserved windows.
- Every scene transition after the opener is a hard cut.
- The cascade varies crop scale, face pose/gesture proxy and motion; defocus, chromatic split,
  zoom trails, texture and scanlines remain accents rather than timing changes.
- The graph requests no foreground mask refinement and decoded mask-layer samples are zero.
- Contact sheets show an upright, centred `FEAR` title and retained faces.
- Layer sheets show the impulse pairs, both alternating shutter phrases and the five-frame finale.

## Real-time review

The ordinary-source output completed playback through Android's platform video decoder at both
requested speeds:

| Speed | Media duration | Measured wall time | Completion |
| --- | ---: | ---: | --- |
| 1× | 18.321 s | 18.839 s | clean |
| 0.5× | 18.321 s | 36.899 s | clean |

Android renders `VideoView` through a hardware surface that is not represented in emulator screen
captures. Visual review therefore uses decoded contact/layer sheets; the playback marker supplies
the independent end-to-end decoder and timing evidence.

## Local artifacts

- `artifacts/fear/fear-final-0.mp4` and its result, inspector, contact and layer sheets.
- `artifacts/fear/fear-final-1.mp4` and its result, inspector, contact and layer sheets.
- `artifacts/fear/fear-final-2.mp4` and its result, inspector, contact and layer sheets.

The supplied fixture videos and the author reference are not packaged into the APK. Only the
authorised audio asset is included.
