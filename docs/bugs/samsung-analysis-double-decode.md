# VME-051 — Samsung cold analysis repeats random 4K/HEVC seeks

## Status

Resolved on 2026-09-15. Cold analysis now uses a single sequential codec session, bounded semantic
inference and a render wake guard. Repeat edits continue to use the local versioned cache.

## Reproduction

- Device: Samsung SM-A256E (`R5CX109MBJF`).
- Local fixture: `artifacts/device-regression/samsung-a25/gallery-latest-20260907_172718.mp4`.
- Source duration: 23.906 s, portrait rotation metadata, 96 analysis timestamps at 250 ms.
- Heartbeat music: local licensed `heartbeat-author.m4a` fixture on the device.

## Root cause

The isolated MediaPipe provider decoded all 96 timestamps through random
`MediaMetadataRetriever` seeks. The main process then decoded the same timestamps again for
flow, pose, face and composition. Pose and face inference were also awaited serially.

## Fix

- Decode the full analysis grid in one `MediaCodec`/GLES session. Every requested sample uses the
  first decoded presentation timestamp at or after its target; codec output buffers are released
  immediately so Samsung's vendor buffer pool cannot stall.
- Keep 250 ms motion/flow observations, run broad ML Kit subject segmentation and face analysis on
  a 500 ms grid, and run pose at 1 s or on strong local subject motion. Face and pose tasks run in
  parallel. The heavier multiclass MediaPipe matte is reserved for the bounded render targets that
  explicitly require an exact cutout.
- Interpolate validated immutable attachment planes without reconstructing and revalidating their
  pixel arrays for every 60 fps output frame.
- Hold a timeout-bounded partial wake lock while analysis/render is active, and keep the activity
  screen on where Android permits it, so an unattended render continues under Doze.
- Persist the full analysis by source SHA-256 in cache schema v2. The cache remains local, bounded
  to three sources, and contains no original video or face template.

## Measured A/B

| Build | Start epoch | Result epoch | Total | Semantic frames | Model successes |
|---|---:|---:|---:|---:|---:|
| Baseline | 1788784591.670 | 1788784971 | 379.3 s | 96 | 222 |
| Raw-frame cache attempt | 1788785235.389 | 1788785620 | 384.6 s | 96 | 222 |
| Sequential decode experiment | 1788786202.557 | 1788786524 | 321.4 s | 96 | 224 |

The sequential experiment was 57.9 s (15.3%) faster, but synchronized three-video review rejected
it: different analysis pixels caused weaker source-window choices at 3.733 and 12.5 seconds. It was
removed from the active path. Exact decoded-frame reuse, parallel pose/face execution and the
versioned full-result cache remain. Cold exact-frame cache population completed in 356.9 s (22.4 s faster than the 379.3 s baseline) with the same 96 frames and 222 model successes. A subsequent installed-build render hit the cache five seconds after launch and completed in 175.5 s total, saving 203.8 s (53.7%) against baseline. Its 1,271 primary and secondary decoder clocks match the cold exact-frame run; the only authored A/B change was the Heartbeat echo envelope.

## 2026-09-15 reproduction on Galaxy S23 Ultra

- Device: Samsung SM-S918B (`R5CWC0Z334V`), Android API 36.
- Gallery source: `20260915_114016.mp4`, 23.156 s, 3840x2160, 60 fps, portrait rotation metadata,
  HEVC/HDR10+, 415,665,393 bytes.
- `tap_render`: 11:46:28; `video_analysis_cache_store`: 11:49:43 — cold analysis took 195 s,
  or 8.4x source duration.
- At a 250 ms analysis interval about 93 samples were expected. Only 34 semantic/mask frames were
  stored; the cache recorded 67 successful model outputs including those 34 external masks.
- Android repeatedly reported `FrameDecoder` buffer-queue timeouts, `WOULD_BLOCK`, and
  `all codecs failed to extract frame` while `MediaMetadataRetriever.getScaledFrameAtTime()`
  performed ascending random seeks through the 4K stream.
- The complete render finished at 11:51:40. Container inspection passed, but visual acceptance
  failed with `face-loss=0.1027668`, consistent with the incomplete semantic timeline.

## 2026-09-15 fixed-path regression on Galaxy S23 Ultra

- Authorized local source: `IMG_1647.MOV`, 1,554 AVC frames; 104 requested analysis samples.
- Sequential decode completed in 12,164 ms. Full cold analysis completed in 50,142 ms with 104
  observations, 52 semantic frames and 99 successful model results.
- The earlier real product failure took 195 s and retained only 34 semantic frames. This observed
  regression is 74.3% shorter (3.9x faster), but it is not a strict codec A/B because the failing
  gallery source was 4K HEVC/HDR10+ and the authorized fixture is 1080p AVC.
- A full candidate MP4 passed container/render inspection: 1,271/1,271 shader frames, 467
  dual-decoder frames, 115 blackout frames, 912 AAC samples, 13,288 us A/V delta, rotation 0 and
  no reported issues.
- On the physical SM-S918B, `com.example.autoedit:montage-render` was observed as a partial wake
  lock while the device was in Doze. This prevents the prior screen-off CPU suspension.
- `testDebugUnitTest`, `lintDebug` and `assembleDebug` pass. APK inspection contains no Golden MP4
  or MOV fixtures.

Golden videos remain local project fixtures and APK fixture scanning must stay clean.
