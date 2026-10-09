# Edge-coordinate experiment — rejected

Baseline: a68cb0e, `reveal-complete-g1.mp4`.
Experiment: route the interior-colour sampling point from bitmap mask coordinates
through the Y flip and actual decoder texture matrix, rather than adding the mask
gradient directly to OES coordinates.

Actual emulator output: `artifacts/reference-analysis/edge-coordinate-g1.mp4`, with
matching `.result.txt` and `-opening.jpg` artifacts. Duration 18.034 s, 542 frames.
Decoded edge leak changed from 0.032062847 to 0.03212434; temporal IoU changed from
0.91305935 to 0.91280895. Both runs fail edge leak and finale isolation.
Visual opening comparison shows the same pale hair fringe and crown residue.

Decision: revert the shader experiment. It does not resolve the observed defect.
This result rules out this coordinate correction as a useful fix on this source,
not all possible coordinate defects on other orientations.

Next investigation: compare the inferred alpha with the matching live source at
the failed 1.8 s output checkpoint; distinguish retained background, interpolation
lag and colour contamination before choosing a further shader/model change.
