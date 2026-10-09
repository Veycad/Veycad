# Heartbeat: absent transition defocus

Baseline `heartbeat-first-g0-0907.mp4` has hard cuts instead of the reference's
defocused arrivals. `heartbeat/blur-window/sheet-0.jpg` samples two windows:
3.733 s is strongly blurred, detail returns around 4.000 s; next arrival at
4.233 s loses focus again and becomes readable by about 4.450–4.500 s.
These are frame observations, not recovered original effect parameters.

The existing `LENS_BLUR` GLSL is RGB radial separation, not spatial blur.
Increasing it cannot reproduce defocus. Added a separate `DEFOCUS_BLUR` sample
and 25-tap weighted diamond-shaped kernel in the existing source pass, BEFORE
layers so authored flashes/black tail are not overwritten. Radius accounts for
output aspect. Only Heartbeat adds these nodes; zero amount follows the prior
branch. Sigma rules and lens-warp semantics remain unchanged.

Experimental envelope: strong arrival, quadratic falloff over 250 ms, from
scene 4 onward. Strength .85 and 4.5%-width radius are tuning hypotheses.
This does not yet reproduce the reference's stretched movement, polygonal bokeh,
velocity curve or every individual transition duration. Unit tests cover onset,
decay, inactive tail and empty-graph isolation. Unit/lint/build pass.

`heartbeat-defocus-g0-0907` started on the emulator. Device shader compilation
and decoded visual benefit remain unverified until that render completes.
The same APK includes the separately tested millisecond pulse-clock correction.

## Failed device compile and retry

First defocus run failed: GLSL ES rejected integer `abs(x)` in the kernel.
Changed to `abs(float(x))` with float weight arithmetic. Tests/lint/assembly
passed. QA now receives actual export FPS (60 for Heartbeat, unchanged default
for Sigma), samples pulse and defocus checkpoints, and reports `defocus`
separately from the unrelated radial lens-warp value.

APK installation then failed with insufficient emulator storage. A sequential
shell command mistakenly started the old APK despite install failure; that
run (`heartbeat-defocus-fixed-g0-0907`) was stopped and is NOT valid evidence.
Archived 12 old aligned/baseline-clock MP4s in
`artifacts/reference-analysis/heartbeat/pre-heartbeat-emulator-archive.tar`.
Compared SHA-256 of every archive member with its emulator original before
removing those exact 12 private test copies. Source drafts/My Edits untouched.
All files remain recoverable from the local archive; export copies also remain.

Installation succeeded after cleanup. Fresh run `heartbeat-defocus-retry-g0-0907`
uses the corrected APK; launch was conditional on install success. Device-render
success and visual benefit remain pending.

## Completed grid-kernel render

`heartbeat-defocus-retry-g0-0907` completed: 1271 frames, audio present,
13,288 us A/V drift. Local MP4 and final inspector/report preserved. The decoded
3.733/4.233 s checkpoints now show real blur, resolved by 4.000/4.500 s.
However the collar exhibits repeated arcs from the sparse regular 25-tap grid;
the experiment is NOT visually accepted. The reference has smoother defocus.
No quality percentage claimed; full temporal review and three-source regression
remain outstanding. Face QA changed sampling density, so its increased loss
rate must not be compared directly to the earlier sparse report.

Replaced the regular grid with 64 fixed golden-angle disk samples and radial
weights to avoid periodic replicas; unchanged radius/time envelope. This is a
new unverified kernel experiment, not a claimed fix. Unit/lint/build pass.
Next run: `heartbeat-diskblur-g0-0907`. The measurement script now also emits
spatial gradient, a descriptive statistic, not an acceptance score. Compare it
only after confirming identical decoded source frames in the two inspectors.

## Disk-kernel verification

`heartbeat-diskblur-g0-0907` completed. MP4, report and inspector are local under
`artifacts/reference-analysis/heartbeat/`; decoded pair sheets under `diskblur-g0/`.
`compare_render_clocks.py` found all 1271 primary AND secondary texture PTS
identical to the grid-kernel render, zero changed/missing pairs.
At 3.733–3.800 s and 4.233–4.300 s the regularly repeated collar arcs seen with
the grid kernel are no longer visible in inspected sheets. Arrival still blurs,
then resolves at 4.000/4.500 s. Retain disk kernel for wider regression.
This is a checkpoint improvement, not full-resolution temporal acceptance of
every transition; the reference's stretch/zoom and stronger echo remain absent.
QA still rejects the same generic beat/repeat/artifact/colour gates. A/V drift
remains 13,288 us. No pass thresholds changed to accept the result.

Added a local paired-clock checker with three passing tests: missing measurements
are not matches, secondary decoder differences invalidate a pair, duplicate
output PTS are rejected. Next actual device regression: Golden 1 with same APK.
