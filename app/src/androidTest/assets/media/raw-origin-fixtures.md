# Raw composition origin fixtures

These two tiny synthetic AVC clips contain the same encoded media bytes: 160×240,
30 fps, 60 frames, planar luma `80 + frameIndex`, neutral chroma. No user footage.
Regenerate with `tools/generate_raw_origin_fixture.py`, passing FFmpeg and this directory.

The seed starts at zero. Its sibling adds a uniform `ctts` composition offset of
12,000 ticks in a 30,000-tick media clock. It has no edit list that could cancel
that offset. Literal first PTS: **400,000 μs**. Last sample PTS: **71,000 ticks**
(2,366,666⅔ μs). Exclusive presentation endpoint: **2,400,000 μs**.

The authoring script rejects unsupported box layouts, verifies 60 samples at
1,000 ticks per sample, requires `moov` after `mdat`, rewrites enclosing box sizes
and durations, and checks unchanged chunk offsets and media bytes. The media
SHA-256 is `af73e6cf6085a1b0c1aad11c9c63f4996a68f60ec28ac49b746187a1eee4ef6a`.

Fixture SHA-256:

- `raw-origin-seed.mp4`: `fab99da4996080125a93621625bd1e2c86c67ff3025dcaf34ecaba4e47ca9552`
- `raw-origin-400ms.mp4`: `73343e52de0d4fcb0e77e943ebd23360b4a6556a21cc5bba642e09e32af256f8`

FFprobe reports 60 samples and first PTS 400,000 μs both with and without edit-list
processing. Host checks do not prove Android behavior: the device test independently
requires real MediaExtractor/inspector first PTS and exclusive endpoint before rendering.
