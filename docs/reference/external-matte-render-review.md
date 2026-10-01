# External matte: three-source local check and real GLES rejection

Date: 2026-09-05. Diagnostic only; production mask provider/shader unchanged.

## Evidence

All three Golden sources processed through the same official RVM MobileNetV3
FP32 model already described in `rvm-local-feasibility.md`. No model/runtime was
added to the APK. Thirty source-time frames per source, around the actual opening
source ranges, were exported from emulator-5554 and processed locally with
recurrent state preserved. `rvm-temporal-g0`, `rvm-temporal-g1`,
`rvm-temporal-g2` under `artifacts/reference-analysis/` contain paired images,
GIFs and machine-readable reports. These are 10fps diagnostics, not edits.

Golden 0 / source 23.925s and Golden 2 / source 23.525s preserve fine side hair
without large background patches in the inspected images. This does not prove
their entire sequences are artifact-free. Golden 1 / source 19.795s also looks
promising in isolation, but that single checkpoint is misleading.

## Controlled GLES experiment on Golden 1

`GoldenRenderActivity --es external_matte_dir <directory>` first renders the
ordinary edit, then passes the SAME graph, audio, source video, output size,
bitrate and render capabilities to the actual MediaCodec/GLES renderer with
only a source-time mask interval replaced. Source-video SHA256 is verified
before reading external PNG alpha. The rest of the attachment timeline remains
unchanged. Replacements are debug-only; product UI cannot select this mode.

- Output: `artifacts/reference-analysis/matte-oracle-g1-external-matte.mp4`.
- 542 frames; video last PTS 18,033,333us; AAC last PTS 18,018,684us.
- 30 masks cover source 18,094,831 through 20,994,831us.
- Opening image at output .5s shows a large wall fragment above/right of hair.
  Its source PTS is 19,081,920us. Adjacent raw-model composites at 18,994,831
  and 19,094,831us show the same false foreground, BEFORE GLES.
- Thus the new failure is in the model output; it is not evidence of a decoder
  coordinate bug or a new editorial choice.
- Inspector `summary.accepted=true` checks execution coverage, NOT visual matte
  quality. The candidate is manually REJECTED. No full decoded perceptual QA
  score was produced for this diagnostic candidate.

## Density hypothesis rejected

The same source interval was exported at 33,333us spacing (91 frames, about
30fps), then run locally with the same model and recurrent state. See
`rvm-export-g1-30fps/sequence-report.json`, `sequence.gif`, and paired PNGs.
At source 18,994,822 and 19,094,821us, the false wall fragment is still visible.
The requested timestamps differ from the 10fps probes by only 9–10us; extracted
source images visibly match. No claim is made that retriever requested PTS are
exact decoder PTS. Increasing density did not cure this defect. A second full
GLES render was not needed to reject an already visibly incorrect source alpha.

## Retained changes and checks

- Reusable debug-only external-alpha experiment, with MP4 SHA256 binding,
  bounded frame count/dimensions, ordered timestamps and no silent overwrite
  of existing diagnostic outputs.
- Three debug unit tests: interval-limited replacement, preserved outside
  records/original timeline, duplicate PTS rejection, empty override rejection.
- Unit tests, debug APK build and lint passed (0 errors, 3 existing warnings).
- Tests live in `src/testDebug`, not the common release test source set.
  A requested `compileReleaseUnitTestKotlin` check was unavailable in this
  project's task set; it is not reported as passing.
- APK archive inspection found no ONNX/RVM/MP4/Golden files.

Neither RVM nor the external-mask route is an accepted product implementation.
The three-source clean live entrance goal remains open. Next model/algorithm
must be evaluated on the fast pull-back failure as well as static clean frames;
do not repeat a density-only or threshold-only fix for the recorded defects.
