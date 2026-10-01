# Frame-pair preparation reuse, exhaustive matching unchanged

2026-09-27. This is a semantics-preserving cost refactor, not a new motion model,
physical-motion validation or independent release acceptance. No new holdout,
original media, music, old result, threshold or product grammar is changed here.

## Cost evidence in the pre-refactor implementation

Each nonempty `LocalMotionCorrespondence.estimate` built two full quarter-pixel
planes. `PersonMotionEvidence` first evaluated the coarse field; after independently
supported camera, `PersonLocalCorrespondence` evaluated the smallest body query,
11 distinct elongated shape factors, and an optional larger contradiction query.
Thus an observation reaching all nonempty stages could construct the SAME
interpolated pair up to 14 times (28 quarter planes). Empty requested-center lists
already returned before constructing interpolated arrays and must retain that rule.

For 144x81 input, each quarter plane is 573x321. A pair contains 367866 floats,
1471464 array-payload bytes (~1.403MiB). Fourteen pairs contain 5150124 interpolated
float samples. Exact array-payload arithmetic:

- 14*1471464 = 20600496 bytes (~19.646MiB).
- One pair = 1471464 bytes; redundant pair payload removed = 19129032 bytes
  (~18.243MiB), a 13/14 reduction for this maximum-stage case.

These are structural counts, not a heap-profiler trace. Actual calls may have empty
centers; only 15/121 observations reached body matching in the disclosed v15 replay.
This does NOT predict a speedup relative to the old 175026ms/current 374847ms Android
analysis: model inference, exhaustive matching, decoding and other work still remain.

At 144x81, sampling 3x1.125 yields quarter-search steps 36x13: 1971 hypotheses per
textured match, forward and backward separately. The old inner candidate lookup
recomputed `i % patchWidth`, `i / patchWidth` and a quarter-row multiplication
for every patch pixel of every hypothesis. For a 15-pixel smallest body query,
both textured directions read 59130 candidate pixel samples per center; larger
queries multiply this cost. The exhaustive hypotheses themselves are NOT removed.

## Implementation scope and invariants

`PreparedPair` holds two interpolated snapshots with private float arrays, exact
width/height and actual interpolated-sample count. It retains no source-array alias.
`PersonMotionEvidence.assessPixels` prepares one pair after unchanged mask/human
gates and passes it to coarse correspondence and then all body shapes/contradiction
checks. `PersonLocalCorrespondence` retains its standalone previous/current API,
but also accepts the prepared pair. There is no global memoization, source-only
cache key, previous-source carry-over or sharing across independent observations.

The original `LocalMotionCorrespondence.estimate(previous,current,...)` API remains.
Its missing/unequal-dimension refusal, parameter validation, bounds, requested-center
filtering/distinct order, tiny-plane refusal and empty-list-before-interpolation
behavior are retained. Prepared arrays remain unchanged when caller-owned original
luma arrays are subsequently modified; a newly prepared pair sees the new pixels.

Patch lookup offsets are precomputed ONCE per estimate/footprint in row-major order.
For quarter width W, former coordinate lookup expands exactly to
`(y-dy)*W + x-dx + ((i/patchWidth-radiusY)*4*W + (i%patchWidth-radiusX)*4)`.
The values are the same; the query/candidate mean, texture, residual accumulation
and their float operation order are untouched. Both quarter planes have the same
width by the original frame-dimension contract. The hot hypothesis loop no longer
performs integer division/remainder for each candidate pixel.

Every exhaustive candidate, traversal/tie-breaking order, bounded gain, texture,
fit, uniqueness, reverse match, exact shape disagreement, radius, confidence,
current-mask purity, largest validated footprint, native pixel ownership, unmatched
mask denominator, disjoint witnesses, coverage gate, actual PTS and FEAR threshold
remain unchanged. Shape pruning and radius deduplication were not implemented.
At 144x81 all 11 elongated footprints are different; deduplication would not remove
this disclosed landscape case's dominant multi-shape searches anyway.

## Verification completed by this change

`MotionPreparationReuseTest` contains an independently retained complete pre-refactor
matcher, not a second call to the optimized implementation. It compares EVERY
ordered Cell field; all float fields use raw-bit equality, including zero signs.
Controls cover 64x64, 144x81 and 81x144, seeded texture, quarter/subpixel translation,
contrast gain .6, offset lighting, unrelated pixels, periodic/flat ambiguous basins,
duplicate/border centers, missing/unequal/tiny inputs and empty lists. An independently
moving inner region versus outer footprint produces two reliable conflicting
displacements; both optimized variants preserve the old raw values, not a conflict
silently changed to agreement. Snapshot independence is tested by mutating the
original arrays after preparation. Structural sample counts compare 14 independent
preparations with one pair reused 14 times; no flaky wall-clock assertion is used.

Targeted Gradle test session 76830 completed successfully (exit 0, 57s build).
The XML timestamp is 2026-09-26T19:35:13.121Z (27 September in local timezone):
6 tests, failures/errors/skipped 0, test-suite time 1.012s. The retained oracle and
controls are disclosed calibration tests, NOT blind footage or independent human
acceptance. Other agents' already-completed shared-tree changes may have been
compiled alongside this targeted run; this does not certify their full behavior.

Full regression/build and a frozen-APK, exact-PTS device replay are still required
before production runtime/compatibility claims. The v15 Coconut calibration remains
a failed positive FEAR challenge (2 measured points, both below .18); this refactor
does not resolve identity, blur/deformation, true per-pixel flow or montage quality.
