# Veycad engine integration and system report — Samsung A25

## Scope and environment

- Date: 2026-09-02.
- Device: physical Samsung `SM-A256E` (`a25x`), Android 16 / API 36.
- Application: `com.example.autoedit` 0.1.0, debug APK built from `codex/engine-reboot`.
- Inputs: three local Golden MP4 files plus a near-static derivative; separate local phonk MP3.
- Privacy: all fixtures remained in the repository test-fixture directory or the app-specific device
  test directory. Nothing was uploaded or sent to another person/team, and fixtures are absent from
  the APK.

## Build and automated verification

- `./gradlew clean testDebugUnitTest lintDebug assembleDebug`: passed; 51 tasks executed from clean.
- Unit tests: 67 passed.
- APK installation with `adb install -r -t`: passed.
- Normal launcher after the render sequence: process started successfully.
- Final device log scan: no `FATAL EXCEPTION`, application ANR, Golden failure, MediaCodec error, or
  `GL_OUT_OF_MEMORY` entry.
- During the three-candidate AUTO run: about 219 MB PSS; AP 36 C, battery 28.7 C, skin 32 C; all
  thermal statuses normal.

## End-to-end results

| Scenario | Winner/style | Clips | Camera events / whips | Reference fit | Face loss | Edge leak | Result |
|---|---:|---:|---:|---:|---:|---:|---:|
| Golden 0 AUTO, three renders | BALANCED | 10 | no whip pass | 0.7304 | 0.0506 | 0.0302 | accepted |
| Golden 1 dynamic | DYNAMIC | 13 | 31 / 2 | 0.9226 | 0.0843 | 0.1081 | accepted |
| Golden 2 dynamic | DYNAMIC | 13 | 37 / 1 | 0.9030 | 0.1180 | 0.0625 | accepted |
| Near-static | DYNAMIC | 13 | 0 / 0 | 0.7237 | 0.0000 | 0.0000 | accepted |

All four outputs contain 534 encoded frames over 17.792 seconds. Each reports 241 beats, 626 onsets,
100% beat-hit rate and 25,334 us A/V last-sample drift. The three live-source outputs produced
39–51 semantic depth planes and one foreground re-entry. The static output used 13 virtual-camera
clips without inventing directional motion.

## Module participation evidence

| Engine module | Integration evidence |
|---|---|
| Audio decode / `AudioBeatMap` | Separate MP3 decoded; 241 beats and 626 onsets classified by strength/band/downbeat on sample-index timestamps. Boundaries reached 100% decoded beat-hit. |
| Visual analysis / `VisualEventMap` | 132–172 events on moving sources; masks, faces, pose/composition, 39–51 depth planes and 88–104 flow planes reached the director/renderer. Static noise gate produced zero camera events. |
| `EventMatchingDirector` | AUTO rendered all three structurally distinct candidates for Golden 0. BALANCED beat DYNAMIC on decoded quality; CINEMATIC was rejected for `reference-grammar-fit`. No candidate reused source ranges. |
| `EffectOrchestrator` | Dynamic Golden 1 retained two evidence-backed whips; static source retained none. Foreground re-entry, cooldown/budget and incompatibility rules executed before rendering. |
| Two-decoder compositor | Golden 1 executed 29 transition frames and Golden 2 executed 20; inspector accepted them only with both live decoder inputs. |
| Directional blur | Golden 1/2 executed 29/20 dedicated FBO draws. Static regression executed none after VME-008 fix. |
| Foreground and depth | Moving Goldens executed 8 foreground and 8 semantic-depth FBO draws each; no decoded black-block artifact. |
| Glow pipeline | Golden 1 executed 13 extract/blur/final draws; Golden 2 executed 6 of each. |
| Virtual camera and speed mapping | 10–13 role-aware clips per output; static footage remained editable with 13 alternating push/pull/drift transforms. |
| H.264 / owned AAC mux | Every output contains AVC video and AAC audio; graph duration and audio loop/cut converge at sample clock with 25,334 us final-sample drift. |
| Decoded MP4 acceptance | Beat-hit, repeated ranges, transition strength, A/V drift, artifacts, colour jumps, face loss, black blocks and reference grammar were evaluated after encoding. All selected outputs passed. |

## Visual inspection

Frames at approximately 1.0, 9.0 and 16.8 seconds were decoded from each pulled A25 file with the
container's preferred transform applied. All were upright portrait frames. No full/partial black
rectangle, upside-down output or missing-subject frame was observed in these twelve samples. Shot
scale and source moment changed across the moving-source outputs. This sampling supplements, but does
not replace, the per-frame/tile decoded metrics.

## Defect found and fixed during the run

The initial near-static render incorrectly scheduled nine directional-blur frames from two isolated
codec-noise flow spikes. `VisualEventMapAnalyzer` now requires population-level directional support
on production-sized timelines. The repeated static render reports zero camera events/whips, while a
repeated dynamic Golden 1 still produces 31 camera events, two whips and 29 blur draws.

## Remaining risks

1. Foreground matte edge leak is above the `0.02` reference target on all three moving Goldens,
   especially Golden 1 (`0.1081`). It remains diagnostic because the current post-render semantic
   re-segmentation is affected by grading, but it is a visible-quality debt (VME-007).
2. Golden 2 face loss (`0.11798`) is only 0.20 percentage points below the `0.12` rejection threshold
   and needs additional crop safety (VME-009).
3. Semantic depth is not a learned metric-depth model (VME-003).
4. Debug APK remains approximately 123 MB because bundled perception models are not optimized
   (VME-004).
5. Render wall time is not yet emitted as a machine-readable stage metric; this run showed that the
   full three-candidate AUTO path is materially slower than a single candidate, but the report does
   not invent an exact duration without an in-engine timer.

## Artifact hashes

- Golden 0: `7a60f64166d72c20552554ccba93141df6fe021ab7b7588c3fa651c7c4709221`
- Golden 1: `8c42265a53304741c8bb202b991018eb9bdd930936fe88631388a2c86d5006e1`
- Golden 2: `a26775124497b9c69f7719f07b5cab9cef8ad31538168e7d2e927ee5934b9cc9`
- Static: `17a2de7fee97b694dbbc0739b4d07411bfaf51ac55da53328534cdc7389a8e45`
