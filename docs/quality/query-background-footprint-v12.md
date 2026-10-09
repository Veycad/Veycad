# Query-patch background eligibility / cache v12

2026-09-26. Measurement correctness fix, not product/release acceptance. Previous
goal turn was progress: FOV units/geometry were implemented, counterexamples tested
and real v11 calibration/replay completed. It did not repair the disclosed FEAR
positive challenge; its baseline stays immutable and is not relabelled as holdout.

## Independent full-body proxy diagnostic

New deterministic synthetic scene 144×81, background random texture seed8401,
two independent texture seeds221/222. Each shape has head, torso, arms and legs;
texture travels with its rigid shape. A deliberately flat background is a separate
control. These are pixel/shape ground truths, not real humans or learned-mask tests,
not evidence about localized limb articulation, real segmentation, or human review.

Native body shifts +3/-3 px with static camera: expected camera=0, relative subject
intensity=1/3 in reference-FOV units. Current production measured 11 person cells,
supported fraction .34375, subject .33333334. Camera-only +3px pan is independently
recovered and compensated to subject=0. Static and additive light +.1 controls stay
measured zero. Partial left-body occlusion retains recoverable right torso matching,
but only 6 supported cells / fraction .1875, so production remains unknown rather
than certifying the missing body. These controls keep current quality thresholds.

Counterexample: BOTH shapes +3px on flat background. V11 declared MEASURED camera
x=.33333334, confidence=.964803, 54 agreeing background cells / 4 quadrants.
True camera was static; the image contained no textured background at all. Those
cells were background only at their CENTERS, while the patch used to match their
motion included moving foreground. Opposed shifts had happened to fail camera
consensus and masked this classification bug. The same-direction regression test
failed before the fix. Consensus thresholds were not weakened to remedy it.

## Fix and diagnostics

LocalMotionCorrespondence.Cell records the actual native X/Y patch radii.
PersonMaskProjection.patchMaximum samples current-frame mask probability at every
native query-patch pixel location (same center-aligned geometry). A camera candidate
requires maximum≤.15, not merely center≤.15. This excludes mixed foreground patches
from independent camera evidence. Subject aggregation still uses its previous
center probability and matching confidence; neither its threshold nor supported-mass
denominator is lowered. No previous-frame mask purity, identity tracking, rotation,
zoom/parallax or total physical mask coverage is claimed. Native area-filter footprint
and real probabilistic mask errors can still limit classification.

After fix, both flat-background controls have 0 reliable clean background cells,
CAMERA_UNSUPPORTED, no invented camera/subject measurement. Positive opposed-body
control still passes: 11 person cells, .34375 fraction, camera=0, subject=1/3;
63 genuine-background reliable cells (previously68). All older camera compensation,
FOV, noise, missing semantics, mask geometry and quarter-pixel tests remain intact.

Audit/probe schema2 separates counts for center-background, clean-query-background,
and person-center candidates: total/textured/fitted/unique/roundtrip/reliable, plus
mixed background patch exclusions. Global texture/fit totals must not be attributed
to the background without this partition. Empty counters at a pre-matching stage
do not imply a measured empty background. These are diagnostics, not new acceptance
criteria. Method `person-background-query-footprint-fov-v6`, cache v12 rejects v11
center-only eligibility; binary shape remains unchanged.

397 Android tests, failures/errors0. 65 Python quality tests passed. APK SHA
`1575afbadf4657e8cf5c0c2a183543a2b7e6894b95100556aa64f11e2f84812c`.

## Real calibration plan, fixed before installation

Save fresh immutable source/resources/tests/APK/docs/tools archive before install.
Fresh output `calibration_v11_fear_query_footprint_20260926.mp4[.result]`.
DISCLOSED coconut source SHA
`ac54e7a8a6ede438cd08e0ff0b6c5cce6fc556af62ecace9747b142bbb5190cb`,
unchanged original FEAR music SHA
`2fc09a0cb163ad9eb6cdda3095fcf103ab4aab7b9dc52ec3a3e5564b085bce37`.
Device API36 / Android SDK built for x86_64. Verify source/music/runtime APK,
absence of existing output and live golden worker before starting ONCE. A timeout
is not a terminal condition and must not trigger restart. Observe same worker until
terminal report and absent thread, then read v12 cache and run fresh exact-PTS stage
replay with cached current semantics. Replay output planned as
`calibration_v11_motion_partition_probe_v12_20260926.json[.result]`.
No new semantic claims from replay, no new parent from reusing calibration.

Stricter correct eligibility can REDUCE measured coverage; this alone is not success
or a physical-accuracy proof. A positive refusal is NOT a negative pass. If exported,
all encoded gates, independent actual AAC and new hash-bound human review remain
required. Existing release matrix and old human review must not be overwritten.
Small/non-rigid limb movement, real camera/person correspondence coverage and
performance are pending, as are Sigma masks/rhythm, Heartbeat face safety, new
DUALITY human review and an actually untouched independent multi-product cohort.

## Real result

Immutable baseline `artifacts/quality/baseline-v11-query-footprint-20260926.tar`, SHA
`8e6fa4a233b7b1705ca986d6174adc58e394df67fa8e49ccc336e582cbd3be59`.
Started once 14:56:44 UTC; analysis terminal14:58:47, PID2581/TID2605 absent
after completion. Installed base.apk, inputs, runtime API/model matched plan, no
split APK. `material_rejected/insufficient_motion_evidence`, no MP4. This disclosed
positive challenge remains failed, not a negative release pass.

122451 ms analysis, 121 observations/semantic frames/masks, 277 model successes.
397 Android and 65 Python tests passed. No performance improvement claim: previous
v11 was110321ms; these timings are observations, not a controlled hardware benchmark.

V12 cache SHA `7b57845b37611ec47ce8c773c8cf12f4ee771d0e81866f5fa38e96e097829a36`.
Read-only diagnostic `calibration_v11_fear_motion_v12_diagnostic_20260926.json`:
1 measured/120 unknown; moving/run/span0. Same measured PTS17900000, gap267000us.
Camera cells31→23, all4 quadrants retained, cameraIntensity .01290491→.01134691,
cameraConfidence .62545675→.64086980. Current relative subject .08372375,
4 cells / supported fraction .27346182; still below .18. Changed camera eligibility
entered actual production path. This is not ground truth for those real vectors.
Legacy peaks unchanged and are not substituted for missing correspondence.

Result SHA `3709c66b00be6eaa1512d6d847e5e021735d798720955928b81ca88108aea279`,
diagnostic SHA `80ba212dbfb0af94e9c9ec4f48ecf9ae0bdbdf35c039a55ec63690e55ed2cbe6`.

## Partition replay result

Probe `calibration_v11_motion_partition_probe_v12_20260926.json[.result]` terminal
by15:02:08 UTC, PID2581/TID2712 absent. Schema2, 121/121 measurement agreements;
runtime/source/cache identities match. JSON SHA
`3c9c1c7221ce430ca79bd113ecdf583f43072d536069ecf82498e0044105b227`.
Stages: FIRST_FRAME1, MASK_UNAVAILABLE42, HUMAN_UNAVAILABLE9,
CAMERA_UNSUPPORTED56, SUBJECT_UNSUPPORTED5, PERSON_COVERAGE7, MEASURED1.

For the69 observations reaching matching, separate medians (not a single median
frame, calibrated probabilities, or ratios of aggregated medians):

| Query classification | Candidates | Textured | Fitted | Unique match | Roundtrip | Reliable |
|---|---:|---:|---:|---:|---:|---:|
| Center background only | 92 | 84 | 11 | 58 | 77 | 5 |
| Clean complete query background | 39 | 33 | 8 | 24 | 32 | 3 |
| Person center | 48 | 48 | 1 | 27 | 35 | 0 |

Mixed background query exclusions min/median/max26/46/71. At the measured
PTS17900000, 143 background centers become94 clean query patches: 49 mixed
patches excluded, of which9 had reliable matches (32 center-background reliable
→23 clean-background reliable). Prior v11 camera consensus had31 agreeing cells,
not32 total reliable candidates; 31→23 alone must NOT be described as8 mixed patch
exclusions. New camera consensus retains23 agreeing cells and4 quadrants.

Of56 camera failures:49 have <8 clean reliable background cells,7 have enough
cells but <3 quadrants,0 have sufficient cells+quadrants yet fail consensus.
Current-frame mask/human rejection counts are unchanged. Eligibility changes can
move a failure earlier in the pipeline; smaller SUBJECT_UNSUPPORTED/PERSON_COVERAGE
counts are NOT proof of improved subject matching. Real accepted motion coverage
has not increased and visual quality is still unproven.

Partition evidence now supports testing photometric consistency and non-rigid/local
limb correspondence: person texture is frequently present while fit/support are poor.
It does NOT isolate illumination, deformation, camera search limits, aliasing, mask
error or any one cause. Next independent benchmarks must exercise localized limbs,
contrast changes, blur/noise, pure camera and static controls before adopting a new
estimator; no gate lowering for this disclosed source. Previous-frame eligibility,
other motion consumers and actual untouched four-product release series remain open.
