# Heartbeat reprise echo is weak

The completed Golden 0 disk-blur MP4 lacks the reference's clearly displaced
double images. Inspector recorded temporal layers, but pass presence alone
does not establish visual strength. Existing generic double-exposure GLSL
uses opposing drift, crosses near zero in the middle, and reduces opacity
near faces by up to 68%. Heartbeat's -67 ms secondary offset is intentionally
small, so this mixing can hide the second copy in low-motion footage.

Experiment: an explicit heartbeatEcho layer flag selects one displaced live
copy without the generic face suppression or zero-crossing drift. Offset
decreases from 5.5% to 1.925% horizontal over each reprise; 0.8% vertical.
These are tuning hypotheses, not recovered reference parameters. The original
Sigma branch is unchanged. Tests verify independent routing, default off for
legacy overlays and advancing secondary PTS. Unit/lint/build pass.

Current emulator `heartbeat-diskblur-g1-0907` predates this experiment. Do not
install the new APK over its running regression. Complete disk-blur regression
on Golden 2 with the old installed APK, then test the new echo against Golden 0.
No visible improvement of the new echo has been demonstrated yet.

## Emulator experiment heartbeat-echo-g0-0907

Completed: 1271 frames, 720x1280 at 60 fps, audio track present, last-PTS drift
13,288 us. All 26 measured pulse windows have matching decoded luma; this is
not a test of exact pulse length, absent extra flashes, or musical alignment.
Generic quality gate still rejects the edit.

Compared 12 timestamped stills at 11.75–13.8 s against `echo-before` and
`echo-reference`. The new branch visibly duplicates facial features at 12.0,
12.7 and 13.4 s, but does not reproduce the reference's repeated trailing
contours and polygonal blur. Increased visibility is not demonstrated quality
improvement; do not promote this experimental branch as a completed effect.
Only 704/1271 frames retain identical primary AND secondary decoded source PTS
against diskblur-g0 (567 changed). Thus this is not a controlled whole-video
A/B test. In particular 13.8 s now uses a different source moment.

Next: stabilize selected source windows for effect-only A/B runs and inspect
the reference trail temporally before replacing the single shifted copy.
No new optical-flow/depth model is justified by this result.

## Controlled paired render

Added debug-only `heartbeat_compare_echo` runner option. After the normal edit
and its exported evidence, it renders a legacy-echo control from the SAME
in-memory graph, changing only the echo kind. It does not rerun scene selection.
Control MP4, inspector and its own `.mp4.result` are exported to the golden
directory. The primary result marker alone does not mean the pair has finished.
Control marker explicitly states that visual quality acceptance is not evaluated.
Unit test checks unchanged source/output clocks, effects and all overlay fields
except routing. Unit/lint/build passed. Installed and started
`heartbeat-paired-g0-0907`; device result pending. Compare actual decoded PTS
before treating the resulting pair as controlled evidence.

Installation initially failed for storage; launch was correctly skipped.
Removed exactly the private and exported copies of diskblur-g0/g1/g2 and echo-g0
MP4s after matching each SHA-256 to the retained local artifact (eight duplicates,
about 106 MB total). All four videos remain recoverable under
`artifacts/reference-analysis/heartbeat/`; no source fixture was removed.

## Next shader hypothesis (not installed over the paired run)

Reviewed twelve consecutive reference frames, 12.450–12.633 s, saved in
`trail-sequence/sheet-0.jpg`. Multiple diminishing contours remain visible
around moving hands/head. This is frame-sequence evidence, not normal-speed
playback. Prepared three nearby spatial taps of the live secondary texture,
weights .55/.30/.15, step (.012,.004) decaying to half across the clip. It
replaces the single large offset only in the experimental Heartbeat branch.
Weights/offsets are hypotheses, not measurements. This is spatial multi-copy
sampling of ONE temporal source, not three historical frames or optical flow.
Unit/lint/build passed; shader execution and visual improvement remain unverified.
The currently running `heartbeat-paired-g0-0907` still contains the previous
single-offset branch. Do not conflate its output with this new shader.

Paired run completed: main and legacy control both 1271 frames. Both MP4s and
inspectors saved locally. `compare_render_clocks.py` reports 1271 identical
primary+secondary decoded PTS, zero changed and zero missing evidence. This
confirms the paired runner isolates effects from source selection in this run;
it does not establish visual preference or approve the single-offset effect.
Installed the three-tap shader build after both renders finished and launched
`heartbeat-trail-g0-0907` with `heartbeat_compare_echo=true`. Both its main and
control markers must be checked before the next installation. Visual result pending.

## Three-tap result: not accepted as a quality improvement

`heartbeat-trail-g0-0907` and its control both completed, 1271 frames each.
All 1271 primary AND secondary decoded PTS match within this pair, with zero
missing evidence. Local MP4s, inspectors and main result are retained.
Main: 720x1280/60 fps, 21.1833 s, audio present, 13,288 us last-PTS drift;
26/26 pulse windows match expected luma. Overall acceptance remains false.

Reviewed matched still checkpoints in `trail-after` and `trail-control` at
11.9,12.0,12.15,12.5,12.7,13.0,13.4,13.6,14.0,15.6,17.4,18.9 s.
Three taps soften/darken facial details at 12.7 and 13.4 rather than yielding
the reference's clearly separated trailing contours. At 14.0 facial duplication
is still conspicuous. This experiment is not an approved replacement. Keep it
experimental; do not present shader execution as visual progress. Neither this
sheet nor previous sheets certify normal-speed motion quality.

Next decision must investigate the trail's geometry/time evolution instead of
increasing opacity or adding more near-identical spatial samples again. No
render remains active after the two terminal markers of this pair.

## Radial geometry hypothesis

Full-resolution reference frames `geometry-detail/frame-0.png` (4.483333 s)
and `frame-1.png` (12.5 s) show corresponding scene occurrences. Reprise
contours diverge outward: hair upward, hand downward, shoulder sideways.
This supports scale-based rather than equal-translation geometry. Exact
source phase alignment and transform fitting are not established.
`inspect_reference.swift --frames` now preserves full decoded PNGs for this
inspection; they are local analysis fixtures, not APK assets.

Replaced the rejected equal offset in the experimental shader with three
centered zoom samples (step .075, decays to half over the clip), retaining
weights .55/.30/.15. Other Heartbeat timing and Sigma branch unchanged.
No obvious double-grading was found: the temporal frame copies the primary's
exposure/colour parameters and each sampled colour is graded once.
Unit/lint/build passed. Installed and launched `heartbeat-zoomtrail-g0-0907`
with paired legacy control after previous pair completed. Magnitude and
center remain hypotheses; execution and visual acceptance pending.

## Unexpected identical encoded picture payload

Zoomtrail pair completed (1271 frames, 26/26 pulses). Main MP4 and inspector
saved locally. Main has exactly 13,368,093 bytes, identical to three-tap
translation run; `cmp -l` first differences are near EOF at 13,357,723, not in
the large picture payload. Installed APK was pulled from its resolved pm path;
its SHA-256 equals the local build (2004114d77dedd269b00ebc403cd65bdf3da20e2e102533859d2344fe331c188),
and classes3.dex contains zoomStep. Stale installation is excluded for that run.
Changing sample geometry apparently did not change encoded pictures. Previous
visual effect conclusions must be treated cautiously until this is explained.

Next diagnostic removes the small dynamic sampling loop: same three scales and
weights, three explicit texture calls. Loop compilation/driver behaviour is a
hypothesis, not an established root cause. Unit/lint/build pass. New run name
`heartbeat-unrolled-g0-0907`, paired legacy control; outcome pending.
Install initially failed for storage, launch skipped. Removed only six SHA-256
verified emulator duplicates: paired/trail main private+exported files, and
their exported controls (~79 MB). All four MP4s remain in local artifacts.

## After actual sampler fix: face-pivot experiment

See heartbeat-black-secondary-texture.md: prior weak/dark echo was substantially
caused by a black second sampler. The full livesamplers render now has real
radial contours. Face duplication at 14.0/15.6 s remains too strong in reviewed
stills. Testing scale about the existing detected face center instead of image
center, with confidence-weighted fallback. Semantic top-down face UV converts
through Y flip and outgoing SurfaceTexture matrix before sampling. No new ML
model, no change to Sigma route. Magnitude and temporal offset unchanged.
Source-contract tests cover the intended conversion, not actual GPU pixels;
unit/lint/build pass. `heartbeat-facepivot-g0-0907` launched with paired legacy
control; visual outcome pending. Do not install over this active pair.

Facepivot pair completed. Main is 13,486,426 bytes, 1271 frames, audio present,
26/26 pulse windows match; last-PTS A/V delta 13,288 us. All artifacts saved.
Compared full-resolution 12.7 s still to livesamplers: eye contours are less
duplicated while collar/necklace trail remains visible. Both actual primary
PTS (7,963,533 us) and secondary PTS (7,896,900 us) match at that checkpoint.
This is a scoped still-frame improvement, not whole-video approval. Across
the runs only 704/1271 frames match source PTS; 567 changed. At 14.0 s the
source moment differs, so apparent face improvements there are not evidence
of the geometry change. General quality acceptance remains false. Normal-speed
review and stable multi-checkpoint comparison are still required. No active run.

Added `heartbeat_compare_pivot` debug comparison: identical graph and footage,
control only sets `uHeartbeatImagePivot` to select image center instead of face
center. Unlike the legacy-echo control this isolates the pivot change itself.
Unit/lint/build pass. `heartbeat-pivotpair-g0-0907` launched. Its control suffix
is `-imagepivot-control.mp4`; both terminal markers are required before install.
Storage: removed six SHA-256-verified emulator duplicates (livesamplers and
facepivot main private/export plus exported echo controls); all four MP4s remain
in local artifacts, no source fixtures removed.

Direct pivot pair completed: 1271/1271 actual primary+secondary PTS match,
zero changed/missing frames. Main and imagepivot-control MP4s and inspectors
retained locally. Reviewed matched checkpoints 12.0,12.7,14.0,15.6,17.4,18.9 s
in `pivotpair-face` / `pivotpair-image`. Face center reduces eye displacement
most clearly at 12.7 and 14.0; collar/necklace contours remain visible. 15.6
still has conspicuous motion ghosting, so the pivot alone is insufficient.
Retain as a scoped still-frame improvement pending normal-speed review;
do not claim temporal stability or reference-quality completion. Main remains
quality-gate false, 26/26 light impulses matched, audio present. Both renders
terminal. Next work should address temporal ghosting/rhythm, not repeat another
uncontrolled pivot comparison or increase shader complexity without evidence.

## Per-cut envelope reset found by synchronized three-video review

Samsung comparison at 1x showed the reference already has separated contours at
13.748 s, just after the 13.733 s scene boundary, while Veycad's echo was nearly absent.
Every reprise scene had its own triangular opacity envelope, so all eleven hard cuts reset
the layer to zero even though the scenes form one continuous 11.750–19.800 s phrase.
`heartbeat-echo-experimental` now retains its authored opacity across each decoder-routing
record; generic double exposure and Sigma keep their existing envelopes. A unit test checks
both sides of all eleven boundaries. Device render and visual acceptance are still pending.

## 2026-09-08 visual tuning outcome

The face-pivot radial trail was rendered with a larger scale step (`0.14`, decaying across
each reprise scene) as `heartbeat-echo-step14-0908`. Container checks remain healthy:
1,271 frames, 26/26 measured light pulses, AAC present, and 13,288 us final A/V PTS delta.
Three-player review at 1x and at 12.500/15.600 s found only a modestly stronger soft trail;
it remains far less articulate than the reference. Retain it only as a bounded improvement,
not as reference parity.

A subsequent contour-screen experiment made the effect unmistakable but produced concentric
rings around the face at 12.500 s and raised decoded face-loss to 0.2279. It was visually
rejected and reverted. The negative MP4 and evidence remain local under
`artifacts/device-regression/emulator/heartbeat-echo-contour-0908/`. Do not reintroduce
absolute-difference contour screening as a substitute for authored repeated silhouettes.

Two subsequent whole-silhouette screen composites were also reviewed at 1x and the same
checkpoints. They removed the contour rings but did not create a materially clearer trail;
the stronger version increased decoded face-loss from about 0.205 to 0.214 and lifted the
12.500 s frame away from the darker reference. Both were rejected and the weighted radial
`step14` result restored. Their local MP4s remain under `heartbeat-echo-screen-0908/` and
`heartbeat-echo-screen-strong-0908/` as negative evidence.

## Face restore calibration — 2026-09-08

The weighted radial trail was still being erased inside the detected face after composition:
the final shader restored 95% of the unmodified face over every Heartbeat echo. A scoped
candidate lowers only this Heartbeat restore to 70%; Sigma and generic double exposure are
unchanged. `heartbeat-faceecho70-0908` was rendered on the emulator and compared against
`heartbeat-directed-finale-0908`. Inspector evidence matches all 1,271 primary and secondary
decoded source PTS, with zero changed or missing timestamps, so this is an effect-only A/B.

At 12.500 s the displaced head/hair copy is visibly clearer without the rejected concentric
rings or horizontal bands; decoded whole-frame luma stays effectively constant (`0.3382` to
`0.3386`). The authored 15.600 s light accent remains at `0.9355` versus `0.9405` in the
reference. Browser playback from 11.75 through 19.46 s reported zero dropped frames and no
decode errors. The conservative face-loss metric worsens from `0.18565` to `0.19831`; retain
this as a bounded visual trade-off, not reference parity. The remaining largest gap is the
reference's more articulate temporal contour choreography and stronger source-plan variation,
not merely echo opacity.

## Directed resolve candidate — 2026-09-09

Exact 100 ms decoding of the author reference across `14.200–15.200 s` changes the
earlier constant-phrase interpretation: the shot begins with several widely separated
heads/shoulders and resolves progressively to one clean figure before its end. The prior
Heartbeat render instead held a faint radial halo throughout; most of the visible separation
was confined to the high-contrast collar.

`heartbeat-echo-directed-0909` keeps the existing three-sample GLES route but gives each
shot a one-way smooth resolve, raises the initial radial step from `.14` to `.22`, and adds a
bounded diagonal drift that vanishes with shot progress. The first envelope-only candidate
was visually rejected because the face remained almost single. The directed candidate shows
2–3 distinct live face/body positions at 14.2–14.6 s and converges by 15.1–15.2 s, without
concentric rings, black edges or a frozen source frame. This is a visible improvement toward
the reference choreography, not full parity.

The production UI render completed 1,271/1,271 frames with Heartbeat author audio, 912 AAC
samples, 13,288 us final A/V delta and rotation 0. Face-loss remains `0.10227273`; the change
did not add mask edge warnings. A frame-by-frame inspector comparison with the preceding
hard-cut/defocus render found zero changes to output, requested source, decoded primary or
decoded secondary PTS, so the visual difference is an effect-only A/B. Evidence is local under
`artifacts/device-regression/emulator/heartbeat-echo-directed-0909/`. The generic temporal
separation warning remains because its threshold measures source PTS lag, not spatial-copy
separation. It is not being suppressed in this iteration.
