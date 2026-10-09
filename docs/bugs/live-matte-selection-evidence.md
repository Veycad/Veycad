# Live matte selection and evidence — 2026-09-07

Scope: first three seconds, three local user Golden sources. Local only.

## Confirmed defects

- `RenderedCandidateSelector` ignored decoded edge leak and temporal matte IoU when
  otherwise identical candidates tied. Exact-source refinement could lose to list
  order. A regression test now covers both orders and both selection entry points;
  the tie breaker prefers higher temporal IoU minus edge leak. Acceptance gates
  and primary edit scoring remain authoritative.
- `GoldenRenderActivity` exported `VeykadRenderInspector.latest()` after candidate
  selection. The last render can be a rejected refinement, so its opening sheet
  and inspector were not necessarily evidence for the selected MP4. Each candidate
  now retains its own inspector artifacts immediately after rendering, with an
  MP4 name/byte-count check, and Golden export uses the winner's artifacts.
- Golden output can be requested directly inside the export directory. Export now
  skips copying an MP4 onto itself.

## Verification and limits

`./gradlew testDebugUnitTest lintDebug assembleDebug` passed after these changes.
The selector regression exercises a clean refinement versus a weaker sparse mask
at exactly equal primary scores. It proves selection behavior, not image quality.

Emulator `emulator-5554` is attached. Baseline run `exact-opening-g0-0907` was
started before these changes with `veypad-test-0.mp4` and the local author track.
It must not be reported as validating the newly changed selector or export.
Fresh post-change renders and visual comparison on all three original Golden
sources remain required. No visual improvement or reference parity is claimed.

## Follow-up: exact-frame masks punched holes in the face

Decoded opening sheets from `exact-opening-g0-0907` show black holes around both
eyes at 2.0 s in the refined candidate, absent from its baseline. The raw
exact-frame class mask replaced all baseline face support. A bounded inset face
ellipse now preserves the interpolated baseline mask support, only when face
confidence is at least 0.65 and occlusion is at most 0.25. It neither retains RGB
nor expands the external hair silhouette. Unsupported baseline pixels remain
unsupported, and protection fades inside the inset boundary.

The regression test checks interior recovery, unchanged exterior and input
buffers, no invented support, and skipping absent/occluded faces. All 136 unit
tests, lint and debug assembly pass. Post-change emulator run
`face-supported-g0-0907` is required to establish whether the visible holes are
removed; this test result alone is not visual proof.

Emulator verification: `face-supported-g0-0907-refined.mp4` removes the visible
black eye patches at the decoded 2.0-second checkpoint compared with
`exact-opening-g0-0907-unprotected.mp4`. The external hair halo remains. The
selector still chose the baseline; exported report correctly identifies it and
contains `opening_mask_refinements=0`, proving the evidence-binding correction.
This is a confirmed repair in the refined candidate, not improved final output.
Golden 0's overall gate fails on final-stage background luma 0.11350 (limit 0.09),
outside the opening window. New Golden 1 run: `face-supported-g1-0907`.

The earlier baseline also reproduced the same-path export failure with
`FileNotFoundException`; its MP4 was preserved locally before export. The fixed
runner uses a private output directory and a distinct export destination.
