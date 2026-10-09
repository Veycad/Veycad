# Matte perceptual-quality gap

## Status

Open. Local-only; do not send externally.

## Reproduction

- Fixture: `app/src/testFixtures/golden-mp4/leonid-live-matte.mp4` (gitignored, not packaged).
- Render: `artifacts/reference-analysis/leonid-live-matte-118.mp4`.
- Opening sheet: `artifacts/reference-analysis/leonid-live-matte-118-opening.jpg`.

## Evidence

The rendered matte looks synthetic around hair and the soft silhouette boundary even though the
existing acceptance metrics report `max_edge_leak=0.00033283778` and
`min_mask_temporal_iou=0.9800382`.

## Cause

The gates measure detached foreground and binary overlap. They do not measure loss of fine hair,
hard thresholding, colour contamination from the source background, or perceptual edge quality.

## Required fix

Use a colour-aware soft-alpha refinement at render resolution and add a perceptual matte gate that
scores boundary softness/detail and background-colour contamination. Do not treat low edge leak as
proof of a production-quality cutout.
