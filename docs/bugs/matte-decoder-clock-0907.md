# Exact mask sampled a different source frame than GLES

Golden 1 measurement: `decoded-clock-g1-0907-baseline-inspector.json`, obtained
from SurfaceTexture timestamps immediately adjacent to the draw call.

- At output 1,000,000 us, requested source PTS is 19,354,457 us; decoded texture
  PTS is 19,382,366 us (27,909 us later).
- In the first 2.5 seconds, 75 distinct requested times map to 38 distinct
  decoded textures; measured forward offset ranges 239..32,466 us.
- This is expected repeated-frame slow playback, not proof of a whole-window
  frozen still. The earlier requested-PTS count was insufficient live evidence.

Cause: DecoderCursor selects first PTS >= target, while mask extraction uses
MediaMetadataRetriever OPTION_CLOSEST. Inter-frame targets can select different
RGB frames for mask inference and compositing.

Fix pending visual validation: read the video's actual sample PTS, map requested
opening times to decoder-ceiling times, deduplicate, and infer those exact frames.
GLES looks up refined attachments using the updated SurfaceTexture timestamp
within the refinement window. Unrefined windows keep their prior behavior.
No optical-flow interpolation or new model is introduced. Frame-index and mask
budgets remain bounded. Unit tests cover variable frame spacing, duplicate
requests and the decoder's last-frame EOS hold.

Inspector exposes planned, decoded and attachment-mask PTS separately. Missing
measurements are null. Validate `mask_source_us == decoded_source_us` in the
opening refined candidate, then inspect its real MP4 against the earlier
`face-supported-g1-0907-refined.mp4`. Matching clocks alone does not eliminate
the model's false hair classification of the curtain.

## Golden 1 device result

`aligned-clock-g1-0907.mp4` completed, selecting the refined candidate. All 75
opening frames before 2.5 s have equal `mask_source_us` and `decoded_source_us`;
at output 1.0 s both are 19,382,366 us. The decoded 1.0-second checkpoint no
longer shows the narrow crown spike visible in `face-supported-g1-0907`, although
bright hair fringe remains. This supports retaining the clock correction;
it does not prove clean matting or reference parity across the sequence.

QA reports edge leak 0.00953 and temporal IoU 0.91447, worse than the unaligned
candidate's 0.000255/0.92460. The sampler still selects source-reference images
from planned source time, so clock mismatch in QA must be investigated before
interpreting that delta as solely a visual regression. Do not relax its gates.
Overall gate remains rejected for final-stage background isolation. Cross-source
aligned runs are still required. Artifacts are local under
`artifacts/reference-analysis/aligned-clock-g1-0907*`.

Golden 0 completed: all 75 opening samples in its refined inspector also have
identical mask/decoded PTS. The selected MP4 remains the baseline. Decoded refined
checkpoints retain irregular hair/neck edges; time alignment is verified, clean
silhouette is not. Artifacts: `aligned-clock-g0-0907-refined*`.

QA clock correction implemented: `RenderedVisualSampler` accepts the candidate's
measured output-to-source clock from its own inspector, retrieves the actual
source frame and applies the same refined attachment lookup as GLES. Output
timing/transform/effects are preserved. Old reports without measurements retain
the previous planned-time fallback; missing data is not invented. The unit test
verifies actual-source selection, retained output-frame properties and missing
measurement fallback. Tests, lint and assembly pass. New cross-source run with
this QA correction: `aligned-qa-g2-0907`, started on emulator-5554.

Golden 2 completed with corrected QA: the refined candidate has 75/75 matching
mask/decoded timestamps and 44 distinct decoded source frames in the first
2.5 seconds. Its opening sheet shows a moving face and background return, but
the selector retained the baseline. Final selected output still fails the
final-stage background gate (maximum luma 0.10853), outside the opening.
Clock correctness is now observed on all three refined candidates, while
product-wide visual improvement remains unproved. The comparison HTML now links
these exact selected and refined outputs rather than earlier files.

## Baseline fallback gap

The initial correction rebound attachments only inside an existing refinement
range. Since Golden 0/2 selected baseline candidates, their final MP4s retained
planned-time mask interpolation. The renderer and QA now both rebind attachments
for clip 0 FOREGROUND_REENTRY using actual decoded time, even without dense
refinements. Other clips retain their existing attachment behavior.

This corrects the interpolation clock; sparse baseline masks remain interpolated
estimates, not newly inferred exact-frame masks. Equal `mask_source_us` and
`decoded_source_us` proves lookup-time alignment, not identical inference input
for a sparse mask. A unit regression specifically covers the no-refinement case
and confirms that other clips remain unchanged. Tests/lint/assembly passed;
emulator run `baseline-clock-g0-0907` is the pending product-output validation.

`baseline-clock-g0-0907` completed and selected the baseline. Its 75 opening
samples now all match mask lookup time to decoded texture time. Decoded opening
sheet does not establish a noticeable hair-edge quality gain. Edge leak is
0.00013908, temporal IoU 0.98659, overall acceptance still fails final-stage
background isolation. The unchanged build is being tested sequentially as
`baseline-clock-g1-0907` and `baseline-clock-g2-0907` by a live local exec session;
do not start duplicate runs without checking current state.
