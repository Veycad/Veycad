# Opening silhouette contracts independently of source motion

Status: open, visually unresolved. Local-only.

`MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER` changes erosion and
alpha thresholds with `uForegroundReentry`. Even a fixed mask changes silhouette
between progress .62 and .78. Reproduce using `tools/probe_matte_phase.py` and
the locally archived source-aligned alpha PNG.

Two emulator-rendered alternatives were rejected: constant strong filtering
cuts hair at output 1.5s, while constant soft filtering increases edge leak to
6.90%. Production code was restored. Full evidence and artifact names:
`docs/reference/matte-phase-experiment.md`.

Acceptance requires preserving real hair/face/shoulders while rejecting background
throughout a live entrance on all three Golden sources, not merely improving IoU.
Do not close this bug based on a static-mask test or shader-string assertion alone.
