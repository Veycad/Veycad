# Golden 1 crown: curtain classified as hair

Observed output 1,000,000 us maps to source 19,354,457 us in the selected
`face-supported-g1-0907-refined.mp4` inspector. Local probe exports source RGB,
six class planes and production person union under
`artifacts/reference-analysis/crown-probe-0907`.

The protrusion is already present in channel 1 (hair), not introduced by the GPU.
Unlike the earlier border accessory bug, the accessory channel does not explain
this region. In diagnostic ROI x=[97,115), y=[48,64), hair probability has mean
0.39052/max 0.68235; background mean is 0.51093, accessory mean 0.02943.
This ROI documents the hypothesis; it is not a production hard-coded mask.

Eight neighbouring source frames were exported at 33,333 us intervals from
19,254,458 us. The exact 19,354,457 us production mask is byte-identical between
isolated and sequence inference (SHA256
244622d437bc465316c06c50816a078cc0a6976ca051ca9d32d140ffaf184f51).
The diagnostic contact sheet sums all person classes, including accessories;
only exported `person-*.png` uses the production accessory rejection rule.

Experiment: apply existing `SemanticMaskMetrics.guidedRefine` to exact-opening
union masks using source-aligned luma. Sparse baseline stays unchanged. This
targets ambiguous edges; confident interiors and the existing face protection
remain. No new model or dependency. Unit tests/lint/assembly pass, but these
checks do not establish visual improvement.

Emulator render started: `guided-crown-g1-0907`. Compare its refined candidate
against `face-supported-g1-0907-refined.mp4`, especially output 1.0 s, hair and
face across 0–3 s. Retain only after verified improvement; otherwise revert the
new guided block in `MulticlassMatteProvider` without removing prior work.

## Verified negative result

`guided-crown-g1-0907.mp4` completed and selected the refined candidate. Its
decoded 1.0 s checkpoint still shows the curtain protrusion. Edge-leak metric
improved from 0.00025546 to 0.00011620, while temporal IoU fell slightly from
0.92460 to 0.92406. Face-loss and other full-edit metrics remained unchanged.
Neither the metric improvement nor successful encoding fixes the named defect.

The experimental guided block was therefore reverted; prior exact-frame and
face-protection changes were preserved. MP4, opening sheet and inspector remain
under `artifacts/reference-analysis/guided-crown-g1-0907*` as negative evidence.
Do not repeat this 3x3 luma-guidance experiment as a proposed solution to the
same crown protrusion. The existing QA must gain coverage of the early moving
boundary before it can discriminate this particular failure automatically.
