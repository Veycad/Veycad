# Non-aligned matte texture produced diagonal bands

## Status

Resolved and verified on the Android emulator.

## Reproduction

- Render `leonid-live-matte.mp4` with the aspect-preserving multiclass mask (270x480).
- Inspect the `FOREGROUND_REENTRY` opening between 0.5 and 2.5 seconds.

## Symptom

The otherwise contiguous person matte appeared as wide diagonal black bands. Rendered QA reported
`maximumEdgeLeakRatio=0.16371009` and `subject-stage-background-not-isolated`.

## Root cause

`GL_LUMINANCE` masks are uploaded as one byte per pixel. OpenGL ES defaults
`GL_UNPACK_ALIGNMENT` to four, but a 270-byte row is not four-byte aligned. GLES therefore read
two phantom padding bytes after every row, progressively shifting the matte texture.

## Fix and evidence

Set `GL_UNPACK_ALIGNMENT=1` for the mask upload and restore it to four afterwards. The repeated
18.034-second device render contains no bands; maximum edge leak fell to `0.002727666`, temporal
mask IoU reached `0.98298997`, and subject-stage background luma fell to `0.07512329`.

