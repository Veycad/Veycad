# Heartbeat: temporal versus spatial echo (2026-09-07)

Local paired experiment, not visual acceptance of the style.

Fixed an offset contract: zero previously selected the legacy -420 ms fallback.
Heartbeat now honours explicit zero; Sigma/legacy layers retain their fallback.
The normal Heartbeat recipe remains -67 ms pending motion review. Debug extra
`heartbeat_compare_timing` renders a synchronous control from the same analysed
graph, changing only the echo offset. Tests cover that scope and legacy behaviour.

Evidence under `artifacts/reference-analysis/heartbeat/`:
- `heartbeat-timepair-g0-0907.mp4`: existing -67 ms recipe.
- `heartbeat-timepair-g0-0907-synchronous-control.mp4`: zero offset.
- Both corresponding `-inspector.json` files: all 1271 primary/output PTS match.
- All 467 recorded secondary decoder frames in the control match primary PTS.
- Both MP4 containers have 720x1280 AVC, AAC, rotation 0, 1271 video samples,
  912 audio samples; last-PTS difference 13288 us; no container issues.
- Main render: 26/26 pulse checkpoints matched, general visual QA still rejected.
- `timepair-sync` and `timepair-delayed`: matching decoded stills at 12.7, 14,
  15.55, 15.6, 15.65, 17.4 seconds.

Still-frame observation: zero lag reduces displaced mouth/face contours at
15.55–15.6 seconds, but spatial zoom copies continue to double facial details.
It does not solve the whole visual problem. No normal-speed audiovisual review
was performed in this iteration; do not promote this as an accepted improvement
or as reference fidelity. Next: review motion and tune spatial echo distribution
against the reference before selecting the default.

Validation: testDebugUnitTest, lintDebug, assembleDebug succeeded; git diff
--check clean; APK entry scan found no MP4/Golden/reference-video fixtures.

Emulator storage: five completed external test MP4 copies (aligned-clock g0/g1,
aligned-qa g2, baseline-clock g0/g1, all 0907) were copied to local
`*-external-backup.mp4` files and SHA-256 matched before deletion. Sources and
user edits were not removed.

## Rejected facial-core attenuation experiment

`heartbeat-facecore-g0-0907-synchronous-control.mp4` and its inspector were
retained locally. A soft ellipse in semantic UV attenuated echo by up to 85%
times face confidence. The paired synchronous baseline and this render have
1271/1271 identical actual primary/secondary PTS. At 15.6 s decoded facial
ghosting remained visibly similar; file size changed only 1158 bytes (not a
quality metric). This does NOT prove the face region overlaps the visible face.
The experimental shader and its structural test assertions were removed rather
than adopting an unproven tuning change. Next diagnostic: draw the actual face
region on the rendered image and inspect its coordinates/confidence at the
problematic source PTS. Existing semantic UV versus decoder/display rotation
is a hypothesis to test, not an established root cause.

Both experimental renders completed 1271 frames, main pulse audit 26/26;
unit tests/lint/build passed before the run. Review remained decoded stills,
not normal-speed audiovisual acceptance.

## Coordinate diagnostic

Debug-only intent `heartbeat_face_probe` isolates source 14660911 us (actual
decoder PTS at output 15.6 s). It retains an input PNG locally and renders the
face ellipse in green via the same semantic UV used by the rejected experiment.
`heartbeat-face-region-probe3-0907.mp4` completed; decoded `face-region-probe/frame-0.png`
shows the ellipse over the face, not the background. Face region from this
full-resolution inference: x=.42777777, y=.41979167, width=.81574076,
height=.45833334, confidence=.9. This establishes the coordinate path for this
one supplied region, NOT availability/correctness of the sparse face regions
in a full montage analysis. First cold diagnostic found no face; a second
detected one but hit a diagnostic-only missing-mask assertion (fixed by passing
the real semantic mask/depth). Do not conflate these failures with montage bugs.

Added local logging of face inference exceptions, previously silently swallowed.
Added nullable face-region evidence to the per-frame render inspector, with a
unit test proving absence remains null and supplied coordinates are retained.
Next full render should inspect those values at 15.6 s against the known-good
region before changing shader strength again. No new visual-quality claim.

Storage cleanup this iteration: SHA-verified local copies retained before
removing timepair/facecore main private+external duplicates, synchronous control
external copies, pivotpair main private+external copies and zoomtrail main
private copy. Unrolled private render was NOT deleted (local copy not found).

## Full-path face evidence and second rejected correction

`heartbeat-face-evidence-g0-0907-inspector.json`: face present in 442/467
DOUBLE_EXPOSURE frames. At output 15600000 us the actual supplied region is
x=.44521, y=.40601, width=.75277, height=.42541, confidence=.9, close to the
successful full-resolution isolated probe. Missing face data is therefore NOT
the explanation for this particular checkpoint. No LocalSemanticFace exception
was observed in the current process during this run.

Second experiment restored the pre-layer image inside the same soft ellipse
after applyLayer instead of attenuating inside applyLayer. Full render
`heartbeat-readability-g0-0907.mp4` completed 1271 frames, 26/26 pulse checkpoints,
but decoded 15.6 s still retained visible ghosting. No quality gain is accepted;
the experimental shader restoration was removed. Keep both inspector files and
`readability/frame-2.png` as negative evidence. Next check must inspect actual GL
uniform state and diagnostic output during the full path, rather than retuning
an assumed active ellipse. No normal-speed audiovisual acceptance performed.

Six old face-interior g0/g1/g2 original/refined test copies were archived in
`face-interior-private-backup.tar`; every member SHA-256 matched its emulator
source before the six private test copies were removed. User sources untouched.

## GPU uniform readback: parameter transport ruled out at checkpoint

Added bounded glGetUniformfv diagnostic at echo output 0.5/15.6 s (no image data
in logs). Short `heartbeat-uniform-probe-0907.mp4` completed using a known static
diagnostic source frame and synchronous echo. Full
`heartbeat-uniform-full-g0-0907` completed 1271 frames and 26/26 pulse checkpoints.
Readback at 15600000 us: face=(.44520888,.40601334,.75277036,.42541),
confidence=.9, heartbeatEcho=1, opacity=.40249223, kind=3, GL error=0.
Evidence: `heartbeat-uniform-readback.log` and full result marker in local
artifacts. This rules out missing/incorrect uniform transport for this frame;
it does not prove shader execution or visual quality in all frames.

Pixel comparison of the same-source delayed baseline (timepair-delayed/frame-3)
and rejected restoration (readability/frame-2) found RGB absolute difference
means .0199507 inside rectangle x=101..519,y=281..899, .0105653 outside (0..1
channel range). Thus the correction did change pixels; calling it inactive would
be unsupported. These are change magnitudes, NOT improvement/similarity scores.
Next iteration should evaluate combined zero temporal lag and spatial echo
readability, including motion, rather than keep diagnosing face availability.
Tests, lint, assembleDebug passed; no style quality acceptance claimed.

## Combined candidate

`HeartbeatDirector.build` now accepts explicit echoOffsetMs (default still -67
for backwards-compatible experiments). Debug intent heartbeat_echo_offset_ms=0
selects synchronous frames through the automatic editor; a unit test proves it
equals the prior same-graph synchronous control. Shader restores the pre-layer
image within the soft facial ellipse at strength .95, leaving pre-layer grade
and defocus intact. Sigma is outside this branch.

`heartbeat-combined-g0-0907.mp4`: 21.1833 s, AVC/AAC 720x1280, rotation 0,
1271 frames, 467/467 secondary frames synchronous, 26/26 pulse checkpoints,
last audio/video PTS delta 13288 us. All 1271 source/secondary/output PTS match
the previous timepair synchronous control. Container accepted, general visual
QA still rejected. APK fixture scan found no MP4/Golden/reference files.

Decoded still review (`combined` versus `timepair-sync`): eyes/mouth contours
are modestly more readable; peripheral hair/collar echoes remain. Residual
ghosting at 15.6 s remains conspicuous. Retain as an experimental combined
candidate, not reference-level acceptance or a validated final default. Normal
speed audiovisual review has not been performed. Build/tests/lint passed.
