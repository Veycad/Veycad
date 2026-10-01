# Per-estimate candidate normalization memo

2026-09-27. This change removes repeated photometric normalization inside the
existing exhaustive local matcher. It does not change the motion model, source
requirements, thresholds, patch shapes, identity assumptions or product grammar.
No holdout or user media is used for implementation or tests.

## Evidence and scope

The disclosed v16 calibration took 382999 ms total, including 9425 ms of semantic
work and 343678 ms of motion work. Its 121 observations yielded only two measured
subject-motion points. Previous pair preparation/offset reuse did not demonstrate
a device runtime improvement. Those historical timings do NOT prove this memo is
faster, and do not establish independent montage quality.

For the actual 144x81 plane, sampling is 3x1.125. Quarter-search steps are 36x13,
giving 73*27 = 1971 hypotheses for each textured forward or backward query. All
11 elongated physical footprints at this geometry are distinct; shape deduplication
does not remove these searches.

Previously, every candidate recomputed two source-only statistics: its mean and
mean-centered absolute texture. Dense nearby query centers repeatedly visit the
same absolute candidate patches. Those statistics depend only on the immutable
source quarter plane, absolute candidate base and this invocation's exact ordered
patch offsets. They do not depend on the query's texture or motion hypothesis name.

The implementation lazily stores mean, texture and a validity bit in primitive
arrays, indexed by absolute candidate base. Previous/current source directions
have separate caches. Both caches belong to ONE `estimate` invocation and its
ONE footprint, and become unreachable when the invocation returns. `PreparedPair`
remains immutable and retains no normalization cache. There is no source/global,
cross-frame or cross-footprint reuse.

## Arithmetic and search contract

Candidate means/textures retain the original row-major Float additions and Float
division. Summed-area tables, altered accumulation, approximate statistics and
candidate pruning are not used. Query mean/texture remain their original Double
accumulations (`average` and `sumOf`) and never use candidate statistics.

For every hypothesis, gain is still calculated from that query's texture and the
candidate texture. The centered candidate value is the same Float subtraction
`source[base + offset] - candidateMean` that previously populated the temporary
values array. The gain multiply, absolute residual and ordered Float residual sum
are unchanged. Candidate texture/gain refusal, exhaustive hypotheses, tie traversal,
second-basin search, forward/backward match, confidence, normalized vectors and
patch radii are unchanged. The temporary per-match candidate values array is no
longer needed.

## Structural cost and tradeoff

One 144x81 quarter plane has 573*321 = 183933 samples. For a textured invocation,
two direction caches contain four FloatArrays and two BooleanArrays. Their raw
array payload is `183933 * (4*4 + 2) = 3310794` bytes, approximately 3.16 MiB,
excluding object headers/alignment. Flat queries and empty center lists allocate
no normalization arrays. Candidate errors still retain the old exhaustive error
arrays. No cache accumulates across all 11 shapes in one observation.

Cache hits avoid the two source normalization scans, but still load centered
candidate samples for residuals. Cache misses, array allocation/collection,
memory traffic, coarse camera work and all matching residuals still cost time.
The disclosed motion timer does not separate coarse and dense body branches.
Therefore neither a wall-clock speedup nor a percentage reduction in full-device
runtime is claimed. Device timing must use a newly built, frozen APK and disclosed
calibration sources, not infer the current source change from an earlier APK run.

## Verification to run

`MotionPreparationReuseTest` retains its independent complete pre-refactor oracle
unchanged. New tests compare every ordered Cell field, with raw-bit equality for
Float fields, across nine adjacent interior centers plus duplicate/border centers,
64x64 / 144x81 / 81x144 geometry, multiple footprints, swapped source directions,
subpixel translations, contrast gain, lighting offset and unrelated noise.
Additional controls cover flat/periodic ambiguous basins, footprint-call reset,
immutable snapshots after input mutation and absence of tables for flat/empty queries.

A caller-owned `NormalizationAudit` records only structural previous/current
hit/miss counts and allocated array payload; it is optional and never retained.
The production-geometry fixture checks all 9*1971 hypotheses per direction still
occur, both caches have hits and misses, misses recur on a fresh invocation, and
array payload is exactly 3310794 bytes. These assertions are not elapsed-time
benchmarks or physical-motion ground truth.

This document records implementation and required verification, not a completed
build/test/device result. Root must run the regression suite and exact-PTS frozen
APK replay before claiming compatibility or runtime benefit. The two low-intensity
FEAR points, missing independent positive cases and mandatory human acceptance
remain unresolved by this semantics-preserving cost change.

## Root verification and next frozen candidate

Targeted `MotionPreparationReuseTest`, then full `testDebugUnitTest assembleDebug`,
completed successfully. The complete current XML set contains469 tests in68
suites, failures/errors/skipped0. All103 Python quality tests also passed.
Universal APK SHA-256:
`56c108935a6a339eca51d71be49716daa63b4b784a2593621a8ee8d4bd70e4db`.
This APK is not the installed frozen v17 APK and has not been timed on-device.
Raw-bit synthetic comparisons establish regression controls, not real accuracy.

Before installation, reserve a distinct calibration artifact:
`calibration_v18_motion_memo_probe_20260927.json[.result]`, using the completed
Coconut cache17 semantics and newly decoded pixels. The cache version remains17:
the memo changes evaluation cost only, not serialized semantics. It must preserve
both camera and whole-body measurements at every actual PTS, including nulls.
The frozen v17 probe name is not reused for a different APK. Archive source, tests,
APK and reports before installation. Never install or launch over the live
predeclared v17 Heartbeat worker. A replay match is not a holdout or human pass;
elapsed-time observations are not a controlled speed benchmark.

Archive was subsequently created before further debug diagnostic edits:
`artifacts/quality/baseline-v18-normalization-memo-20260927.tar`, SHA-256
`8729005ddac2ea5fef04acc5c8b021eb6d6ff3bc048fd26e8a9dafe13e32e6ca`.
The predeclared probe has not been run and this candidate has not been installed.
