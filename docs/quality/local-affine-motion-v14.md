# Local body + bounded affine photometry / cache v14

2026-09-26. The previous goal turn made progress: query-footprint eligibility fixed
a known false-camera counterexample and partition replay identified unresolved
person photometric support. No live job remains from that turn. This change is
calibration, not a new blind parent or product acceptance.

## Independent defects reproduced before changing the estimator

Affine brightness control: deterministic144×81 texture seed19037, luma .25.. .75.
After image = .5 + gain*(translatedBefore-.5) + offset. Gains .6 and1.6, offsets
±.02, actual camera dx0 or3px. V12 offset-only fit gave0 fitted/reliable matches
even at gain.6: static and known-pan cases CAMERA_UNSUPPORTED. Mean subtraction
alone was not contrast compensation. New residual fits bounded positive gain
textureQuery/textureCandidate in [.5,2], both mean-absolute textures≥.02; offset
is removed separately. All160 matches now recover exact dx on both gains. Static
stays zero, pan is compensated to zero subject. Unrelated contrast-scaled noise
remains unknown; spatial gain discontinuity does not fabricate a camera pan.
The photometric model changed (numeric .05 fit/.02 uniqueness coefficients remain,
but residual now follows an affine brightness fit). No probability calibration,
arbitrary lighting invariance, extreme gain, clipping or blur robustness is claimed.

Localized motion fixture: background seed74601,12 textured body parts seeds628..639.
Two shapes have static heads/torsos; four limbs per shape move independently±6px.
Pixelwise native mask gives true whole-person mean .3466683 in reference FOV units.
Coarse torso-centered production gave MEASURED subject=0.0. This was not an unknown
sample or zero net-direction effect: actual moving limb pixels were mostly missed.
Initial smaller-patch center weighting also failed the predeclared .08 accuracy
tolerance and changed with lattice phase; that tolerance was not relaxed.

## Body measurement separate from camera

Camera still uses the large reference-radius2 patch, 12×18 distinct centers,
independent clean-query background and original consensus/coverage constraints.
Only AFTER supported camera is available, body matching uses reference-radius.5
patches, native quarter-pixel search with the SAME full-FOV displacement units.
Native lattice Xstep=ceil(.5*width/48), Ystep=ceil(.5*height/72)+1; no duplicate
clamping. At144×81 the local query is5×3 pixels and lattice spacing2×2. Current
mask minimum throughout each small query must be≥.5. Large independently reliable
context checks non-camera deviations; incompatible confident scales make a match
unknown, never a choice of the more active displacement. Half-native-quarter
quantization is used only to decide whether context checking is needed, NOT to
zero/boost reported motion. Wider context never contributes camera/body evidence.

Weights refer to actual UNIQUE native person pixels used by validated local
patches, not only their centers. Each covered pixel belongs to its closest supported
query center in reference coordinates; overlap never counts a pixel twice. Patch
confidence is the actual correspondence fit/uniqueness/roundtrip confidence, not a
global confidence copied into new matches. No extrapolation outside supported query
footprints. Unowned full-frame mask mass remains unknown in the denominator.
Subject scalar = confidence-and-mask-mass-weighted relative displacement magnitude.
Coverage = owned validated query-pixel mask mass / full-frame mask mass. This is a
better explicit area interpretation, NOT old coarse point coverage relabelled.

Minimum4 subject witnesses now counts a deterministic NON-OVERLAPPING patch subset,
not the number of overlapping dense centers. Minimum25% mask-mass coverage, all
confidence/semantic/camera requirements and FEAR .18 plus sustained-run constraints
remain unchanged. `sampleArea` defaults1 in the generic aggregator so its unit-cell
controls stay intact; production patch cells carry their unique owned area. Unknown
remainder mass has matchingConfidence0 and cannot become motion evidence.

Limitations: masks only on the current frame, no identity tracking, no individual
limb segmentation, no rotation/zoom/parallax camera, no flow through occlusions,
no zero velocity claim (real sampling interval still retained as displacement).
Ownership is supported patch evidence, not independently validated per-pixel flow.
Multi-scale conflicts can correctly lose coverage; image details already discarded
by provider resize cannot be recovered. Matching is still expensive and exhaustive.
Legacy luma/blur-flow and other directors/rankings are unchanged and not fixed here.

## Verification

Localized limb phase0/1/2: truth.3466683, measured .33661202/.33661205/.33661202,
camera0; the original .08 tolerance holds at all phases. Camera-only pan stays
subject0. Pure whole-body±3px still intensity1/3, coverage.9209524 (previous coarse
center coverage.34375). Partial occlusion measures only actually supported right
body at fraction.4604762; correct surviving motion1/3, not inferred missing-body
motion. Better actual supported area may now meet25%; no old unknown is declared
accepted without running the new model. Flat background plus moving edges still
cannot certify camera motion. Analytic subpixel compensation remains exact within
previous .0001 checks; conflicts are excluded rather than tolerance increased.

Extra controls: ownership conserves native area and full mask mass; a tiny8×5
feature has≥4 reliable overlapping matches but<4 disjoint witnesses and remains
PERSON_COVERAGE/unknown. Independent foreground noise with genuine supported static
background is unknown, proving the local matcher is exercised (not an early camera
rejection). Old lighting/noise/FOV/aliasing/PTS/semantic/cache tests remain intact.

408 Android tests, failures/errors0;65 Python quality tests passed. APK SHA
`d18fb1603c3a851bba9b6e5ec2bbd96a87b5259dd9ee4e9205b69888c0abc305`.
Cache v14, method `person-local-affine-background-query-fov-v8`, probe schema3;
v13 affine-only was a unit-test intermediate, not a device/release baseline.
Binary cache shape unchanged; all old semantic versions rejected. Probe retains
coarse person counters AND adds separate local-person support/grid/disjoint counts.
Coarse `person_cells` must not be confused with new accepted subject witnesses.

## Preregistered real calibration

Before installation create a fresh immutable source/resources/tests/APK/docs/tools
archive. Output `calibration_v12_fear_local_affine_20260926.mp4[.result]`.
Same DISCLOSED coconut source SHA
`ac54e7a8a6ede438cd08e0ff0b6c5cce6fc556af62ecace9747b142bbb5190cb`;
unchanged original FEAR music SHA
`2fc09a0cb163ad9eb6cdda3095fcf103ab4aab7b9dc52ec3a3e5564b085bce37`.
Verify runtime APK/API36/model Android SDK built for x86_64/input identity, no other
golden worker and no existing output. Start ONCE, observe same handle until terminal;
timeout is not grounds for restart. Read cached measured/unknown/runs; exact-PTS
partition replay planned as `calibration_v12_motion_local_probe_v14_20260926.json`.
New estimator has two independently justified changes: real differences cannot be
attributed solely to affine fitting or solely to local body matching.

A positive refusal is NOT a negative pass. More measurements or an MP4 is NOT
physical-motion accuracy or visual acceptance. If exported, all actual decoded
video/audio quality gates and a new hash-bound human review remain mandatory.
Do not transfer WhiteKing's old review or user DUALITY reviews; originals untouched.
No new release parent or corpus readiness is gained. Four-style story/transition/
music/selection grammars and independent machine+human release series remain the
full scope, including Sigma mask/rhythm, Heartbeat face and DUALITY human acceptance.

## Real result (not acceptance)

Immutable baseline `artifacts/quality/baseline-v12-local-affine-20260926.tar`, SHA
`4de753ae723a85116594cdb607e0dc1298a1ed89610c5f99e8ecd02d1b110df0`.
Installed base.apk and all input/runtime identities matched plan; no splits.
Started once15:23:28 UTC; analysis terminal15:26:23, PID2801/TID2822 absent
after report. Export absent, `material_rejected/insufficient_motion_evidence`.
Positive challenge remains failed. No old review, release matrix or original changed.

175026ms analysis,121 observations/semantic frames/masks,277 model successes.
New v14 cache SHA `4c9972d2003332da639e15232ce47f3d56a7cd4eb730be266fbc40c99e4466ad`.
Read-only diagnostic `calibration_v12_fear_motion_v14_diagnostic_20260926.json`:
0 measured/121 unknown, moving/run/span0. Previous v12 had1 measured; this is
NOT improved real accepted coverage and is not an improvement in product performance
(previous observed122451ms). Raw legacy peaks remain unchanged and are NOT fallback
evidence. Neither independent synthetic counterexample fix proved the real challenge
resolved; its failure must not be relabelled as a successful negative case.

Result SHA `4cb63a91c406b23003ecf2cdb7c352d68b319078be53f6f6c26bfbf7063984ca`,
diagnostic SHA `d8c6fcef90408c2c490041427fe16070b92d36f8cc8452d639c318fadf00cc51`.

## Exact-PTS local replay

`calibration_v12_motion_local_probe_v14_20260926.json[.result]` terminal by15:31:36
UTC, PID2801/TID2942 absent. Schema3, runtime/source/cache identities match. JSON SHA
`b7fe2108fcd3062a2dc0dda4fcbdb81faca4a84c12fbe8640c34202ad5dcd393`.
121/121 cached nullable measurement agreements, but all measurements are null: this
agreement alone is trivial and NOT verification of intermediate numerical matching,
new semantic inference, physical motion or acceptance. Detailed replay counters
show that background and local branches actually ran on the exact decoded PTS.

Stages: FIRST_FRAME1, MASK_UNAVAILABLE42, HUMAN_UNAVAILABLE9,
CAMERA_UNSUPPORTED54, SUBJECT_UNSUPPORTED2, PERSON_COVERAGE13, MEASURED0.
Camera reaches local body on15 observations. Separate local min/median/max counts:
candidates112/447/1021, pure-query textured17/225/558, fitted12/61/200,
unique-match0/88/291, roundtrip18/161/436, reliable0/15/89.
Candidate count includes all person-center lattice points; texture/fit/etc refer
to pure, valid local queries after context conflict rejection. They are not counts
of independent witnesses or independently validated per-pixel flow.

Largest area support PTS4633000:89 reliable overlapping local matches,36 disjoint
witnesses, but covered person mask mass .21173225 < .25. Other covered fractions
.09464649,.02311120,.06646182,.02421890,.07217766,.06322541,.10891653,
.12675905,.08270091,.06422730,.06838293,.02660452. These are coverage estimates,
not motion correctness probabilities. More witnesses alone cannot certify enough
body area or justify weakening .25. PTS17900000 (the only measured v12 event) now
has125 person candidates,21 textured/fitted,0 unique matches,0 reliable/witnesses;
the smaller queries cannot establish a unique displacement there.

Thus real refusal is localized to independent camera support and insufficient
unique supported body area, not simply the number of grid points. This does not
isolate one root cause. Next independent controls should add locally smooth/repeated
limb textures and blur/appearance deformation, testing whether adaptive larger
context can improve uniqueness/area without inventing motion on lighting, noise or
camera-only scenes. Previous-frame semantics/identity and other motion consumers
still need work. Real corpus breadth, all four product grammars and human release
acceptance remain unclosed; synthetic accuracy is not their substitute.
