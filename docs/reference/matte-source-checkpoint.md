# Exact-source matte checkpoint, Golden 1

Output 1,800,000 us maps to source 19,794,831 us in the actual GLES inspector.
The local diagnostic reads the cached PTS planes and reproduces timeline interpolation
before any GLES colour treatment. Source and alpha are exported separately.

250 ms evidence: `artifacts/reference-analysis/edge-source-probe/edge-source-probe/`.
100 ms evidence on the same source PTS: `artifacts/reference-analysis/edge-source-probe-100/`.

The source is strongly backlit from the left. Its original hair pixels already carry
pale light, while the alpha also retains background above the crown and a broad
shoulder/head fringe. These are distinct defects: RGB contamination and mask geometry.

Increasing inference cadence from 250 to 100 ms did not remove that fringe at the
matched source PTS. The 100 ms alpha includes a visible part of the curtain above the
head. This contradicts cadence alone as the solution. Temporal IoU was a clue, not
proof that interpolation caused the entire problem.

The full 100 ms render additionally crashed in ML Kit face analysis at the 192 MB heap
limit. AndroidRuntime records PID 24808 and OutOfMemoryError allocating 518,416 bytes;
the cache contains high-resolution Float planes and derived depth planes for the
whole clip. Log: `artifacts/reference-analysis/matte-100ms-g1-crash.log`.

Decision: revert the production cadence to 250 ms. No successful 100 ms MP4 exists.
Keep the debug checkpoint exporter because it separates input/mask defects from GPU
defects without repeated inference. Next inspect the six class channels at this exact
source window before changing the foreground union or choosing model replacement.

Goal remains unfulfilled; no visual gain is claimed for this experiment.
