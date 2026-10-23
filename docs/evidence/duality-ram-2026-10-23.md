# DUALITY: two 60-second sources and a 192 MiB heap

## Reproduction and cause

Baseline: PR #65 at `7d9228506bfd0221aae97e9a31c496913f85124a`.
The automatic editor retains both source analyses while directing/rendering DUALITY.
Each analysis previously retained per-frame mask/depth/flow planes although
`DualityLoopDirector` and its graph consume only scalar observations.
Two 240-frame mask/depth timelines alone can require 240 MiB of float payload.

On a task-owned AOSP API 36 x86_64 emulator, `dalvik.vm.heapgrowthlimit` and
`Runtime.maxMemory()` were 192 MiB. The profiled baseline reached 191.48 MiB
and threw `OutOfMemoryError` in the sampler. A separate rerun **without the sampler**
also crashed with an uncaught `OutOfMemoryError` in ML Kit's `MLHandler` thread
at the same 201,326,592-byte growth limit. No successful baseline export was produced.

## Fix

DUALITY now requests `editorial-observations-v1`. Semantic inference still produces
the same observation fields/cadence; its per-frame render planes are not retained,
and unused dense flow is not calculated. Other recipes retain their existing capabilities.
The new profile has a distinct cache key/header, cannot supply a caller requiring
planes, and rejects cached plane/refinement counts before allocating those planes.

## Inputs and method

- Two different local golden fixtures were read without alteration, looped and
  transcoded into separate 60.000-second AVC files, 720×1280 at 30 fps, without source audio.
  Their SHA-256 hashes are `4f41af1be6b8541401d55c923104663cd20dd591615034550de93b0f545a3263`
  and `717329d505ef8747f6378cb16005a30c2097204eba206507838a21e0ed609b25`.
- Both runs used those same files and the bundled authored DUALITY music.
- The existing debug `GoldenRenderActivity` invoked the real `VeycadAutomaticEditor`,
  models, GLES, AVC encoder and AAC muxer. `CapturingRenderEngine` was not used.
- The optional `ram_trace` sampler sleeps 250 ms between samples. It records Java
  used/limit bytes, main-process native allocated bytes, and main/matte-engine PSS.
  Query overhead increases the interval; sampled peaks are lower bounds.
- Cold means no cache for the new profile. Warm restarts the app process and loads
  both completed observation-only caches; two cache-hit events were confirmed.
- RAM-probe exports use the conservative capability preset used by the product request.

## Measured peaks (MiB, 1 MiB = 1,048,576 bytes)

| Run | Java used | Main native allocated | Main PSS | Matte-engine PSS | Combined PSS |
| --- | ---: | ---: | ---: | ---: | ---: |
| Baseline, profiled, OOM | 191.48 | 139.66 | 410.78 | 22.69 | 433.47 |
| Fixed, cold analysis + complete export | 38.73 | 135.65 | 202.46 | 51.31 | 231.36 |
| Fixed, warm cache + complete export | 42.50 | 70.65 | 142.63 | 0.00 | 142.63 |

Combined PSS is the maximum simultaneous sum, not the sum of separate process maxima.
Java/native allocation and PSS describe different views and must not be added together.
Codec services outside these two processes and total device/GPU memory are not fully covered.

Both fixed runs completed with `status=ok`, 240 observations and zero retained
attachment frames **per source**. Cold analysis actually ran semantic inference on
all 480 samples. Each export contains 549 AVC frames (720×1280, 18.300 seconds)
and AAC audio (18.320544 seconds); independent FFmpeg full-file decode returned 0.

## Checks and repeatability

- The new capability regression failed on the baseline before the fix.
- Targeted JVM checks: 49/49 passed.
- `clean baselineVerify`: 1023 JVM + 290 Python tests passed, no failures/errors/skips;
  lint, security contract and debug APK passed.
- `DualityMemoryDeviceTest`: passed on the same 192 MiB-heap emulator. It generates
  two distinct 60-second sources, performs real cold inference, retains both analyses,
  checks 480 observations/zero planes and validates warm-cache scalar round-trip.
- The complete automated UI suite discovers this regression normally. Exact pushed-SHA
  CI results and the independent review are recorded in PR #65.

To repeat, prepare a dedicated API 36 AVD with `vm.heapSize=192M`, verify the running
heap property, install the debug APK, and place inputs/music under its private files
directory with `run-as`. Start `com.veycad.app/.GoldenRenderActivity` with `source`,
`secondary_source`, `music`, `output`, `style=AUTO`, `duality=true`, and an absolute
`ram_trace` CSV path. Force-stop/restart between cold and warm runs; preserve the cache
for warm. Keep the CSV and `.mp4.result` marker together with the exported MP4.

The original videos, generated inputs/exports, CSVs and logs are not committed.
This establishes the reproduced heap failure and its fix on an emulator; physical
ARM/OEM codecs, total-device low-memory killing, power loss and artistic acceptance remain open.
