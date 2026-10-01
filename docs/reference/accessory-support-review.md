# Accessory support filtering — three-source review

The six-channel probe at Golden 1 source 19,794,831 us identified the curtain above
the head in channel 5 (accessories). The old union unconditionally added it to the
person. Evidence: `artifacts/reference-analysis/channel-probe-g1/channel-5.png`.

The candidate preserves hair, body skin, face skin and clothing confidences. Accessory
components above 0.35 confidence require at least 25% of their boundary to touch the
confident person core; image edges count as unsupported boundaries. One-pixel support
retains their soft edges. This is a heuristic, not a new learned model. Eyewear inside
a face is covered by a synthetic regression; real hats/glasses remain unvalidated.

| Source | Before leak / IoU | After leak / IoU | Observation |
|---|---|---|---|
| Golden 0 | 0.000189 / 0.98830 | 0.000164 / 0.98885 | Pale hair edge remains; no major aggregate improvement. |
| Golden 1 | 0.032063 / 0.91306 | 0.009515 / 0.89377 | Leak now below 0.02; temporal stability slightly worse but above 0.88. Pale edge remains. |
| Golden 2 | 0.000627 / 0.97837 | 0.000387 / 0.97792 | Opening stable by metric; coarse hair boundary remains visible. |

Golden 1's 1.8 s checkpoint has identical source PTS, scale and entrance envelope
before and after. The improvement is not explained by selecting a different moment.

Files: `reveal-complete-gN.mp4` (before) and `accessory-support-gN.mp4` (candidate),
with `.result.txt` and `-opening.jpg` companions, under `artifacts/reference-analysis`.
Golden 0's comparison also includes the previously committed pose-depth dimension fix.
Full-video acceptance continues to fail on finale background isolation, outside the
three-second opening. This is not evidence of completed visual parity.

All three runs finished with 542 frames, 72 distinct opening source PTS, no black-block
flag and A/V drift 14,649 us. Unit tests, lint and APK build passed. No Golden MP4 is
packaged. Retain the improvement to accessory handling, with real accessory footage
and fine-hair quality still outstanding. No model or dependency was added.
