# Full-frame geometry / cache v11 — 2026-09-26

Calibration change, not FEAR acceptance or release evidence. The preceding v10 goal
turn made progress: pixel-center projection was implemented and exercised on device;
the measured positive challenge still did not pass. No live render is being resumed.
Current device thread inventory was checked before this new experiment.

## Geometry and units

The correspondence-only plane no longer squeezes every frame into 48×72.
Full-frame area integration preserves aspect, long side at most 144 (no upsampling),
with nearest integer size rounding. Examples: 480×270 → 144×81, 720×1280 → 81×144.
If reduction would make a dimension ≤4, keep the bounded provider plane; matching
fails closed on insufficient support. No padding, crop, fabricated person or camera.
The provider itself has already reduced decoded frames; lost details are not restored.

Matching uses explicit native-pixels/reference-pixel scales width/48 and height/72.
Search limits are three reference pixels per axis, rounded DOWN to the native
quarter-pixel candidate grid. Patch radii scale from two reference pixels (rounded
up); uniqueness and roundtrip tolerances also use reference coordinates. Search is
still exhaustive, duplicate grid centers still removed. Grid 12×18 is unchanged.
Photometric texture/fit/uniqueness thresholds, camera consensus/coverage, human and
mask confidence, person ≥4 cells / ≥25% supported mass, FEAR opener 0.18 and its
duration requirements are unchanged. These confidences are not calibrated probabilities.

Production units: dx/(3*width/48), dy/(3*height/72). Equal FOV displacement therefore
does not acquire a resolution multiplier. V10 was already 48×72, so its production
unit scale is preserved. Pure raw-pixel matcher tests can explicitly use unit sampling;
PersonMotionEvidence always uses full-frame FOV sampling. Real interval is retained,
NOT normalized to speed. Camera-compensated subject magnitude remains direction-free.
This is not dense optical flow, multi-person identity tracking, rotation/zoom/parallax
estimation, or proof of real segmentation accuracy.

Method `person-background-fov-area-subpixel-v5`, cache v11. Binary cache shape is
unchanged, old caches rejected; no previous disclosed cache is relabelled as new.
Legacy luma moments and blur-flow still use their previous plane and are NOT fixed
by this change. The rest of their ranking/selection semantics remains pending.

## Independent synthetic checks

Known continuous FOV field is sampled at 48×72, 96×144, 144×81, 81×144. Ground
truth dx=.75, dy=-.5 reference pixels. Initial fixed-native-patch implementation
failed the ≥90% accurate-correspondence criterion (139/160 on landscape); that
criterion was not relaxed. Scaling patch footprint corrected this counterexample.
Final reliable/accurate pairs: 107/107, 160/160, 160/160, 160/160.
Mean X: .25, .25, .25, .24691358; mean Y: -.16666667, -.16666667,
-.14814815, -.16666667 (quarter-native-grid quantization). Error tolerance .04 in
normalized coordinates, ≥40 spatial witnesses, ≥90% accuracy of reliable matches.
These controls are not human-quality or small-real-person evidence.

Lighting-only static field stays measured zero; unrelated noise gives no confident
correspondence at each size. Actual mask→independent background consensus→subject
compensation holds. A smaller dx=.25 reference displacement stays below .18 on BOTH
camera and relative subject and does not pass the actual FEAR opener evaluator at
any of the four sizes. Thin frame remains unknown; v10 cache rejection is tested.

Full suite: 388 Android tests, failures/errors 0; 65 Python quality tests passed.
APK built, SHA `2d4af9f816dbd6989879708ee8b72d8a307504af99308097b6bf1ff58946c8e4`.

## Preregistered real calibration

Before install archive source/resources/tests/APK/docs/tools into a fresh immutable
baseline. New output `calibration_v10_fear_fov_20260926.mp4[.result]`.
Same DISCLOSED coconut source SHA
`ac54e7a8a6ede438cd08e0ff0b6c5cce6fc556af62ecace9747b142bbb5190cb`,
unchanged original FEAR music SHA
`2fc09a0cb163ad9eb6cdda3095fcf103ab4aab7b9dc52ec3a3e5564b085bce37`.
Device emulator API36 / Android SDK built for x86_64. Verify installed APK hash,
input identities, fresh output absence and no other golden worker. Start ONCE;
observation timeout is not grounds for restart. Read v11 cached measured/unknown
counts and runs after terminal result; stage replay may use exact newly decoded
pixels plus cached current semantics. More measured samples alone do not prove
physical accuracy or successful montage. Positive refusal is NOT a negative pass.
If exported, all encoded gates, independently decoded AAC and NEW hash-bound human
review remain required. No new independent parent is gained from this calibration.

Other motion consumers, Sigma mask/rhythm, Heartbeat face safety, human acceptance of
new user DUALITY and independent multi-product release cohort are still open.

## Device result

Immutable baseline `artifacts/quality/baseline-v10-fov-20260926.tar`, SHA
`e43701e2a03b47a8fe796d93b8f0eff242e237941c54172920937b73d6c11701`.
Installed base.apk hash matched; no split APK. Source/music/API/model matched plan.
Started once at 14:41:54 UTC, terminal result by 14:43:45, PID2357/TID2382 absent
after completion. No MP4, typed `insufficient_motion_evidence`. No result is added
to the original release matrix; this remains a disclosed positive challenge failure.

Analysis 110321 ms, 121 observations/semantic frames/masks, 277 successful model
invocations. Previous v10 analysis was 38304 ms; changed circumstances prevent
a controlled hardware benchmark, but the observed ~2.88x slowdown must not be hidden.
Full exhaustive denser-patch matching has a substantial cost; no speed improvement
or usable product-performance claim is made.

V11 cache SHA `a04748369e97a4b33c8a52e58fad492f77a8c5196641f2b79ab565967dcc63bb`.
Read-only diagnostic `calibration_v10_fear_motion_v11_diagnostic_20260926.json`:
1 measured / 120 unknown, moving/run/span 0. Measured PTS17900000, gap267000us,
subjectIntensity=.0807270855, cameraIntensity=.0129049113, cameraConfidence=.62545675,
4 person cells / supported fraction .27346182, 31 camera cells / 4 quadrants.
The measured PTS changed (v10 only had PTS25900000), so these are not successive
measurements of an identical event. Both intensities remain below .18. Legacy
camera/subject peaks unchanged; they are not replacement evidence.

Result SHA `dfb3ac73830d812a40cb40e3377c435d6332f62396bd3c70552a7639b29d2e75`,
diagnostic SHA `450cf779bc9bc2d3ab70482358504101c2b6cac19160243094cfce9a6a1b1eb0`.
Resolution-independent units and synthetic geometry counterexample are corrected;
real subject coverage and end-to-end visual quality are NOT proven improved.

## Exact-PTS stage replay

`calibration_v10_motion_stage_probe_v11_20260926.json[.result]` completed by
14:46:32 UTC; PID2357/TID2505 no longer present. JSON SHA
`cc4b4ea702f7380512d0d9e19d9173ff1959a21c03c70122b32a5f5519fe0af9`.
Source/APK/API/model/cache identity matches, 121/121 cache measurement agreements.
All 121 actual analysis planes are 144×81. This verifies execution/replay consistency,
not independent semantic or physical-motion accuracy.

Stages: FIRST_FRAME1, MASK_UNAVAILABLE42, HUMAN_UNAVAILABLE9,
CAMERA_UNSUPPORTED51, SUBJECT_UNSUPPORTED7, PERSON_COVERAGE10, MEASURED1.
For 69 observations reaching pixel matching, min/median/max totals:
unique centers160/160/160, textured132/153/159, fitted1/13/57,
unique-match44/102/145, roundtrip87/132/158, reliable0/6/45,
background0/5/34, background quadrants0/2/4, person0/0/8.
Global texture/fit counters are NOT background-only counters and do not by themselves
prove a texture or photometry defect on the background.

Of 51 camera failures, 47 have <8 reliable background cells, 4 have ≥8 cells but
<3 quadrants, and 0 have enough cells+quadrants while still failing consensus.
Thus a larger search alone or weakening the consensus ratio is not justified by
these observations. Subject support remains insufficient as well. Merely increasing
resolution has not repaired the disclosed real challenge.

Next evidence required: separate independent known-motion full-body/proxy fixtures
(localized limbs/deformation, camera-only/lighting/noise/occlusion controls), followed
by background-versus-person matching diagnostics and a justified model change, not
relaxation to get this video accepted. Exhaustive matching cost also needs an
equivalence-checked optimization or another validated estimator. Other three products
and an actually untouched release cohort remain in the original scope; this calibration
is not a replacement for any required release case or human review.
