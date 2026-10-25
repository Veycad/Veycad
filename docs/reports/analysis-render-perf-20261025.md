# Analysis copies and encoder polling: 2026-10-25

The renderer finishes the measured four-second export faster on Samsung A25 without changing decoded video, timestamps, audio or analysis outputs. The analysis-copy change removes unnecessary allocations; its small timing difference is not an established standalone speedup.

## Changes

- `MulticlassMatteProvider` constructs an owned Bitmap/MPImage only for exact-matte inference. Frame-only analysis writes the decoder's borrowed bitmap directly to the existing cache. Inference ownership, cleanup, cancellation and validation remain intact.
- `MediaCodecSpeedRampRenderer` polls ready encoder packets with timeout zero between submitted frames. Final EOS draining still waits with 10,000 us polling and the existing 20-second stall deadline. Codec settings, rendering, frame planning, bitrate and audio are unchanged.

## Measured results

Samsung SM-A256E (Galaxy A25), Android 16 / API 36, ARM64, USB charging. Battery temperature before the four blocks: 30.0, 31.0, 31.7 and 32.6 degrees C. Order: baseline A, optimized A, baseline B, optimized B. Each block has one excluded warmup plus three measured repetitions: six measurements per build and workload.

| Workload | Baseline median | Optimized median | Time reduction |
| --- | ---: | ---: | ---: |
| Four-second export, 720x1280, 30 fps | 5.396 s | 4.134 s | 23.4% |
| Four-second export, 720x1280, 60 fps | 7.288 s | 4.614 s | 36.7% |
| Frame decode/cache stage, 72 targets | 5.084 s | 4.932 s | 3.0% observed; overlapping samples |

All six optimized export times are lower than all six baseline times at each frame rate. Analysis timings overlap and the second pair of blocks reverses the small analysis difference; do not advertise a reliable analysis-stage acceleration from these measurements.

The API 36 x86_64 emulator with host GPU independently shows 14.7% / 15.3% export-time reductions at 30 / 60 fps. Its analysis difference is only 0.5%. Emulator numbers do not substitute for the A25 measurements.

## Quality evidence

For each device, FFmpeg completely decoded all 24 measured exports (4,320 decoded frames). All decoded frame checksums, frame timestamps and decoded float PCM match exactly between repeated baseline and optimized exports at the same frame rate. Both devices passed this comparison independently; decoder differences between devices are not compared.

The instrumented probe also checks actual AVC packet counts (120 / 240), every packet PTS, the final frame after EOS, video geometry, one AVC plus one AAC track, decoded audio duration and non-silent audio. The 72 cached analysis frames including pixels/dimensions/PTS and three exact-matte planes match exactly across builds on each device.

Source: existing `dexter-matte-reference.mp4`, 18.033 seconds, 720x720, 30 fps. Input SHA-256, model SHA-256, all six APK hashes, every raw measurement/report and comparison results are retained in [the machine-readable evidence](../evidence/analysis-render-perf-20261025.json). The shared graph produces four seconds with a cut, whip transition, push-in, color grade, speed ramp and music, at 5 Mbps AVC. Timings cover the complete renderer including audio muxing; analysis timing covers `MulticlassMatteClient.analyze`, not the whole automatic editing pipeline.

## Validation and limits

- `clean baselineVerify -Pandroid.injected.build.abi=arm64-v8a --max-workers=2` rebuilt and ran 1,392 JVM and 340 Python tests successfully. Its lint step initially failed on this worktree's ignored `local.properties` SDK-path escaping. After correcting it, `:app:lintAnalyzeDebug --rerun :app:lintReportDebug --rerun baselineVerify` passed, including lint (0 errors, 42 existing warnings), release security contract and debug APK assembly. The explicit lint rerun was needed because Gradle had cached the old local-path diagnostic.
- All eight retained benchmark blocks passed (four on A25, four on emulator).
- A25 supplementary native regression run: 11/12 passed. Native export, default small probe, 30/60 fps seeking/EOS and cancellation/cleanup cases passed. `VideoDecoderCursorDeviceTest.normalized_first_advance_without_begin_range_adds_raw_origin_exactly_once` fails its fixture keyframe precondition at line 34, before creating a decoder. The exact same test fails on the untouched baseline APK. This pre-existing device-dependent fixture issue is not fixed or reported as a passing test here.
- Early setup attempts with software GPU and an unsmudged Git LFS model were excluded. Both accepted APK variants contain the verified real model; within each ABI, ZIP entry content differs only in the application DEX.
- No codec quality settings or analysis sampling were reduced. No proof is claimed for other footage, longer sustained thermal loads, other devices, resolutions or codec combinations. Human editorial acceptance and the full UI suite are separate from this bounded performance/quality comparison.
- Fresh independent review found no Critical, Important or Minor findings and independently repeated all 48 export comparisons and verified the six APK/eight report hashes. No injected MPImage-construction failure or driver hang was tested; exceptional ownership/cleanup was reviewed in code. [Draft PR #69](https://github.com/Veycad/Veycad/pull/69) retains these limits and awaits CI/human acceptance.

## Reproduction

Build both production revisions with the same `MediaPerformanceDeviceTest` test APK and real Git LFS model. Install the isolated `com.veycad.app.uitest` package; do not clear the user's preview application. Place the same source in that package's private `files/media-performance/input.mp4`.

```text
adb -s DEVICE shell am instrument -w -r \
  -e class com.veycad.app.MediaPerformanceDeviceTest \
  -e perfRun UNIQUE_RUN_NAME -e perfRepetitions 3 \
  -e perfSource /data/user/0/com.veycad.app.uitest/files/media-performance/input.mp4 \
  com.veycad.app.uitest.test/androidx.test.runner.AndroidJUnitRunner
```

Alternate the APKs in the order above. Export each private run folder using `adb exec-out run-as com.veycad.app.uitest tar -C files/media-performance -cf - UNIQUE_RUN_NAME` with binary-safe output capture. Compare the extracted folders:

```text
python tools/compare_media_performance.py --baseline BASE_A BASE_B \
  --optimized OPT_A OPT_B --ffmpeg /path/to/ffmpeg --output comparison.json
```

The comparison fails on incomplete reports, changed input/device/mattes, mismatched analysis pixels, incomplete decode, video/PTS/PCM differences, or fewer than three positive measurements per build. Videos/APKs and local logs remain in ignored `artifacts/perf`; they are not committed.
