# Heartbeat: decoded black-tail evidence

This changes the machine gate, not the artistic acceptance of any existing edit.
The previous Heartbeat gate measured the 26 authored pulse windows but did not
measure the promised terminal black tail. A correct overlay/plan and an inspector
count cannot prove that the exported pixels actually became black.

## Exact contract

- Profile: `HEARTBEAT_V1`, 60 fps, 1270 output frames; rounded-millisecond graph
  endpoint 21.166 s is exclusive. Frame 1269 is 21.150000 s; there is no frame 1270.
- Authored tail boundary: 19.800000 s, frame 1188. Nominal tail: 82 frames.
- Decoder QA requests frame 1186 through 1269: 84 exact rounded 60 fps PTS,
  including two adjacent terminal-white frames before the tail. Starting at
  frame 1186's PTS minus 1 microsecond, it measures every actual decoder image
  through EOS, even between targets or after the final target. Uniform and
  existing pulse/effect sampling remain; this is not a full-output vision pass.
- Each slot must have exactly one measured decoded image at its expected PTS
  within 1 microsecond. Missing/late/duplicated evidence cannot be interpolated or
  replaced by the graph, a later decoder image, or a container frame count.
  Every image in the captured boundary/tail region must occupy one of those
  slots. Off-clock and extra images fail even when black and even when all 84
  expected slots are present; two images within the same slot's +/-1 microsecond
  allowance both fail as duplicate witnesses. Duplicate PTS are retained.
- The boundary witness at frame 1186 must have decoded mean luma >= .95. First
  black decoded image must be within one 60 fps frame (16.667 ms) of 19.800 s, plus
  the separate 1 microsecond timestamp-rounding allowance.
- Black means decoded sampled mean luma <= .05. From the first black boundary
  image onward every actual decoded image must stay black, including the last image.
  At the latest allowed onset (frame 1189), 81 nominal tail frames are black;
  otherwise 82 are black. All 82 nominal tail slots must be measured regardless.
- The independent 26 pulse checks remain unchanged. An early black tail that
  overwrites the final authored white pulse can pass the tail timing tolerance
  but still fails that pulse check. This does not weaken the pulse grammar.

The pixels/luma come from sequential decoding of the actual exported MP4 in
`RenderedVisualSampler`. Target timestamps are expected clock slots, not evidence
of their existence. `HeartbeatPulseAudit` compares them with actual decoded PTS
and luma and returns missing/bright/unexpected times and measured onset; device acceptance
rejects `heartbeat-tail-mismatch` when any condition fails.

## Report and offline gate

The debug report records `heartbeat_tail_method=decoded-luma-contiguous-60fps-v1`,
expected/measured/black frame counts, boundary coverage, matched flag, first
black PTS and offset, missing/bright timestamps, the required empty
`heartbeat_tail_unexpected_us` field, and the pre-boundary luminance.
The offline per-product gate requires all fields, rechecks nominal coverage,
finite integer counts/PTS, legal 60 fps onset slots, count/onset consistency and
the empty failure lists. `acceptance=true`, correct pulses or a positive human
form do not replace missing tail evidence. Old `.result` files are preserved
and do not become evidence for this stronger gate; no values are backfilled.

The luminance threshold is a coarse decoded blackness measurement, not proof
that every pixel is exactly zero or a perceptual judgment of the ending. Human
1x/0.5x acceptance, correct music, visible reprise and readable hero remain
mandatory. A unit-test fixture is not an independent release render.

## Verification

Focused Kotlin fixtures cover the current nominal tail, permitted one-frame
onsets and codec timestamp flooring; missing boundary/middle/final slots;
absent or bright tails; early/late onset beyond tolerance; interior bright
holes, including a hole after an early onset; off-clock/duplicate samples;
complete 82+2 target coverage plus extra bright/black images at +/-2 microseconds,
duplicate witnesses at +/-1 microsecond, and an extra image beyond the final slot;
end-exclusive final-frame coverage and sampler targets. Device-gate integration
requires tail evidence even when all 26 pulses match. Python fixtures reject
missing/invalid/contradictory fields despite a positive device flag and human
form, and retain the one-frame rounding allowance.

No media, music, completed `.result` or human review was rewritten. No new
holdout footage was rendered, viewed for tuning, or promoted to accepted quality.
