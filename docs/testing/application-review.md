# Application state, storage, source rules and UI logic test review

Reviewed on 2026-09-27 against the pre-review working-copy snapshot in
`artifacts/testing/review-20260927/before`, rather than Git HEAD. Pre-existing
uncommitted application changes were retained. This is a JVM contract review;
these tests do not operate Android views or execute hardware codecs.

## Per-file decisions

All paths are under `app/src/test/java/com/example/autoedit/`.

| File | Methods before → after | Decision and evidence |
| --- | --- | --- |
| CompletedRenderStoreTest.kt | 3 → 4 | Retain recovery and durable publication tests. Check specific rejection for missing/empty/directory input, unchanged previous bytes and actual absence of all partial files. Added independently timestamped latest-file selection and per-result saved state, including Unicode summaries and empty/partial/unrelated files. |
| MaterialSuitabilityTest.kt | 3 → 5 | Retain product and mixed-source cases. Added exact duration versus one microsecond below for each input position; require two actual human/face samples at independent 0.55/0.65 boundaries. The formerly positive-only full-body case now has a below-threshold control. |
| MontageFailurePresentationTest.kt | 13 → 13 | Retain. Typed domain exhaustion, search budget, codec errors, cancellation, blank messages, duration and unknown codes are distinct user-visible contracts. Exact copy assertions are intentional; these do not certify source suitability. |
| MontageStyleCatalogTest.kt | 5 → 5 | Replace redundant counts derived from the same catalogue with the explicit ordered available IDs. Retain identity, paused state and enforcement at the shared render boundary. |
| MontageStylePresentationTest.kt | 15 → 15 | Check independently specified 15,000/6,000 ms minima instead of comparing two production constants. Remove substring negatives already guaranteed by an exact string assertion. Check extra imports are truncated while retaining ordered primary sources. Preserve stale request/reset tests. |
| MulticlassMatteClientTest.kt | 1 → 5 | Use independently authored binary format magic/version. Add distinct PTS and rectangular unsigned planes; complete-payload invalid-header controls, optional versus required empty cache, and truncated pixel payload rejection. A separate 1..7-byte final-timestamp matrix exposed a real parser bug and first failed before its fix. |
| ProductSourcePoolsTest.kt | 4 → 4 | Retain. Product-specific selection, independent pairwise overlap, downstream director composition, minimum-length input and sparse-sample refusal test different behaviors. These synthetic pools do not certify real footage. |
| RenderWorkspaceTest.kt | 2 → 2 | Use automatically cleaned temporary folders instead of a leaking nonempty deleteOnExit directory. Assert exact locations and unrelated-cache preservation. Replace XML substring inspection with parsed element/name/path contracts for pending and completed results. |
| SequentialBitmapDecoderTest.kt | 2 → 4 | Retain normal/short interval cases. Add odd intervals, exact last-target boundary and invalid duration/interval controls. Only the target scheduler is tested; actual decoding remains outside this suite. |
| SourceDiversitySummaryTest.kt | 5 → 6 | Check missing counts/scene peak. Add custom thresholds, excluded unreliable gestures/weak faces and independently calculated ranges. Preserve distinction between unknown and measured zero. |
| StylePickerSelectionTest.kt | 6 → 6 | Replace redundant static two-source catalogue case with actual DUALITY→Heartbeat state transitions, rejected Sigma selection and music association. Strengthen instance/input immutability, remove repeated scrolling assertions owned by catalogue tests, and cover FEAR audio refusal for DUALITY. |
| VisualEventMapAnalyzerTest.kt | 5 → 6 | Require exact semantic peaks and all hold timestamps rather than broad existence/minimum assertions. Pair the noisy-camera negative with exactly enough real population support, and add shuffled input ordering before face-turn analysis. |
| TemporalDecodedEvidenceTest.kt | concurrent 13 → 14 | Read all concurrent new temporal contracts: both kinds, missing/negative/equal decoded PTS, decoder presence, source cadence, inclusive visibility threshold, requested separation, non-temporal/reference exemption, debug binding and referential texture identity, absent secondary collection and policy metadata. Added mixed valid/invalid sequence aggregation in both orders with an all-valid control. Synthetic shader evidence does not execute texture sampling. |

The twelve original files contained 64 methods; they now contain 75. New methods
cover independent boundaries or state transitions, rather than replaying the
implementation. Their execution and deliberate-mutation evidence are recorded
in the overall review report.

## Boundaries

- Passing pure copy/catalogue tests cannot demonstrate that a dialog renders,
  receives touch, rotates or restores an Activity correctly.
- The cache reader's tests cover headers, byte geometry, records, incomplete
  pixel bodies and truncated final timestamps. They do not validate Android
  provider IPC or actual ML inference.
- The `HeartbeatCachedPoolProbe.kt` command-line diagnostic is not a JUnit test
  and remains excluded from test counts. It uses real local cache files, which
  cannot be assumed to exist on a clean CI checkout.

## Concurrent additions

`TemporalDecodedEvidenceTest.kt` appeared in this working copy during the review.
It is included in the final inventory and reviewed separately with its current
production contract. Final counts therefore include user-owned parallel additions
as well as this review's test consolidation and improvements.

## Real application defect fixed

`MulticlassMatteClient.read` previously treated every `EOFException` during the
next timestamp read as a normal end of file. A valid frame plus 1..7 trailing
bytes was therefore accepted. The new regression failed with an assertion before
the fix (`artifacts/testing/review-20260927/cache-truncation-before-fix.log`).
The parser now stops only when there are no bytes before starting a record;
partial timestamp/geometry/pixels propagate an error. Valid complete caches and
the optional empty-cache policy retain their existing behavior.
