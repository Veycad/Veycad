# Analysis and render performance implementation plan

> **For agentic workers:** Use superpowers:executing-plans for this approved, bounded change.

**Goal:** Remove unused frame copies and avoid waiting for unavailable encoder output between submitted frames, with measured performance and decoded-media equivalence.

**Architecture:** Keep frame decode, inference, GLES, encoder configuration, timestamps and QA unchanged. Construct the owned MPImage only when exact-matte inference needs it. Poll encoder output without blocking between frames; retain bounded blocking EOS drain.

**Tech Stack:** Kotlin, Android MediaCodec/GLES/MediaPipe, instrumentation, Python and FFmpeg.

**Spec:** User-approved scope in this chat: implement the two identified optimizations and verify quality and real speedup. Baseline commit: beca0960fbac96837e39a92ed751909eb9e83715.

## Constraints and alternatives

- Preserve exact-matte image ownership and cleanup, all frame pixels/PTS, encoder settings, audio and quality gates.
- No model concurrency, decoder seeking changes, cache identity changes or quality reductions.
- Use the same instrumentation harness, inputs, device and output settings for both builds; keep baseline APK and evidence.
- A runtime time reduction must be measured, not inferred from the code. Emulator results do not establish Samsung A25 performance.
- The chosen small changes replace the earlier broader concurrency/streaming proposals; those require separate profiling.

## Review focus

- Final delayed encoder packets and EOS must be drained, including at 60 fps.
- Codec backpressure must not deadlock rendering.
- Exact-matte MPImage must still own a copy and close it on failure.
- Frame-only output must retain identical decoded pixels and PTS.
- Cancellation and ordinary native export must continue to work.

## Task 1: Remove unused analysis copies

- [x] Add `MediaPerformanceDeviceTest` to exercise real frame-only and exact-matte provider paths, recording frame/plane hashes and elapsed time.
- [x] Build the untouched production baseline with the common test harness and retain APKs.
- [x] In `MulticlassMatteProvider.analyze`, write the borrowed frame to cache directly; construct/copy/close MPImage only inside the non-null segmenter branch.
- [x] Run provider comparison and exact-matte regression; compare pixel/PTS and mask signatures.
- [x] Commit this scoped change and its test after verification (`4bbbd090`).

## Task 2: Avoid empty encoder waits

- [x] Extend the common harness to export the same moving/transition graph at 30/60 fps with AAC, recording whole-render time and validating actual frame counts and final PTS.
- [x] In `MediaCodecSpeedRampRenderer.drainEncoder`, use timeout 0 between frames and 10000 us while draining EOS. Preserve error handling and stall guard.
- [x] Run baseline/optimized/baseline/optimized blocks with warmup and repeat samples. Verify complete MP4 decode, per-frame hashes/PTS and decoded PCM against baseline.
- [x] Run `clean baselineVerify`, native smoke, provider/exact-matte checks, cancellation and applicable regressions; see the explicit baseline fixture failure and local lint rerun below.
- [x] Commit separately, obtain fresh review, publish draft PR with exact results and device limitations. PR #69; not merged.

## Execution record

- Original checkout is dirty and has malformed service refs; managed worktree creation succeeded at the verified remote commit.
- ADB initially has no attached devices. Phone measurement was requested; independent emulator and build work continues.
- 2026-10-25: Samsung A25 connected. Completed four retained blocks on both A25 and API36 host-GPU emulator. Six measured samples per build/workload; decoded video/PTS/PCM and analysis/matte output identical on each device.
- A25 export median reductions: 23.4% at 30 fps and 36.7% at 60 fps. Analysis 3.0% difference overlaps noise; no standalone analysis acceleration claim.
- Task 2: Ruling: A25's existing normalized-origin regression fails its generated-fixture keyframe precondition on both baseline and optimized APKs, before the decoder under test is constructed. Leave the unrelated fixture unchanged and report 11/12 supplementary tests; this one decoder scenario remains unverified on A25.
- 1,392 JVM and 340 Python tests pass. The initial clean gate failed solely on this worktree's ignored SDK-path escaping; fixed it and reran lint analysis/report explicitly, then `baselineVerify` passed (0 lint errors, 42 existing warnings).
- Exact protocol, raw measurements, APK hashes and limitations: [performance report](../../reports/analysis-render-perf-20261025.md).
- Final review: independent fresh reviewer found no Critical, Important or Minor findings. Independently rechecked all 48 decoded exports, six APK hashes and eight report hashes against the retained evidence.
- Final: Ruling: long-running backpressure, thermal throttling and other devices/resolutions/codecs remain outside the short measured scenario; no broad speed or liveness guarantee is made. Cost if extrapolated incorrectly: slower or stalled exports on an untested workload.
- Final: Ruling: normalized-origin native scenario remains blocked by the unchanged fixture precondition on A25; retain the explicit 11/12 result. Cost: this decoder scenario lacks device acceptance until that separate fixture is corrected.
- Final: Ruling: full UI, subjective editorial acceptance and total automatic-pipeline speed remain outside these measurements; PR stays draft with CI/human acceptance pending. Cost if inferred from these results: unrelated regressions or overall latency could go unnoticed.
- Final: Ruling: MPImage-construction failure and driver-hang fault injection were not performed; ownership and cleanup are reviewed and existing EOS deadline is unchanged. Cost: exceptional native-resource behavior lacks injected-failure runtime evidence.
