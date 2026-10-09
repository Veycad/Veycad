# Time-dependent matte filtering — rejected alternatives

Date: 2026-09-05. Baseline: accessory-support-g1 / production shader at 67b8539.

## Confirmed mechanism

Opening shader independently changes erosion radius from .65 to 2.88 texels,
core blend .38 to .97, and alpha thresholds (.42,.74) to (.66,.89) as
foreground_reentry advances from .62 to .78. A fixed mask therefore contracts
even without source motion. `tools/probe_matte_phase.py` reproduces the sampling
and filtering equations (not the full GLES pipeline). On the archived Golden 1
mask it loses 14.6167% alpha area. That mask predates accessory filtering, so the
percentage is a mechanism diagnostic, not a current app quality measurement.

## Controlled app experiments

Both candidates retained music, source selection, timing and all other shader
logic. The first 76 frame inspector entries exactly match the baseline for both
candidates, including source PTS, scale, entrance envelope and mask confidence.

| Variant | Max edge leak | Min temporal IoU | Decision |
|---|---:|---:|---|
| Baseline accessory-support-g1 | .009514536 | .893773 | Existing imperfect baseline |
| phase-stable-g1: constant settled/strong filter | .009616174 | .8942841 | Reject: visible dark cut into crown at 1.5s |
| phase-soft-g1: constant early/soft filter | .06899682 | .9810222 | Reject: increased pale fringe/background at 1.8–2.0s |

The strong candidate's 1.5s checkpoint is source PTS 19,636,685 us in both
baseline and candidate. No improvement can be attributed to selecting a different
source moment. Higher temporal IoU in the soft candidate does not indicate a
better silhouette: a consistently oversized matte can score well.

Both rendered full 18.034s MP4s on emulator-5554, 542 frames, 72 distinct opening
source PTS, 14,649us A/V drift, zero black-block metric. Both continue to fail
the unrelated existing finale background isolation gate. The soft candidate also
fails opening edge-leak acceptance.

Files are local under `artifacts/reference-analysis/`, prefixes
`phase-stable-g1` and `phase-soft-g1`, with MP4, opening JPG, inspector JSON and
`.result.txt`. They are rejected experiments, NOT accepted application outputs.

## Retained work and next decision

Production shader and shader tests restored exactly to pre-experiment state.
No new model/dependency added to APK. The local diagnostic script and this evidence
remain to avoid repeating the same threshold experiment. Both candidate builds
and unit tests passed; strong candidate lint passed with 0 errors / 3 existing
warnings. Passing those checks did not establish visual quality.

Do not try another arbitrary interpolation between these endpoints. The next
experiment must improve the source matte itself and assess foreground retention
separately from background rejection. Animated contraction remains unresolved;
it must be removed together with a matte that does not need it to suppress leaks.
Goal is not complete; no three-source acceptance is claimed.
