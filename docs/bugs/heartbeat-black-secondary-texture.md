# Confirmed black secondary GPU sampler on emulator

This supersedes visual tuning conclusions in heartbeat-weak-echo.md.
The second decoder advancing PTS does NOT prove usable pixels reach GLSL.

Diagnostic debug intent `heartbeat_texture_probe=true` runs a one-second graph
without ML analysis. `debugTextureProbe` (default false) returns raw incoming
on the left and raw outgoing on the right, bypassing all effects and grading.
The probe uses secondary offset -1 ms (zero means legacy -420 ms fallback).

`heartbeat-split-probe-0907`: left live video, right black. Local PNG
`artifacts/reference-analysis/heartbeat/split-probe/frame-0.png` proves it.
Decoder texture names differ, and outgoing matrix is a valid rotated matrix.

Hypothesis tests:
- Explicit GL texture unit per DecoderInput creation/update: `bound-probe`
  right becomes solid grey, not video. NOT a fix.
- Unbind GL_TEXTURE_2D on OES units and OES on semantic units:
  `cleanbindings-probe` right remains black. NOT a fix.

All builds/unit/lint passed, demonstrating why those tests alone cannot approve
the compositor. Current diagnostic code/default-off raw view and explicit
bindings remain in the worktree; no final visual fix claimed. All probes are
terminal; there is no active renderer. Next isolate sampler binding/reflection
and external texture compatibility, then rerun the full music-backed edit.

Storage: removed four old first-g0/defocus-retry-g0 private/export duplicates
only after SHA-256 matched retained local MP4s (~53 MB). All are recoverable
from artifacts/reference-analysis/heartbeat; original source fixtures untouched.

## Isolated fix, 2026-09-07

`sametexture-probe` binds the known-good incoming texture to BOTH slots. Right
half remained black: the secondary decoder/image is not the sole cause.
Changed just the combined declaration `uniform samplerExternalOES uIncoming,uOutgoing;`
to two individual declarations. `separatesampler-probe` with TWO REAL decoder
textures now displays video in both halves; PNG stored in local artifacts.
This isolates declaration compatibility in this emulator shader path. It does
not prove that all physical GPU drivers exhibit the same issue.

Added source-contract unit guard against grouped external sampler declarations.
Probe now checks decoded luma variance separately for each half at 0.5 s,
rejecting flat black/grey. `variance-probe` passes: [0.05085849,0.02527348].
This is fixture-specific coverage, not a general quality score. Unit/lint/build
pass. Full music-backed paired run `heartbeat-livesamplers-g0-0907` launched
after probe completed; full montage visual quality remains pending.

Earlier echo/transition PTS counters alone were insufficient evidence of usable
second-source pixels. Re-evaluate the actual Heartbeat effects with this fix.

## Full edit after fix

`heartbeat-livesamplers-g0-0907` completed, 13,821,479 bytes, 1271 frames,
720x1280/60 fps, 21.1833 s, audio present, last-PTS A/V delta 13,288 us.
26/26 measured light impulses matched decoded luma. Control also completed.
MP4/report/inspectors retained locally. Contact frames `livesamplers-after`
now show real repeated collar/necklace/face contours, unlike the darkened
black-secondary versions. This proves effect execution in the full pipeline,
not reference-quality completion. Facial doubling at 14.0/15.6 s remains
too conspicuous. Normal-speed audiovisual review is still outstanding.
Generic quality acceptance remains false; do not waive its failures or claim
its author/grammar metrics establish Heartbeat parity. No render is active.
