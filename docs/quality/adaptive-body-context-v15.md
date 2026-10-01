# Person-pure multi-axis context / cache v15

2026-09-26. Previous continuation only inspected current files: no implementation,
new test evidence or live job. This turn revalidated that state and added a known
displacement control before changing the matcher. Device was absent, not a live
render to restart. Existing emulator is subsequently booted without userdata reset.

## Controlled evidence, not independent release material

`SmoothClothingMotionDiagnosticTest` reuses the articulated proxy's geometry but
uses new background seed914037 and clothing seeds71921..71932. Clothing is cosine
interpolation between seeded luma .2.. .7 knots, spacing4 or8 native pixels. Two
heads/torsos are static; arms/legs move independently +/-6 native pixels. Full
144x81 mask ground-truth mean intensity .3466683, camera0, actual interval250ms.
Three lattice phases0/1/2. The predeclared absolute error tolerance .08 is unchanged.

V14 already passed spacing4: .34706977/.34098962/.34614894, coverage .757619/
.73142856/.7442857. Thus there was no evidence to change that moderately smooth
case alone. Spacing8 failed all three initial test methods (motion, camera-only
pan, static contrast gain.6): no accepted measurement. This is a known textured
positive challenge, not permission to lower fit/texture/coverage gates.

Exploratory vertical/horizontal long queries recovered the landscape challenge.
A 90-degree rotation exposed sampling/coverage bias: truth becomes .5200024
because native6px now corresponds to height144/reference72, magnitude1. First
two long-query orientations reported .36494195 at coverage .4757143. Adding
lengths2/4/8 but retaining one narrow short axis reported .42633414 (unresolved
error .09366826 > .08). Choosing the largest agreeing context improved actual
area but still failed at .41319773. Representative reliable native queries were
correct; the failure was disproportionate supported area/confidence, not proof
of an incorrect individual displacement. No assertion was removed or loosened.
These explorations tuned this fixture; it is NOT a blind holdout or real clothing
validation. The rotation must not be counted as another source parent.

## Implemented measurement rules

Camera estimator, clean-background query purity and consensus are unchanged.
After supported camera, smallest reference-radius.5 query remains. At each pure
valid body center also examine distinct shape factors `(1,L),(L,1),(2,L),(L,2)`
for L2/4/8. Native radii = ceil(.5*samplingAxis*shapeAxis). This enlarges the short
axis as well when its initial native3-pixel query lacks two-dimensional evidence.
Every current query pixel must have person probability>=.5 BEFORE matching it;
the returned footprint is also checked. No body query can borrow background as
positive evidence. Original exhaustive native-quarter search, bounded affine
photometry, texture .02, fit .05, uniqueness .02, roundtrip and confidence .5
remain unchanged. Physical displacement units and real PTS are unchanged.

ALL independently reliable shapes at a center must agree on the exact discrete
displacement. Any conflict is unknown. Pick the largest actual reliable query
footprint, deterministically, not the largest activity or highest confidence.
Original mixed large-context check can only veto a non-camera deviation; it still
cannot supply body/camera evidence. If no shape is reliable, no new support exists.
Empty requested-center lists return empty before allocating interpolated planes.

Pixel ownership, mask-mass denominator and non-overlapping witness count use the
ACTUAL selected query dimensions. Pixels are never extrapolated beyond validated
footprints or counted twice. Minimum4 disjoint witnesses and25% full-frame mask
mass, semantic gates, FEAR .18 and sustained runs are unchanged. Cache15 rejects
all old versions including14; binary optional measurement format is unchanged.
Method `person-adaptive-affine-background-query-fov-v9`; probe schema3 unchanged.

Final targeted proxy evidence: landscape spacing4 phases .3281786/.32974827/
.3298914 (coverage .96); spacing8 .28661016/.3142703/.28702632 (coverage .74857146/
.81809527/.74857146). All within original .08, not exact unbiased whole-mask means.
Rotated spacing4 .51695675 at coverage1; spacing8 .4703189 at coverage .8752381,
truth .5200024. Camera-only pan and static gain.6 return zero subject within original
.0001 checks at both spacings. Periodic clothing remains unknown despite supported
static textured background; it has zero reliable local witnesses. Existing independent
foreground-noise, overlapping-witness, native-area conservation, subpixel and white-noise
articulated controls passed targeted checks. Full suite/build results recorded below.

Limitations: supporting a larger patch is not independent per-pixel flow, a spatial
confidence probability, a guaranteed unbiased full-mask estimate or limb identity.
Unknown mass/confidence weighting may still bias the scalar. Query minimum refers
only to current semantics; previous mask/identity, occlusion, rotation/zoom/parallax,
blur/deformation, real texture validation and runtime costs are still unresolved.
No threshold relaxation or real acceptance claim follows from these controls.

## Preregistered disclosed real calibration

After full tests/build, freeze source/resources/tests/APK/docs/tools/reports in new
`artifacts/quality/baseline-v13-adaptive-body-20260926.tar` before installation.
Planned APK SHA `c92ab3ee93f4bd8d1b80f10a482ad3cff96be5a04cca3940bc53ecd9155e0a8d`.
Output `calibration_v13_fear_adaptive_body_20260926.mp4[.result]`, optional exact-PTS
replay `calibration_v13_motion_adaptive_probe_v15_20260926.json[.result]`, read-only
cache report `calibration_v13_fear_motion_v15_diagnostic_20260926.json`. No overwrite.

Same disclosed coconut input SHA
`ac54e7a8a6ede438cd08e0ff0b6c5cce6fc556af62ecace9747b142bbb5190cb` and unchanged
original FEAR music SHA
`2fc09a0cb163ad9eb6cdda3095fcf103ab4aab7b9dc52ec3a3e5564b085bce37`.
Revalidate API36/model Android SDK built for x86_64, installed APK hash/no splits,
input hashes, absent golden thread and absent output/marker. Start once, observe
that worker to terminal. Timeout alone does not permit restart. Capture measured/
unknown, sustained runs and exact-PTS local stage/area counters. If no export,
positive challenge remains failed, not a successful negative. If export, require
decoded video/audio gates and new exact-hash human acceptance. More supported
area/measurements alone do not prove physical accuracy or meaningful montage.

Full four-product objective remains open: independent parents/conditions/formats,
three positive parents per style, three meaningful DUALITY pairs in BOTH orders,
mandatory negative cases, style-specific story/selection/transition/music gates
and real human name/date/1x+half-speed review. Do not reuse WhiteKing's old review,
relabel the disclosed coconut as blind, or transfer new DUALITY machine passes to
human acceptance. Sigma mask/rhythm and Heartbeat face regressions remain open.

## Full local verification before device installation

414 Android tests in64 suites, failures/errors/skipped0. Full test task and debug
assembly completed successfully in2m46s.65 Python quality tests passed. `git diff
--check` passed (only existing CRLF conversion warnings). Final APK SHA matches
the preregistration above. Version/method changes are in this APK, not a reused
v14 binary. No device golden thread; planned output/probe and both markers absent.
Existing input hashes/API36/model match; no original, music or old output replaced.

## Interrupted first device attempt and recovery

Immutable pre-install archive SHA
`ed5bd273b0c781d5da1d595ef975a9e472c0251ba11e1d984e15210e179344b3`.
First planned attempt started17:05:40 UTC, worker PID2016/TID2037 confirmed live.
The continuation was interrupted; at18:42:38 UTC neither adb device nor emulator/
qemu process existed. Original observation session78579 was also missing. This
is an actual stopped runtime, not an observation timeout. Restarted the SAME AVD
with existing userdata, no snapshot/data reset and no app reinstall. Once boot
completed, original golden worker absent, output AND marker absent, no v15 cache,
and last completed analysis remains v14 at15:26:23. First attempt is INTERRUPTED/
unreported, not a typed material refusal, successful negative or completed render.
No inference about its physical motion or elapsed completed analysis is possible.

Before rerun, installed base.apk/no splits, source and music hashes matched the
preregistration; old caches/output are preserved. New attempt planned under
`calibration_v13_fear_adaptive_body_retry_20260926.mp4[.result]` (both absent),
same unchanged frozen APK/engine and input. Only after authoritative absence of
the old runtime is this new invocation allowed. The originally planned probe/cache
diagnostic names remain unused and reserved. Observe the new worker to terminal;
do not restart it solely because an observation session times out.

## Completed retry: positive challenge still failed

Retry started18:44:31 UTC, PID1981/TID2000 confirmed live during analysis. Analysis
terminal18:50:46 UTC, worker absent by18:52:00. Result marker SHA
`665103ebaa06d73b622504c6a6997cece562fa170d80e986cd52efada28f5809`.
Runtime APK/no splits/API36/model/source/render-source/music identities all match.
No MP4, `material_rejected/insufficient_motion_evidence`. Not a successful negative.

374847ms analysis,121 observations/semantic frames/masks,277 model successes.
2 measured/119 unknown vs v14's0/121. Cache v15 SHA
`703c4392a2571f0c9885caf372b4c6da6ea0ef0ee06571ae25472350b05386c4`.
Read-only diagnostic SHA
`345d31cdda1250c90dfd9aac5dc7fc2d1c049ab969145822e607897ae2e1a503`.
These two supported measurements are BELOW FEAR's unchanged .18 threshold:

| Actual PTS us | Subject scalar | Camera scalar | Body fraction | Disjoint body witnesses |
| --- | --- | --- | --- | --- |
| 4633000 | .125232697 | .048181010 | .394229889 | 36 |
| 5133000 | .166038454 | .015170799 | .250429899 | 24 |

Both actual intervals233000us; background11/12 witnesses,3 quadrants and camera
confidence .660032868/.673590899. Source legacy peaks unchanged (.591092467 camera,
.225634983 subject), never fallback. Moving/run/span0. Former best v14 fraction
.21173225 at4633000 now .394229889 with larger validated queries, not extrapolated
unowned pixels. More counted area is not physical per-pixel accuracy or montage
quality. No independent ground truth on this footage. PTS5133000 cached mask temporal
IoU .011525796 is a further identity/previous-semantics warning, not proof of a cut,
wrong mask or reliable continuity; no claim that current masks establish identity.

Real elapsed time more than doubled from175026ms; this change does NOT prove acceptable
production performance. No grammar, .18 threshold, music, old artifact, human review
or release matrix changed to get an export. Four-product readiness remains unproven.

Exact-PTS probe started once18:52:57 UTC under the original reserved JSON name,
same source/current-cache/APK. It recomputes correspondences on exact decoded pixels
using cached semantics only; it is not a new inference, director run or acceptance.
Unlike all-null v14, agreement on both non-null measurements can now be checked.
Probe result/stage coverage evidence was pending until its own worker was terminal.

## Completed exact-PTS replay

Probe worker PID1981/TID2118 remained confirmed live through18:58:57 UTC; terminal
marker and absence of the worker confirmed19:00:11 UTC (already27 September in
the user's timezone). Artifact names retain their preregistered26 September date.
JSON SHA `3a09dd44e0892751f4f1ae07f2552e09de4dccbc7c72435de3f9b7c454dee9ca`.
Schema3/cache/source/runtime APK/API/model identities all match.121/121 agreements
use data-class exact equality, INCLUDING both non-null full motion measurements
at4633000/5133000, not just two scalar tolerances or all-null agreement. This
verifies deterministic replay of current pixels+cached labels, not semantic or
physical correctness, identity, human acceptance or montage quality.

Stages: FIRST_FRAME1, MASK_UNAVAILABLE42, HUMAN_UNAVAILABLE9,
CAMERA_UNSUPPORTED54, PERSON_COVERAGE13, MEASURED2. Independently supported
camera reaches body on15 observations. Every13 rejected body sample has actual
covered mask mass below.25 (range .021888673.. .20141111);5 of those also have
fewer than4 disjoint witnesses. Thus high dense match counts cannot replace
body-area coverage. The .20141111 case at4400000 has43 reliable overlapping
queries/16 disjoint witnesses;6400000 has46/27 but fraction.18751465.
Former old measured17900000 now has1 reliable query/1 witness/.04457891 after
adaptive context, insufficient. No raw unsupported scalar is promoted to evidence.

The two measured points still supply0 above-threshold moving observations.
Better query support has not resolved this positive FEAR challenge or justified
changing its product grammar. Next motion work needs profiling and independent
blur/deformation/previous-mask identity controls, not another grid-count or
threshold relaxation. Broader goal work in this continuation also prepared the
separate non-ready v2 cohort and prevents known exposed parents from new sealing;
see `next-cohort-v2.md`.414 Android tests remain the authoritative unchanged-APK
run;73 Python tests passed after the forward-cohort/pre-seal safeguards.
