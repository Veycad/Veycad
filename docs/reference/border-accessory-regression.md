# Border-connected accessory regression — 2026-09-05

## Cause isolated

Golden 1 source PTS 18,994,822us still retained curtain after semantic support.
Exporting this frame independently (new segmenter, no earlier frames) produces
exactly the same source PNG and byte-identical person mask as the 91-frame run.
This rules out segmentation history as the cause at this checkpoint.

The six exported classes identify the upper curtain primarily as accessory.
On the quantized exported channels its largest accessory component is 12,010
pixels, bounding box x=23..256, y=0..81. It touches the top image border and has
31.27% person-supported boundary. The old 25% component rule accepts it.

## Implementation

`PersonMaskUnion.combine` now requires majority (50%) person-supported boundary
for accessory components that touch the image border. Interior components retain
the 25% rule. Image-border edges still count as unsupported. This is a bounded
conservative heuristic for unknown offscreen extent, not proof of accessory
identity. No new model, shader or dependency was added.

Added tests for a wide top-border curtain passing the old threshold and a
border-touching accessory supported on its three visible sides. Existing tests
for enclosed eyewear, narrow curtain rejection and soft hair remain passing.
Synthetic accessory tests do not replace real hats/glasses validation. A cropped
hat with weak observed attachment may still be rejected; this is an explicit risk.

## Three-source mask regression

On emulator-5554, freshly exported 30 / 91 / 30 frames for Golden 0 / 1 / 2.
All source PNGs match previous inputs exactly. Golden 0 and Golden 2 person masks
are byte-identical to their previous versions across all 30 sampled frames each.
Golden 1 changes only three of 91 sampled masks. No blanket temporal smoothing
or static/frozen mask is introduced.

With the same RVM opacity and same five-pixel semantic support operation, the
known background ROI x=[235,270), y=[0,55) at 18,994,822us falls from mean alpha
.468969 to 0; selected face ROI x=[100,180), y=[130,250) remains 1.0.
The source itself is motion blurred. Visible coarse/soft hair edges persist;
this targeted fix does not establish a clean or reference-quality matte.

- [Before support correction](../../artifacts/reference-analysis/rvm-supported-sequence-g1/compare-000018994822.png)
- [After support correction](../../artifacts/reference-analysis/rvm-border-supported-g1/compare-000018994822.png)
- [Updated source-time sequence](../../artifacts/reference-analysis/rvm-border-supported-g1/sequence.gif)

These are source-time diagnostics, not final-render acceptance. The actual
production union changes, while RVM remains an external diagnostic only.

## Verification

`:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passed. Model/GPU path
is unchanged. APK listing excludes ONNX/RVM models and MP4 fixtures. Mask export
markers report status=ok with 91/30/30 frames. No device-phone test was run.
The three-source clean-opening goal remains open.

## Full render and the product integration gap

Both ordinary and external-alpha renders completed: 542 frames each. External
render uses 91 masks, 75 opening frames with opacity semantics, final video PTS
18,033,333us and AAC PTS 18,018,684us. Compared with the previous supported-alpha
render, the 90 opening frame records match for output/source PTS, scale, x/y,
entrance envelope and transition. This controls those parameters, not every
auxiliary attachment. The sampled opening still has a broad soft hair edge.

- [Ordinary output](../../artifacts/reference-analysis/border-union-g1.mp4)
- [External diagnostic output](../../artifacts/reference-analysis/border-union-g1-external-matte.mp4)
- [External inspector](../../artifacts/reference-analysis/border-union-g1-external-matte-inspector.json)
- [External checkpoints](../../artifacts/reference-analysis/border-union-g1-external-matte-opening.jpg)

**Crucial negative result:** ordinary `border-union-g1.mp4` and the previous
`semantic-support-g1-baseline.mp4` have identical media-data (`mdat`) payload
SHA256 `b02c64d311ba74690a6f62fecfbc163861ec76832f9aac4b96f00ef2407ad202`.
Whole-file hashes differ (container metadata), but no encoded media improvement
is present in the ordinary result. Do not report this patch as a visible app fix.

Production analysis samples at 250,000us intervals starting at half the interval.
The three altered dense checkpoints lie between these samples. The next needed
integration is bounded, denser segmentation of the already-selected opening
source window, merged into the actual candidate graph before render. Do not
increase whole-video mask density: that previously exceeded the memory budget.
Preserve source-time alignment and the other graph decisions, test bounded
allocation and compare actual ordinary-app output. This fixes a demonstrated
integration gap, not a request for another model or effect.
