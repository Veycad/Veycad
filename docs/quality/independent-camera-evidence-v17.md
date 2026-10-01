# Independent camera evidence / cache v17

2026-09-27. Correctness change following read-only examination of the disclosed
Coconut v15 positive challenge. This is not a successful render, a physical
ground-truth result, a new holdout, or acceptance of any product.

## Actual motivation

The existing v15 exact-PTS probe has 121 observations: FIRST_FRAME1,
MASK_UNAVAILABLE42, HUMAN_UNAVAILABLE9, CAMERA_UNSUPPORTED54,
PERSON_COVERAGE13, MEASURED2. Of the 54 camera failures, 47 have fewer than eight
reliable clean-background cells; the remaining seven have at least eight but
only two quadrants. Camera/body confidence failures cannot be repaired by
lowering FEAR's movement threshold. The two measured subject scalars .1252/.1660
are below the unchanged .18 threshold and do not supply a sustained opener.

Separately, thirteen rows have independently supported camera translation but
their complete motion measurement is discarded because body coverage fails.
The FEAR opener explicitly permits camera OR subject movement. Coupling those
two validity branches loses already supported camera evidence. Neither the old
probe nor its results records camera values on these thirteen rows; therefore
there is no evidence that this change will produce a Coconut opener or export.

## Measurement contract

`VisualEventMap.MotionMeasurement` is unchanged: at least four disjoint body
witnesses and .25 actual mask-mass coverage, supported clean-background camera,
fresh current mask/human evidence and a real 100–500ms interval are still
required. Unknown body motion remains null; no zero-valued subject is fabricated.

`CameraMeasurement` is separately nullable. It is produced only after the SAME
first-frame, fresh-semantic, PTS interval, current mask confidence .8, human .55,
local correspondence and background-camera gates. At least eight agreeing clean
background cells, .7 confidence-mass consensus, three quadrants and confidence
.5 remain mandatory. Pixel-supported camera survives SUBJECT_UNSUPPORTED and
PERSON_COVERAGE without certifying body motion.

The new value stores both actual decoded PTS, semantic PTS (equal to current
PTS), exact interval (current minus previous), method, x/y, confidence, agreeing
cell count and quadrants. Observation requires its source PTS equal to current
measurement PTS. Units remain full-frame dx/(3*width/48), dy/(3*height/72), not
velocity or legacy luma-change moments. Matching, mask purity, camera consensus,
coverage, confidence and .18 movement thresholds are not relaxed.

## FEAR opener and diagnostic count semantics

An observation is moving when validated whole-body measurement supplies camera
or relative subject intensity >=.18, OR independent camera intensity is >=.18.
Three consecutive moving observations spanning at least 500000us are required;
unknown/static observations and gaps above 500000us reset the run. When a new
independent camera record exists, its previous actual PTS must equal the prior
moving observation PTS to extend the run. Filtered-out intermediate frames cannot
silently bridge a supposedly consecutive camera run.

- `measuredSamples`: union of observations with valid whole-body OR independent
  camera evidence. A single observation is counted once.
- `subjectMeasuredSamples`: observations with valid whole-body measurement.
- `cameraMeasuredSamples`: observations with either independent camera evidence
  or the supported camera contained in a whole-body measurement. This equals the
  union count under the present contract because valid body requires camera.
- `unknownSamples`: no valid branch; preserves the no-measurement meaning, not
  the number of observations whose subject remains unknown.
- `conclusiveSamples`: valid whole-body measurement OR independently measured
  camera intensity >=.18. A moving camera conclusively supplies the motion OR;
  a static camera alone does not settle the unknown subject branch.
- `inconclusiveSamples`: total observations minus conclusiveSamples, including
  static-camera/body-unknown observations. Total = measuredSamples+unknownSamples.

If no opener passes, the existing typed rejection distinction now uses
conclusiveSamples rather than the union measurement count: a set of valid static
cameras with unknown bodies remains `insufficient_motion_evidence`, not a claim
that the material is physically static. Other material/story/cascade gates are
unchanged. Successful exported reports must identify these new count semantics;
historical FEAR results are not relabelled with the new method.

## Cache, production path and replay

Production analysis computes one Assessment for the same immutable pair and
attaches both independently validated branches to the current observation.
Cache17 appends the optional camera record after the v16 face/gesture flags; all
earlier cache versions are rejected, not promoted or migrated into new evidence.
Frozen v16 APK/run/cache remains historical and does not cover these source edits.

Probe schema5 records raw supported camera even when body fails, plus validated
camera and exact actual/semantic PTS provenance. Separate camera/whole-body cache
equality counts and their combined equality are reported. Replay still uses
cached semantics and fresh decoded pixels; equality is determinism, not physical
truth, semantic accuracy, identity tracking or human acceptance.

New pure tests cover exact provenance, invalid support/PTS, stale semantics and
mask/human gates, actual pixel camera pan with insufficient disjoint body support,
independent foreground noise, camera-only positive/static cases, unknown/PTS-link
run resets, non-double-counting, typed inconclusive rejection, source attachment,
cache17 round-trip and cache16 rejection. Tests/build/device verification are
pending root integration; this document does not assert they passed.

Root integration subsequently completed full Android unit tests and assemble:
464 tests in68 suites, failures/errors/skipped0. Python QA88 tests passed,
including exact camera==union-measured consistency: body evidence without
supported camera is rejected, while a validated camera-only opener remains legal.
Universal APK SHA-256:
`51823195c067752766874f147af7ebc76a6f5e8826d51ff183162d9ed55fb07d`.
These results certify the regression controls/build, not a real successful opener.

Before device execution, reserved NEW artifacts on the already disclosed inputs:

- `calibration_v17_fear_camera_20260927.mp4[.result]` (same Coconut + FEAR music);
- `calibration_v17_motion_camera_probe_20260927.json[.result]` after complete cache17;
- `calibration_v17_heartbeat_fresh_face_20260927.mp4[.result]` (same OpenSpeaks + music).

Source/music SHA are fixed in `current-frame-semantics-v16.md`, unchanged.
Archive `artifacts/quality/baseline-v17-independent-camera-face-20260927.tar`
must be created before installation. No existing result/review is overwritten;
both output and marker absence and no existing worker are checked each launch.
These are calibration, not newly independent holdout cases or human acceptance.

## Frozen runtime result, 2026-09-27

The archive was created before installation, SHA-256
`c17bbfdcaaed715bfcee5dd944ec180dd643b2c470c34ef980a1d8250ef8dd14`.
The single installed base APK was independently hashed as `51823195...55fb07d`,
matching the full APK identity above; API36/x86_64 emulator and Coconut/music
bytes were checked against their declared SHA identities.

The predeclared FEAR run terminated at 08:11 UTC with
`status=material_rejected`, `rejection_code=insufficient_motion_evidence`.
No MP4 was produced. Local terminal result:
`artifacts/quality/runs/calibration_v17_fear_camera_20260927.mp4.result`, SHA-256
`0234a68f533b102a9588da6cfc22c88c160a45e54e1f5770d5cc0e6c57c1b7e2`.
This remains a failed disclosed positive challenge, not a successful negative case.

An exclusive cache-only diagnostic was subsequently saved as
`artifacts/quality/runs/calibration_v17_cached_camera_20260927.json`.
Its compressed cache SHA is
`f4d8e3809e6f204f663900f2be969cf48ddd264b14417fadfc52dee5712f78f9`.
All121 observations have current semantics/masks; model successes277. There are
2 whole-body measurements and15 independent camera measurements, including13
camera-only rows. Camera intensity peak .1015883 and subject peak .16603845 are
both below the unchanged .18f threshold. No moving run exists;106 observations
have neither measurement branch, and119 remain inconclusive for the camera OR
subject condition. Retaining camera evidence works but does not solve the
observed positive failure. This diagnostic reads existing cache only; it does
not add fresh pixels, physical ground truth or acceptance.

After confirming FEAR terminal/no worker, both new Heartbeat output/marker absent,
and unchanged OpenSpeaks/music SHA, the predeclared fresh-face Heartbeat run was
started on the SAME frozen APK. Its worker was confirmed PID2295/TID2473.
Outcome is pending; no successful export, tail/face check or human review is
claimed from the launch.

Actual analysis profile:444804ms elapsed,10542ms semantics and398224ms motion.
This run overlapped a Gradle build, so comparison with v16 is not a controlled
speed measurement; no runtime gain or causal slowdown is claimed.

## Remaining physical limitations

Current masks do not establish previous-frame class/identity or ground-truth flow.
Rotation, zoom, parallax, deformation, occlusion, blur and camera/body accuracy on
the real footage remain unresolved. The Coconut contact sheet shows genuine
movement, smooth dark clothing, repeated stripes and a moving audience, but does
not assign a single causal matcher defect. Mask confidence <=.48 indicates
implausible foreground coverage. Subsequent full attachment audit of the SAME
completed cache resolves empty versus full: all42 low-confidence masks have
coverage below .035, none above .90;30 contain zero foreground pixels at the
existing .5 threshold, and34 still have human confidence >=.55. The saved report
`artifacts/quality/runs/calibration_v17_cached_mask_audit_20260927.json` has SHA
`da23b08bdaf97b4828b839baa08fc354ef2b9aa20fbfe0e9302890d61a52e0e2`.
These are refined cached probabilities, not original raw segmentation output.
They establish lost foreground, not whether model inference, STREAM history,
sampling, or refinement caused it. Full source analysis here uses ML Kit Selfie
segmentation; MediaPipe multiclass inference is reserved for target refinements.
Three retained measurements also follow a frame with mask confidence <.8; the
current-only mask contract does not establish the previous person class/identity.
No independent annotated mask/displacement,
cadence-invariance result or human product acceptance is added by this change.
