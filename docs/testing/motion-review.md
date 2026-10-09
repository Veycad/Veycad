# Motion and perception test review

Reviewed on 2026-09-27. Scope: all 32 assigned JVM test files for motion, perception, semantic masks, frame evidence, source profiles and clocks. Reviewed production implementations alongside the fixtures. Existing working-tree changes were retained; production files were not edited.

The scope had 208 JUnit methods before this review and has 200 afterward. This is a count of methods, not a count of fixture rows or assertions. Thirteen near-identical version-header tests were replaced by one labeled matrix, and the separate version-17 test was moved into that same matrix. Five focused methods were added. All previously tested cache versions remain covered.

## Findings and changes

- Cache refusal tests wrote only magic/version. If a broken reader accepted an invalid header but subsequently threw `IllegalArgumentException` on another field, the exception-based test could incorrectly pass. The replacement starts with a complete valid round-tripped payload and changes only its version or magic. It rejects versions 4..17, zero, negative, 19 and `Int.MAX_VALUE`. Each matrix row identifies the rejected version.
- The semantic probe previously calculated expected confidence with the same production `SemanticMaskMetrics.stats` that the probe called. Replaced that expectation with hand-calculated confidence 0.94, separation 0.8 and mean 0.5; also checked geometry.
- The zero-radius mask test previously compared two production methods that could share the same projection error. Both now must match an independently calculated native pixel-center sample; the patch-minimum method is also checked. Added a patch-edge and invalid-bounds test.
- Static dense optical flow previously checked only averages and aggregate confidence. Now every vector must be zero and the complete vector buffer must be present, detecting cancellation of opposite nonzero vectors.
- The depth test previously required only ordering. It now checks exact subject/background depths, confidence, geometry and input immutability.
- Camera consensus now checks both axes/signs and has explicit minimum-witness, confidence, mask-support and quorum tests. The subject scalar now checks vertical camera compensation and independently calculated confidence/area weighting, including unmatched person mass.
- The rotated smooth-clothing test could pass each query-shape assertion with no reliable queries. It now requires reliable queries for every shape and moving queries for the two shapes which this independently constructed fixture supports. The vertical elongated footprint legitimately has only static torso support; it is not forced to invent limb correspondence.
- The split-brightness fixture used conditional assertions and could pass with no measurement. Its textured interiors must now produce a measured static result. The partial-occlusion fixture still permits unknown whole-body support, but must reach body matching and retain the independently supported static camera.
- Geometry dimensions now use explicit expected sizes, rather than recomputing the production resize formula. Constant-resize tests also assert output geometry, avoiding vacuous success on an empty output.
- Reused a prepared frame pair across the rotated fixture's query shapes. The 14-preparation allocation comparison sums sample counts without retaining 14 large snapshots simultaneously. No wall-clock limits were added.
- Added invalid alignment matrices for empty, negative, reordered and duplicated plans/video timestamps.

## Per-file record

All paths below are relative to `app/src/test/java/com/example/autoedit/`.

| File | Methods before → after | Decision and evidence |
| --- | ---: | --- |
| CameraMotionConsensusTest.kt | 6 → 8 | Expanded `coherent_pan_is_measured` to signed/vertical/diagonal motion. Added `minimum_witness_and_confidence_boundaries_require_distributed_background` and `a_consensus_requires_seventy_percent_of_background_confidence_mass`. Existing person, conflicting, local-patch and weak-confidence negatives retained. |
| IndependentCameraMeasurementTest.kt | 2 → 2 | Replaced production-equivalent `hypot` expectation with fixed 0.36055513. All malformed confidence/spatial/clock/method cases retained. |
| FullBodyMotionDiagnosticTest.kt | 6 → 6 | Partial-occlusion test now checks independent camera and reached body stage; reuses the same scenes for assessment/local matching. Retained opposed, same-direction, flat-background, lighting and camera-only controls because they exercise different causal failures. |
| LocalizedLimbMotionDiagnosticTest.kt | 2 → 2 | Retained. Ground truth comes from native part displacement/area, with three sampling phases and camera-only control. |
| OpposedMotionDiagnosticTest.kt | 3 → 3 | Retained. Explicit legacy-centroid counterexample versus local flow, single-object control and unchanged control; these are distinct algorithm boundaries. |
| SmoothClothingMotionDiagnosticTest.kt | 5 → 5 | Strengthened `rotated_smooth_limbs_use_physical_axis_units_and_both_query_orientations`; prepared-pair reuse. Retained two texture spacings, three phases, both rotations, photometric/camera controls and periodic ambiguity. |
| SubpixelMotionDiagnosticTest.kt | 6 → 6 | Retained. Analytic independent translations cover fractional axes/signs, static/light controls, independently supported camera and repeating ambiguity. |
| PhotometricMotionDiagnosticTest.kt | 4 → 4 | Strengthened `unrelated_local_brightness_changes_cannot_supply_a_coherent_false_pan` to require measured zero. Other gain/noise fixtures retained. |
| MotionAnalysisGeometryTest.kt | 6 → 6 | Explicit resize-size oracle. Retained four densities/aspects and below-threshold Fear integration because unit normalization defects can differ by axis and resolution. |
| MotionPreparationReuseTest.kt | 11 → 11 | Reduced retained allocations in `fourteen_shape_calls_reuse_two_planes_instead_of_twenty_eight`. Historical oracle retained; it is separate code, plus independent physical fixtures elsewhere. Opposing directions, immutable snapshots, memo/reset and allocation counters cover distinct optimization contracts. |
| SubjectMotionIntensityTest.kt | 6 → 7 | Added independently calculated confidence/area/unmatched-mass test and vertical camera subtraction. Existing opposite-direction and missing-support tests retained. |
| LocalMotionCorrespondenceTest.kt | 10 → 10 | Retained. Known translations and zero controls have nonempty support guards; border/tiny frames test unique physical centers; flat/noise/repeating patterns are distinct refusal causes. |
| LumaAreaResamplerTest.kt | 6 → 6 | Constant resize now checks dimensions/payload size. Retained checkerboard/global-mean tests and the explicit point-sampling counterexample versus production evidence integration. |
| LumaMotionEstimatorTest.kt | 4 → 4 | Retained. Tests the editorial heuristic's broad behavioral contracts, not exact physical motion. Pan, localized change, occlusion and unrelated-shot boundary are distinct. |
| DenseOpticalFlowEstimatorTest.kt | 3 → 3 | Strengthened `static_frame_has_zero_confidence_and_flow` with all-vector and complete-grid assertions. Retained positive translation and first-frame refusal. |
| PersonLocalCorrespondenceTest.kt | 4 → 4 | Retained. Independent pixel-mass conservation, disjoint-witness refusal, foreground-only noise and independent camera surviving insufficient person coverage. |
| PersonMaskProjectionTest.kt | 6 → 7 | Independent fixed center value in zero-radius test; added `patch_minimum_includes_a_background_edge_and_rejects_out_of_frame_queries`. Existing affine field, boundary and anisotropic tests retained. |
| PersonMaskUnionTest.kt | 4 → 4 | Retained. Accessory enclosure versus weak border attachment, wide curtain counterexample and supported cropped accessory have independent pixel assertions. |
| PersonMotionEvidenceTest.kt | 10 → 10 | Retained. Covers raw/production/audit APIs separately: synthetic pixels, actual timestamps, stale semantics, independent-camera gates and support counters. Equality across wrappers supplements fixed-zero/support assertions. |
| PoseWristEvidenceTest.kt | 6 → 6 | Retained. Availability, identity, reversed iteration, missing previous wrist, gap and 3–4–5 displacement are independent cases. |
| PoseDepthMaskShapeTest.kt | 1 → 1 | Retained. Explicit depth values, portrait/landscape/square geometry and input non-aliasing. |
| SemanticMaskMetricsTest.kt | 12 → 12 | Retained. Fixed mask geometry/coverage, IoU identity and displacement, leak/registration, morphology, guided edge and renderer envelope integration cover separate contracts. |
| SemanticMaskEvidenceProbeTest.kt | 5 → 5 | Replaced `confidence_uses_existing_semantic_metrics_without_new_thresholds` with `confidence_separation_and_raw_summary_match_hand_calculated_values`; boundary/nonfinite/geometry negatives retained. |
| SemanticMonocularDepthEstimatorTest.kt | 1 → 1 | Expanded the existing method with exact independent values and immutability. |
| OpacityMaskTest.kt | 4 → 4 | Retained. Mixed opacity/confidence refuses blending; fractional endpoints/progress, default confidence semantics and invalid depth-only opacity are separate cases. |
| ForegroundRevealContinuityTest.kt | 2 → 2 | Retained. Endpoint/overshoot and last-frame jump versus monotonic interior/settled subject are complementary. |
| OpeningMatteRefinementTest.kt | 12 → 12 | Retained. Decoder clock, ceiling alignment, supported face-hole repair versus exterior rejection, bounded entrance/finale coverage, interpolation preservation and missing masks. Frame-plan-derived targets are an intentional integration contract with separate bounded/endpoint assertions, not independent visual truth. |
| SourceFramingTest.kt | 4 → 4 | Retained. Aspect/crop invariants and exact projected pixels; rotated circle has independently specified expected position/physical shape. |
| MediaFrameVisualAnalyzerTest.kt | 11 → 11 | Retained dirty work. Fresh-frame failure, measured-empty versus inference-failure, gap events, product evidence and independent-camera attachment all exercise different evidence contracts. |
| MediaFrameAnalysisCacheTest.kt | 14 → 2 | Consolidated 13 header-version methods into `incompatible_versions_cannot_supply_current_evidence_even_with_a_complete_payload`. Preserved `exactRoundTripKeepsDirectorAndRendererEvidence` and all existing rich observation/attachment fields. |
| SourceAnalysisProfileTest.kt | 13 → 12 | Removed only `older_cache_without_profile_is_not_migrated_to_full`: version17 is now covered by the complete-payload cache matrix. Retained lazy-work counters, capabilities, cache-profile/count corruption and expensive non-Fear graph equivalence regression. |
| SourceAnalysisTimelineTest.kt | 19 → 20 | Added `invalid_plans_and_negative_or_duplicate_video_pts_are_rejected_before_alignment`. Existing exact-versus-approximate duration boundaries and successful Unit-returning validation methods are legitimate paired positive/negative contract tests. |

The removed cache methods were `v16CoupledCameraCacheCannotSupplyIndependentCameraEvidence`, `v15CarriedSemanticCacheCannotSupplyFreshFrameEvidence`, `fixedLocalV14CacheCannotSupplyAdaptiveContextEvidence`, `coarseBodyV13CacheCannotSupplyLocalBodyEvidence`, `offsetOnlyV12CacheCannotSupplyAffineBrightnessEvidence`, `centerOnlyBackgroundV11CacheCannotSupplyQueryFootprintEvidence`, `fixedDistortedV10PlaneCannotSupplyFovGeometryEvidence`, `edgeIndexedMaskV9CacheCannotSupplyPixelCenteredEvidence`, `directPointResizeV8CacheCannotSupplyAreaIntegratedEvidence`, `integerOnlyV7CacheCannotSupplySubpixelEvidence`, `v6DuplicateBorderSupportCannotSupplyUniquePatchEvidence`, `v5CacheWithoutCorrespondenceScalarCannotSupplyMotionEvidence` and `oldSyncFallbackCacheCannotBeReadAsActualPtsEvidence`.

## Mutation checks that should expose real defects

These are proposed mutations in an isolated copied/compiled test target, not changes to production files. Do not infer mutation coverage from an unexecuted proposal.

| Deliberate defect | Test expected to fail |
| --- | --- |
| Accept any cache version; skip magic check | New complete-payload version/magic matrix. Unlike incomplete-header fixtures, it has no later parse failure to disguise header acceptance. |
| Change mask statistics confidence/separation while probe still calls those same statistics | Hand-calculated probe test. |
| Use edge-index coordinates instead of pixel centers in all projection methods | Fixed zero-radius value and affine/half-person-boundary projection tests. |
| Return nonzero static flow vectors with zero averages/confidence | New all-vector static dense-flow assertion. |
| Ignore vertical camera displacement | Expanded consensus/subject-camera tests and subpixel/FOV physics tests. |
| Average subject directions before modulus | Opposed-direction scalar, pixel-correspondence and full-body fixtures. |
| Ignore pixel area/confidence or omit unmatched person mass | New `.56` intensity / `.5` support-fraction test. |
| Lower minimum camera witnesses to7, accept sub-.5 confidence, or accept person support above .15 | Exact threshold/witness test. |
| Lower camera quorum to .68 or omit it | Twelve-versus-eleven agreeing-cell matrix. |
| Return no reliable query matches for a rotated shape | New nonempty shape guards; moving-support guards cover applicable orientations. |
| Return unknown for all textured photometric cases | Measured-zero controls, including the strengthened split-gain fixture. |
| Return a constant depth pair that preserves only ordering | Exact depth/background/confidence values. |
| Alias prepared snapshots or leak footprint-specific memo state | Existing immutable snapshot, dense historical-oracle and footprint-reset tests. |
| Accept duplicated/reordered/negative alignment requests | New invalid alignment matrix plus existing exact-clock negative tests. |

## Limits

These are synthetic numeric/image JVM tests. They do not execute ML Kit/MediaPipe models, Android codec/GLES rendering, the actual app UI or genuine human perception. The historical motion oracle checks preservation of earlier behavior and cannot independently establish that earlier behavior was physically correct. Analytic/translated/native-shape fixtures supply the independent counterexamples alongside it. Device/real-video quality evidence is a separate verification layer.

Selected execution of all 16 changed classes completed successfully on 2026-09-27: 105 tests, zero failures/errors/skips. Gradle completed in 1m35s including compilation; summed test-suite times were 38.27s. The strengthened split-brightness and nonempty rotated-query fixtures passed. No claim of measured overall speedup is made from allocation reductions alone; timings vary with compilation, JVM warmup and host load. The root task owns the subsequent full-suite verification.
