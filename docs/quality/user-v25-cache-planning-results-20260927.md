# v25 Heartbeat: registered-cache planning diagnostics, 2026-09-27

## Scope and verdict

Both explicit JVM diagnostics passed their **planning** contract. A preserved the original preferred pool and the complete graph exactly, without entering the alternate domain. C reproduced the original typed refusal, then obtained an accepted complete graph from measured alternate windows, without changing the echo offset or disabling handle/profile checks.

These are cache-derived planning diagnostics only. They did not decode or render the source videos, measure exported AAC, inspect native pixels, or perform human review. The 1,270 frames below are scheduler entries, not a claim that 1,270 frames were actually encoded or decoded. Each JSON explicitly records `native_render_acceptance`, `audio_acceptance`, and `human_acceptance` as `NOT_ASSESSED`. No v25 native MP4 is established by this document. After the diagnostics, the full build completed successfully in 1 minute 14 seconds with `assembleDebug`, `lintDebug` and `verifySecurityContract` passing; `testDebugUnitTest` was up-to-date against the actual full test run described below. APK byte identity and native results are separate evidence.

The original four-product objective remains incomplete. Sigma alone remains paused/locked; Heartbeat, FEAR and DUALITY remain active but not release-ready. The current authorized set remains three exposed pilots A/B/C and zero unused holdout sources. Neither cache nor derivative restores unused status. No independent release-matrix case is added, and existing owner approval of v23 Heartbeat(A)/FEAR(C) is not transferred to these new planning graphs.

## Immutable inputs and results

Paths are relative to the repository root `C:/Users/rexar/Documents/Codex/AutoEdit` in this evidence inventory.

| Evidence | Bytes | SHA-256 |
| --- | ---: | --- |
| A cache: `artifacts/quality/runs/v25a-diagnostic-v18-editorial-semantics-v1-250000-8e65b237e48e8fd4b3d50079c85c64034fb592f5881a389deaa9485dc43aafca.bin.gz` | 13,425,524 | `31abb3fcd6f3cf067fd87bee76d0b9449cf83d5c62524219551cf7c4da6f870a` |
| C cache: `artifacts/quality/runs/v24c-diagnostic-v18-editorial-semantics-v1-250000-0e2d39036a6eeb520196905a04d6d4fa1385a09e80124d98f3c4a3faf48e9c23.bin.gz` | 18,564,764 | `21a2e6d655d46f9588aeff64074cca98bff547cb8a4324cc557c6b86880aa70f` |
| A diagnostic: `artifacts/quality/runs/user_heartbeat_v25_a_cacheplanning_minus67_20260927.json` | 15,437 | `07b817a9c923191f89288402beaebd3db07ad1b2a89d1398fa9b558bd8dde385` |
| C diagnostic: `artifacts/quality/runs/user_heartbeat_v25_c_cacheplanning_minus67_20260927.json` | 11,335 | `c8cfdbd6081bc25434a5a585a1769980732c59f0fb8824b63ea9201a4671012f` |

The registered source identities are A=`19348088228456.mp4`, source SHA `8e65b237e48e8fd4b3d50079c85c64034fb592f5881a389deaa9485dc43aafca`, and C=`IMG_2249.MOV`, source SHA `0e2d39036a6eeb520196905a04d6d4fa1385a09e80124d98f3c4a3faf48e9c23`. The diagnostic's source SHA is explicitly attributed to the registered cache key; this JVM invocation hashes the cache, not the original video again.

The production `MediaFrameAnalysisCache.read` verified schema 18 and requested profile `editorial-semantics-v1`. A supplied 112 observations and 112 attachments over 27,989,000 us; C supplied 119 observations and 119 attachments over 29,700,000 us. Both have zero completed correspondence assessments and state `NOT_REQUESTED`, which is not measured static motion and not a failed correspondence attempt. The real `VisualEventMapAnalyzer` reconstructed events from these observations. No invented movement, interpolated source observations, or new source analysis was used.

## Production path and unchanged options

The standalone `app/src/test/java/com/example/autoedit/HeartbeatCachedPoolProbe.kt` has no `@Test`, no JUnit invocation, and no default-test dependency on these ignored cache artifacts. It is called explicitly through `artifacts/quality/scripts/heartbeat-cache-probe.init.gradle`.

For each cache it first called the original `HeartbeatSourcePool.select` and full `HeartbeatDirector.build`, then called production `HeartbeatSourcePool.direct`. No substitute `director` callback was supplied. The default callback in `ProductSourcePools.kt` calls the complete director with the actual visual map, `echoOffsetMs=-67`, `allowShiftedHandles=true`, and `allowProfileGap=true`, plus cooperative cancellation. The negative offset is unchanged; no synchronous-echo control, legacy-effect control, weakened extension threshold, or disabled profile-gap rule was used.

The director reserves live handles after composition assignment, evaluates its existing distinct interlude, applies the actual finale mapping, and checks the complete scheduled echo footprint. The diagnostic independently checks the resulting graph using `HighQualityFramePlan.build(graph, 60)`. Every live clip must have equal source/output duration, all speed-ramp keyframes at 1, and no speed parameter track. It checks the first 15 primary intervals for intersections, the product-specific repeated-source ratio, and every requested temporal source PTS before clamping against `[0, sourceDurationUs)`.

Output uses `CREATE_NEW`: an existing JSON cannot be overwritten. A's successful bytes were preserved throughout launcher repairs. The search domain is finite measured anchors; successful acceptance here does not claim exhaustive search of every physical moment.

## A: exact preferred-path preservation

Both the separately computed old pool and old complete graph equal the objects returned by production `direct`. The JSON records `exact_preferred_pool_and_graph_preserved=true` and `preferred_trace_no_fallback=true`.

| Trace field | A |
| --- | ---: |
| method | `preferred-pool` |
| generated candidates | 0 |
| candidate count | 0 |
| charged candidate visits | 0 |
| complete director validations | 1 |
| finite domain exhausted | false |
| configured candidate-visit limit | 2,000,000 |
| configured complete-director limit | 64 |

All 15 pool windows retain their own 2–5 actual observations. All 27 clip windows and all graph data are retained exactly relative to the separately executed current preferred director path; the JSON contains both inventories. This is not byte-identity of a future encoded MP4 and does not transfer the owner's v23 approval to a new export.

## C: actual measured alternatives, not a boundary extension

The old pool again failed with `insufficient_distinct_moments`, detail `Heartbeat needs disjoint readable handles for every measured live role and finale`. The new complete selection passed with method `measured-pair-joint-v1`.

| Trace field | C |
| --- | ---: |
| generated candidates, shared duration domains | 2,794 |
| candidate count, expanded role domains after necessary filtering | 3,260 |
| charged candidate visits | 83,695 |
| complete director validations, including the preferred attempt | 2 |
| finite domain exhausted | false |
| configured candidate-visit limit | 2,000,000 |
| configured complete-director limit | 64 |

Only pool roles 0, 2 and 7 changed. The other 12 pool ranges are identical to the old pool. This actual C solution therefore is not a single-window substitution, nor an extension of the old `[0,450)` role-7 boundary window. Candidate counts are window/domain counts, not counts of new measured source frames.

The trace counts only validations inside production `direct`. The diagnostic separately invokes the old full director once for comparison; that extra reproduction is not included in `director_validations=2`.

| Pool role | Old half-open source range, ms | New half-open source range, ms | New actual source PTS, us |
| --- | --- | --- | --- |
| 0 | `[25308,25958)` | `[23941,24591)` | 24,133,333; 24,400,000 |
| 2 | `[24175,25091)` | `[24808,25724)` | 24,900,000; 25,133,333; 25,400,000; 25,633,333 |
| 7 | `[0,450)` | `[25791,26241)` | 25,900,000; 26,133,333 |

The other new pool ranges are roles 1=`[13575,14692)`, 3=`[22608,23658)`, 4=`[9334,10467)`, 5=`[26650,27617)`, 6=`[12642,13159)`, 8=`[11342,12459)`, 9=`[5650,6150)`, 10=`[14875,15392)`, 11=`[29300,29700)`, 12=`[4667,5600)`, 13=`[3392,4409)`, and 14=`[1583,2683)` ms. Every selected pool range has at least two actual observations, listed in the JSON. Range membership uses the same millisecond flooring and half-open convention as production selection.

The full director also chose its existing optional measured distinct-interlude role 15 for clips 4/15, with live range `[217,717)` ms. Its longer interlude candidate is separately checked by the director's existing no-face/quality/occlusion/distinctness rules before trimming. The original 15-row source pool does not contain this extra director insert; the complete graph does. The finale maps to role 11 with actual live range `[28567,29700)` ms, rather than pretending that the pool's 400 ms selection itself supplies a 1,133 ms finale without validated handles.

### Complete accepted C clip inventory

All rows use source index 0. Rows 0–25 are the authored live scenes. Row 26 is the authored black tail and is not claimed to be a live 1× scene.

| Clip | Selected source role | Source range, ms | Output duration, ms |
| ---: | ---: | --- | ---: |
| 0 | 0 | `[23941,24591)` | 650 |
| 1 | 1 | `[13575,14692)` | 1,117 |
| 2 | 2 | `[24808,25724)` | 916 |
| 3 | 14 | `[1608,2658)` | 1,050 |
| 4 | 15, distinct interlude | `[217,717)` | 500 |
| 5 | 3 | `[22650,23617)` | 967 |
| 6 | 6 | `[12642,13159)` | 517 |
| 7 | 5 | `[26908,27358)` | 450 |
| 8 | 8 | `[11342,12458)` | 1,116 |
| 9 | 9 | `[5650,6150)` | 500 |
| 10 | 10 | `[14875,15392)` | 517 |
| 11 | 7 | `[25816,26216)` | 400 |
| 12 | 12 | `[4667,5600)` | 933 |
| 13 | 13 | `[3392,4409)` | 1,017 |
| 14 | 4 | `[9350,10450)` | 1,100 |
| 15 | 15, distinct interlude | `[217,717)` | 500 |
| 16 | 3 | `[22650,23617)` | 967 |
| 17 | 6 | `[12642,13158)` | 516 |
| 18 | 5 | `[26908,27358)` | 450 |
| 19 | 8 | `[11342,12459)` | 1,117 |
| 20 | 9 | `[5650,6150)` | 500 |
| 21 | 10 | `[14875,15392)` | 517 |
| 22 | 7 | `[25816,26216)` | 400 |
| 23 | 12 | `[4667,5600)` | 933 |
| 24 | 13 | `[3392,4409)` | 1,017 |
| 25, finale | 11 | `[28567,29700)` | 1,133 |
| 26, black tail | copied finale handle | `[28567,29700)` | 1,366 |

## Planning clocks and bounds

| Planned measurement | A | C |
| --- | ---: | ---: |
| Complete graph clips | 27 | 27 |
| Live scenes at source 1× | 26/26 | 26/26 |
| Graph duration, ms | 21,166 | 21,166 |
| Scheduled frames at 60 fps | 1,270 | 1,270 |
| First-15 primary interval intersections | 0 | 0 |
| Product-specific repeated-source ratio | 0 | 0 |
| Echo scheduler entries checked | 467 | 467 |
| Minimum requested secondary source PTS, us | 0 | 150,000 |
| Maximum requested secondary source PTS, us | 26,803,667 | 29,583,000 |
| Requested echo PTS outside source bounds | 0 | 0 |
| Requested echo-offset mismatches | 0 | 0 |
| Diagnostic planning elapsed, ms | 720 | 484 |

Elapsed times are individual JVM observations encompassing the diagnostic's old/new planning paths after initial cache loading and preferred-pool construction, not a native analysis/render benchmark or a performance promise. Positive `+67` controls were not run by these two diagnostics. Actual decoded texture separation, AV timing, AAC headroom, rendered human readability and subjective acceptance remain unassessed for v25.

## Launcher history and test honesty

1. The first A launcher unexpectedly scheduled `:app:testDebugUnitTest` because a `TaskProvider.map` dependency retained the producing Test task. It completed successfully: 565 tests in 75 suites, zero failures/errors/skips, followed by successful A planning. The counts were confirmed from the current XML results; this incidental full JUnit run was not performed by the standalone probe class and does not replace native or human acceptance.
2. A first launcher repair attempted to snapshot the Test task's dependency set. The C invocation failed before diagnostic execution with a project-state-lock error while determining Test dependencies. No C JSON was produced by that attempt.
3. The init script was repaired again to use explicit actual compilation/resource prerequisites only: `compileDebugUnitTestKotlin`, `compileDebugUnitTestJavaWithJavac`, `processDebugUnitTestJavaRes`, `bundleDebugClassesToRuntimeJar`, and `processDebugJavaRes`. Classpath paths are resolved as ordinary files inside the JavaExec action, stripping Test-task producer provenance.
4. The subsequent C invocation completed successfully in 14 seconds and created the new C JSON using `CREATE_NEW`. Its observed task log contained only prerequisites and `:app:inspectHeartbeatCachedSource`, with no `:app:testDebugUnitTest` execution. It was a separate non-overwriting retry after a launcher failure, not a restarted native worker, repeated source analysis, or rewritten earlier result. Native source alternation history is unaffected by JVM cache planning.

Next evidence must come from the root-controlled new APK/native A then C runs and their actual rendered execution evidence, independent audio measurement, and fresh human forms. Successful planning does not pre-fill any of those gates.
