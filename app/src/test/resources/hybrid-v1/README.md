# Historical v1 fixtures

`project.bin` and `revision.bin` were generated on 2026-10-10 with the
unmodified encoder at commit `b5b6af962a37f7bbe1fe60520e6f7d6a7cafa3dc`, before
adding `HybridClip.originalFrameOffset` or the physical format-v2 encoder.
They are fixed compatibility fixtures; do not regenerate them with the current codec.

The temporary capture test used `storageProject()` with an empty
`FrameAttachmentTimeline` (no external analysis sidecars), shared original/current
revision 0, and an export reference to revision 0 named `automatic.mp4` with
1080x1920, 30 fps, 8,000,000 video bitrate and 192,000 audio bitrate.
It wrote `codec.encode(project)` and `codec.encodeRevisions(listOf(revision))[0]`.
The capture ran as `:app:testDebugUnitTest --tests
com.veycad.app.HybridV1FixtureGeneratorTest --max-workers=1`, passed, and the
temporary generator was removed before implementation.

SHA-256:

- `project.bin`: `a5bb3ce1a557ec2623b15b460a32fe5550d8d833a3896b56c9384e66bb101d8c`
- `revision.bin`: `018c5cc13f3b2c928c6bdf4f68d107e114e5e491617b9429a532265e98e8a5dd`
