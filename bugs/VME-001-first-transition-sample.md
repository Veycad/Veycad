# VME-001 — first transition sample is reported black

- Status: fixed and device-verified on the local API 36 emulator
- Scope: local emulator API 36, Golden `veypad-test-0.mp4`
- External reporting: forbidden; keep local

## Reproduction

Render an eight-second DYNAMIC graph with `GoldenRenderActivity`, then decode the resulting MP4
with `RenderedVisualSampler` at 100 ms intervals.

## Evidence

- Output PTS: `1_500_000 us` (first frame of the first WHIP window)
- Decoded luma: `0.0010237619`
- Artifact score: `0.85`
- Beat-hit rate: `1.0`
- Transition peak: `0.8855945`
- Maximum colour jump after exposure normalization: `0.08070823`
- A/V drift: `33_334 us`

Correcting double alpha multiplication removed the persistent purple/dark midpoint visible in
contact sheets. Increasing source analysis to 200 ms and continuing the outgoing decoder clock
did not change this exact first-sample report.

## Root cause

The first scheduled overlap sample normally lands after the mathematical transition boundary
because clip boundaries and the 30 fps frame grid do not coincide. It was also the first frame
sampled from two newly attached decoder surfaces. On the tested GLES/codec path, that first
surface colour conversion was encoded as black.

## Fix and verification

- Acceptance now uses sequential `MediaCodec` YUV decoding with actual output PTS instead of
  random-access retriever seeks.
- The renderer holds the last verified previous-clip texture for one boundary sample, then starts
  the two live decoders on the remaining transition frames.
- Golden 0 DYNAMIC passed on 2026-09-02: `max_artifact=0.0`, beat-hit rate `1.0`, transition peak
  `0.86879134`, maximum colour jump `0.07991952`, A/V drift `33,334 us`.
- Verified output SHA-256:
  `b2704d667f08d744736bb8634318713c25e155bd709e4046a7b69aec3a58fc02`.
