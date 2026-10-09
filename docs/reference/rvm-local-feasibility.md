# Local matting feasibility — 2026-09-05

## Question and scope

Residual pale hair edges remain in all three accessory-support app renders.
Test whether a foreground-colour + alpha predictor changes this named defect,
without adding a dependency/model to the application. This is not an app quality
release and is not proof of the three-source opening goal.

Official candidate: https://github.com/PeterL1n/RobustVideoMatting
Official interface: https://github.com/PeterL1n/RobustVideoMatting/blob/master/documentation/inference.md
The repository declares GPL-3.0. Production distribution/licensing is unresolved;
the candidate remains an ignored local experiment, not an APK asset.

## Reproducible evidence

- Model: official v1.0.0 MobileNetV3 FP32 ONNX release, SHA256
  `88d4531297118f595bf2fd60f6f566aec2e559393802d1f436c380f0cbbd2828`.
- ONNX Runtime 1.29.0, CPU provider, 2 threads, 270x480 RGB, ratio 1.0.
- Single-frame probe: Golden 1 source PTS 19,794,831 us, same extracted PNG
  as the preceding edge investigation. Four columns: original, alpha,
  original RGB multiplied by alpha, recovered RGB multiplied by the SAME alpha.
- `artifacts/reference-analysis/rvm-temporal-g1/comparison.png` and `report.json`.
- Sequence: 30 frames, source 18,094,831 through 20,994,831 us, 100 ms intervals;
  exported by the debug activity from emulator-5554. Recurrent states carried
  between frames. Output `sequence.gif`, individual paired PNGs and
  `sequence-report.json` in the same directory.
- Sequence total 5.13 seconds including PNG/GIF generation; this is NOT Android
  inference speed. The diagnostic is 10 fps, not a full-rate video acceptance test.

## Observations and decision

The single-frame silhouette excludes the curtain above the head without our
accessory-component heuristic. Fine side hair is retained, although edges are
still soft. The two same-alpha compositions look very similar: foreground RGB
recovery does not visibly eliminate the broad pale backlit hair region here.
At the sampled sequential checkpoint and final close-up, face and shirt remain
present, but pale hair remains. These snapshots do not prove absence of flicker.

This rules out treating foreground-colour recovery alone as the missing fix.
The silhouette improvement warrants comparison on the other two sources and
full-resolution frames, but does not justify integrating RVM now. Need separate
evaluation of genuine source backlighting versus leaked background before any
further despill operation; suppressing all bright hair would alter the subject.

## Verification

- `:app:testDebugUnitTest :app:assembleDebug`: passed.
- New debug APK installed on emulator-5554; frame-export probe returned
  `status=ok`, `frames=30`, mask 270x480. All 30 input PNGs processed locally.
- Python shape, finite-value, alpha-range and advancing-PTS assertions passed.
- APK listing contains no RVM model, MP4 or Golden fixtures.
- Production compositor and segmentation are unchanged; no new app-render MP4
  was generated in this experiment. The GIF is a diagnostic, not a montage.
