# MODNet checkpoint rejection — 2026-09-05

Local diagnostic, not an application change or completion of opening acceptance.

## Named defect and control

RVM independent-frame opacity retained a false wall region at source PTS
19,061,488us and soft hair edges at 19,794,831us on Golden 1. Increasing RGB
resolution alone did not solve the wall defect: prior low-resolution wall ROI
mean was .211875 versus .223822 with native RGB and internal analysis side 480.
These are selected background ROIs, not whole-mask ground-truth accuracy.

Tested the official MODNet photographic portrait ONNX model on the exact native
1080x1920 PNGs at those two timestamps. Model SHA256:
`07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9`.
The downloaded file successfully loads in ONNX Runtime 1.29.0 CPU.

Official protocol source:
https://github.com/ZHKKKe/MODNet/blob/master/onnx/inference_onnx.py

Local script uses RGB [-1,1], NCHW and official multiple-of-32 size policy
(512x896 for these inputs). Pillow float BOX replaces OpenCV INTER_AREA, and
float bilinear alpha restoration replaces the demo's quantized area resize;
therefore this is explicitly not a bit-exact reproduction of the demo.

## Observed result

Both source-alpha comparisons show substantial curtain/wall retained above the
head. The second checkpoint has disconnected bright background fragments.
The candidate fails background exclusion before any GLES animation. It must not
be promoted based on preserved face detail alone.

- [Wall checkpoint](../../artifacts/reference-analysis/modnet-native-wall-g1/comparison.png)
- [Hair checkpoint](../../artifacts/reference-analysis/modnet-native-hair-g1/comparison.png)

Adjacent report.json files bind input/model hashes and record 0.384s / 0.372s
CPU inference respectively. These are local single-image measurements, not
Android performance or temporal validation. No new MP4, three-source acceptance,
production model integration or reference parity is claimed.

## Retained deliverable

`tools/evaluate_modnet_probe.py` is a local-only reproducible inference probe;
four unit tests cover preprocessing dimension policy. Model/artifacts remain
outside app assets. Do not retry this exact configuration or add the runtime to
the APK without evidence addressing false background classification. A next
experiment must constrain foreground/background ambiguity independently instead
of assuming another unconstrained model alone will solve it.
