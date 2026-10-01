# Montage, audio and render-contract test review

Scope: all 31 assigned main unit-test files plus `src/testDebug/.../ExternalMatteRenderProbeTest.kt` were read, with the production contracts used by their assertions. Review applies to the existing working tree, including pre-existing uncommitted changes. No production source was changed by this review.

## Main findings and changes

- **Shared expectations hid shared bugs.** Heartbeat grade tests calculated their expected exposure with the production exposure helper; tail tests constructed every decoded timestamp with the production scheduler. Grade assertions now apply the documented grade to source luminance and assert the intended target. The tail fixture now independently specifies its 84 reference timestamps. Audio headroom asserts the 1 dB contract independently of `TARGET_PEAK`. PCM tests assert actual encoded short values and independently authored raw little-endian bytes as well as their round trip.
- **Duplicates rebuilt expensive scenarios.** Three Duality moving-edit tests rebuilt the same 120-observation montage four times. They are merged into one scenario with all previous ramp/effect/clock assertions and stronger music-snap assertions; only the baseline and music-adjusted montage are now built. Every eligible cue must snap; every cue inside a protected cut margin must preserve the original motion peak. Two duplicate acceptance methods were removed with their assertions retained in the corresponding accepted-render and required-mask tests. One repeated positive black-block control assertion was removed; its first occurrence remains.
- **Weak assertions did not prove their test names.** Reversed Duality import order now checks both clip counts, roles, durations and transitions in addition to source identity/time. Render-pass checks now cover exact pass kinds, dependencies and order rather than existence alone. The “deterministic” effect test now samples forward/reverse order and checks exact glow values. Plane equality now distinguishes different content and uses four cells instead of 49,152 cells, since equality and primitive-storage semantics are independent of image size.
- **Missing negative paths were filled.** Live transition tests now cover a partially consumed clip, preserving its remainder, source-end clamping, absent previous frames and absent transition. Mask evidence covers invalid values in either input, buffer mismatches, the half-opacity boundary and precedence of missing source evidence. FEAR's audit now tests a complete independently constructed 34-interval pulse fixture and removes/corrupts each required interval. Candidate selection now checks that the product fallback API preserves accepted candidates and its quality-gate flag across orderings.
- **Profile constants needed independent expectations.** The complete Sigma cut and accent timelines are now compared with explicit reference values. Previously only counts, endpoint values and increasing order were asserted.
- **Claimed cooldown/budget coverage used one onset.** The flash fixture now includes 899/900 ms same-effect boundaries and enough eligible onsets to exceed the seven-per-ten-second cap. A second mixed blackout/flash fixture checks the global cooldown at 219/220 ms. Render capability checks now include the BALANCED branch and its distinct intermediate resolutions. Heartbeat's complete 26-pulse reference clock/kinds are independently pinned.
- **GPU source contracts are explicitly named.** Three shader-source methods were renamed to say `shader_source_contract`; valid expression and branch guards remain. Their names and this report no longer suggest those checks execute the renderer.

## Deleted or merged methods

| File | Old method | Treatment |
|---|---|---|
| `DualityLoopDirectorTest.kt` | `organic_ramps_change_actual_source_time_not_just_speed_labels` | Replaced by merged `moving_edit_has_live_ramps_sparse_effects_and_music_snap_without_retiming_cuts`; all ramp/clock assertions preserved. |
| `DualityLoopDirectorTest.kt` | `movement_effect_clusters_leave_clean_intervals_and_a_live_finale` | Merged into the same moving scenario; identical production build eliminated. |
| `DualityLoopDirectorTest.kt` | `effect_peaks_can_snap_to_nearby_music_without_changing_cut_grid_or_source_gesture` | Merged into the same scenario; retains a separate second build with music, requires every eligible cue to snap, and every ineligible cue to preserve the original peak. |
| `RenderedMp4AcceptanceTest.kt` | `actual_measured_leak_retains_the_same_hard_gate` | Removed; explicit MEASURED status and rejection assertion retained by `requires_clean_output_mask_evidence_for_foreground_reentry`. |
| `RenderedMp4AcceptanceTest.kt` | `exposes_reference_montage_grammar_as_an_acceptance_metric` | Removed; exact same successful graph/audio/container/sample evaluation already appears in `accepts_render_that_hits_beats_has_visible_transition_and_stable_av`, which now contains both grammar assertions. |

Four new methods cover previously uncovered behavior: `DecodedMaskEvidenceTest.half_opacity_counts_as_measured_person_and_empty_source_takes_precedence`, `LiveDecoderTransitionPlanTest.transition_prefix_is_decoded_once_and_remaining_live_frames_keep_their_clock`, `FearStrobeAuditTest.complete_shutter_and_finale_evidence_fails_when_one_required_interval_is_missing_or_wrong`, and `EffectOrchestratorTest.global_cooldown_applies_between_different_accents_and_allows_its_exact_boundary`. Net change in assigned scope: **unchanged test-method count**, with repeated scenarios removed and new boundary scenarios added.

## Per-file review

All paths below are relative to `app/src/test/java/com/example/autoedit`, except the explicit debug path.

| Test file | Decision and evidence |
|---|---|
| `AudioBeatMapAnalyzerTest.kt` | Strengthened looping for onsets and exclusive endpoint; checked encoded shorts/raw decoded bytes independently and checked zero sample. Existing 120/90 BPM, low/high band and silence cases represent different signal behavior; retained. |
| `AudioExportHeadroomTest.kt` | Replaced self-referential constant expectations with 1 dB level/gain; verified final channel ratio, source peak and negative infinity rejection. Quiet/silent, negative peak, immutability and cancellation cases retained. |
| `BuiltInMusicCatalogTest.kt` | Retained catalog identity, synthesis determinism/audibility/clipping and four authored score routes. These are unit contracts, not proof of audible quality on a device. |
| `DecodedAudioQualityTest.kt` | Retained exact peak/RMS/duration, float overshoot/nonfinite preservation, per-channel same-sign plateau isolation, absent/silent/truncated/clamped evidence. They exercise distinct reporting branches and fail with concrete issue tokens. |
| `DecodedEffectSignatureTest.kt` | Removed one exactly repeated positive black-block assertion. Kept controlled positive/negative pixel patterns, camera-shift versus fragments, flat/repeated/unrelated texture controls, mirror orientation and chroma-only glitch. |
| `DecodedFaceEvidenceTest.kt` | Retained exact timestamp/source/clip freshness, successful zero versus unknown, multi-scale confidence and malformed-result rejection. Cases are distinct freshness or evidence-availability branches. |
| `DecodedMaskEvidenceTest.kt` | Expanded malformed input and source/output availability checks, added 0.5 cutoff and both-empty precedence. |
| `DualityLoopDirectorTest.kt` | Merged expensive identical moving fixtures, strengthened import reversal and full musical snap. Retained window scoring, motion coherence, framing, balance, faceless human evidence, continuity, distinct-source windows, grading and live finale scenarios. |
| `EditDurationPolicyTest.kt` | Retained source minimum, exact short source, strong musical boundary, bounded phrase extension and no-boundary fallback. No duplicate production input found. |
| `EffectOrchestratorTest.kt` | Replaced single-onset cooldown/budget fixture with dense, independently expected 899/900ms same-effect boundaries and seven/10s budget. Added mixed 219/220ms global cooldown. Retained mask-required foreground, opening exception, incoming-mask changes, directional whip, low audio accents and reference fixed vocabulary. |
| `EventMatchingDirectorTest.kt` | Retained generic alternatives, selected-style equivalence, non-overlap under sparse/dense beats, confirmed whip, quiet virtual camera, readable/calm finale and independently scarce/wide/body source roles. Repeated assertions support different material fixtures; not safe to delete solely for sharing invariant assertions. |
| `ExportContainerIntegrityTest.kt` | Retained exact geometry/clock pass, swapped dimensions/rotation, shifted audio/nonmonotonic tracks and absent-track failures. Tests address separate fields of the pure container validator. They do not inspect real muxed MP4 files. |
| `FearCascadeEvidenceTest.kt` | Retained both-phrase contrast triplets, chain-versus-pairwise contrast, confidence, faceless body scale and unknown-versus-measured gesture. Fixture guards distinct evidence contracts. |
| `FearDirectorTest.kt` | Retained exact 549-frame choreography, exact/adaptive coverage, unique source ranges, entry-only effects, zoom acceleration, human opener, source-dependent grading and clean title/cascade selection. Shader string check is explicitly a structural guard. |
| `FearOpeningEvidenceTest.kt` | Retained isolated peaks, sustained half-second run, broken cadence, full body without face, missing versus static measurement, independent camera support, source-pool rejections and double-count prevention. Cases protect material rejection distinctions. |
| `FearStrobeAuditTest.kt` | Strengthened 1 us flooring check to require actual measured/matched pulse. Added positive complete reference plus 34 omitted/wrong interval controls. Existing unexpected black frame case retained. |
| `HeartbeatDirectorTest.kt` | Replaced production exposure-helper expectations with source-to-target grade assertions. Retained handle availability, strict controls, semantic roles, interlude unknown-face guard, source timing, exact pulse frame coverage, finale and layer behavior. Its many shader-substring assertions remain structural source guards and cannot certify pixels. |
| `HeartbeatMontageProfileTest.kt` | Pinned every measured pulse start/end and its white/dark identity independently. Retained asset digest identity, scene and pulse half-open boundaries, target scale/motion, title order and measured light accents. Stable profile data inspection is useful, but does not prove the chosen source or renderer visually matches the author reference. |
| `HeartbeatPulseAuditTest.kt` | Independent literal reference clock fixture and direct scheduler equality added. Missing slots, flooring, off-clock/duplicate/extra images, tail holes and onset tolerance preserved. |
| `HeartbeatReservationTest.kt` | Retained real boundary regression ranges, positive/negative temporal handles, true disjoint reservations, source-role permutation, strict readable extension, union-impossibility and multiplicity/aggregate-overlap regressions. The old archived-overlay reconstruction uses production profile data: it tests conversion/application, not independent profile identity. |
| `HeartbeatSourceFallbackTest.kt` | Retained preferred pass, measured pair membership, half-open boundary/gap checks, viability, alternate search, cancellation, budget versus genuine domain exhaustion and 4,000-observation bounded-work stress. Two complete runs are intentional determinism evidence. |
| `InspectorSelectionEvidenceTest.kt` | Retained exact interval/id output contract, reverse source order and intentional duplicate diagnostics. Assertions include independent concrete times. |
| `LiveDecoderTransitionPlanTest.kt` | Added exact outgoing step sequence and partial-transition/boundary/null-window cases; retained entirely consumed short clip. |
| `ReferenceMontageGrammarTest.kt` | Retained weak/strong vocabulary, complete event card and individually named missing event despite >95% aggregate recall. Production-built positive card exercises grammar consistency; independently pinned profile timeline is in profile tests. |
| `ReferenceMontageProfileTest.kt` | Strengthened complete immutable cut/accent arrays and wrong output-duration rejection. Retained audio-content identity, unrelated audio, minimum source, measured glitch envelope and scene-preserving fade. |
| `RenderedCandidateSelectorTest.kt` | Extended accepted-winner precedence to `chooseBestAvailable` and both orders. Kept matte tie-break, rejection when all fail and honest fallback flag. |
| `RenderedMp4AcceptanceTest.kt` | Removed two equivalent scenarios with assertions merged. Retained independent gate rejection controls for face, mask, audio/container, color/artifacts, sample-clock timing, style-specific grammar/pulses/coverage and stage background. These are metric/report gate tests using fabricated `VisualSample` records. |
| `RenderPassPlannerTest.kt` | Exact topology/dependencies/order replace existence-only checks; reused fixture locally in simple graph case. Added BALANCED boundary capabilities and blur/depth/glow resolutions alongside high/compatibility and foreground-with-depth cases. |
| `SigmaCompositionTest.kt` | Retained monotonic entrance, black versus graded background, authored ghost projection, visible leak filtering, registered travel and role-dependent crop fit. Positive and counterexample arrays are distinct. |
| `VeycadEngineCoreTest.kt` | Optimized primitive plane fixture from 49,152 to 4 cells and added unequal-content control. Deterministic effect sampling now checks exact values and reverse order. Kept timing, speed integration, attachment interpolation, live foreground/stage and temporal source contracts. Shader checks do not execute GPU code. |
| `VeycadExecutionAcceptanceTest.kt` | Retained report issue composition, absent execution evidence, original decoded failures, preserved metrics/audio/reference, artifact fields and stale report identity. These demonstrate honest evidence merging, not real device rendering. |
| `app/src/testDebug/java/com/example/autoedit/ExternalMatteRenderProbeTest.kt` | Retained debug-only replacement interval, nonmutation, duplicate timestamp and empty override rejection. No duplicate or vacuous scenario found. |

## Mutation checks proposed to the coordinator

These are deliberate faults for isolated validation, not requested production edits. Method names may have Kotlin's internal module suffix in bytecode.

| Production class/method | Deliberate fault | Test expected to fail |
|---|---|---|
| `HeartbeatDirector.exposureDeltaForTargetLuma` | Return zero or use gamma 1.0 | `HeartbeatDirectorTest.finale_grade_adapts_to_selected_source_luma_and_drops_after_dark_punctuation`, `ordinary_scene_grade_uses_its_measured_reference_luma_without_replacing_accent_curves` |
| `HeartbeatPulseAudit.tailSamplingTargetsUs` | Shift each generated PTS by 2 us | `HeartbeatPulseAuditTest.current_profile_tail_measures_every_end_exclusive_frame_and_terminal_white_boundary` and `sampler_requests_only_tail_and_adjacent_white_frames_at_exact60fps` |
| `AudioExportHeadroom.prepare` | Set gain to 1 for overloaded input, or reserve 0 dB | `AudioExportHeadroomTest.loud_score_gets_one_gain_without_changing_sign_timing_or_channel_balance` |
| `PcmSampleConverter.encode16` | Return zero PCM, encode big-endian or scale by 32768 | `AudioBeatMapAnalyzerTest.pcm16_conversion_is_little_endian_and_clamped` |
| `DecodedMaskEvidence.classify` | Return MEASURED for all inputs or accept values above 1 | `DecodedMaskEvidenceTest.invalid_values_are_not_clean_evidence`, `missing_buffer_is_not_an_empty_observed_person`, `half_opacity_counts_as_measured_person_and_empty_source_takes_precedence` |
| `FearStrobeAudit.evaluate` | Ignore wrong-luma samples or missing finale intervals | `FearStrobeAuditTest.complete_shutter_and_finale_evidence_fails_when_one_required_interval_is_missing_or_wrong` |
| `RenderPassPlanner.plan` | Remove glow blur, change compositor input to source or alter pass order | `RenderPassPlannerTest.fuses_cheap_operations_but_keeps_blur_glow_and_depth_in_dedicated_passes` |
| `EffectOrchestrator.orchestrate` | Ignore accent budget or same/global cooldown | `EffectOrchestratorTest.high_band_onset_creates_short_flash_with_cooldown_and_budget`, `global_cooldown_applies_between_different_accents_and_allows_its_exact_boundary` |
| `LiveDecoderTransitionPlan.remainingFrames` | Return all current frames or empty list | `LiveDecoderTransitionPlanTest.transition_prefix_is_decoded_once_and_remaining_live_frames_keep_their_clock` |
| `RenderedCandidateSelector.chooseBestAvailable` | Rank every candidate including rejected ones, or always set passedQualityGate false | `RenderedCandidateSelectorTest.selects_strongest_accepted_decoded_candidate_not_first_style` |
| `DecodedFaceEvidence.isFreshFor` | Ignore PTS/source/clip | Existing freshness tests in `DecodedFaceEvidenceTest`; required evidence tests in `RenderedMp4AcceptanceTest` |

The coordinator records execution results separately. This file does not claim that unrun mutations were killed.

## Focused validation

The selected Gradle debug unit-test run passed all **37** cases in `DualityLoopDirectorTest`, `EffectOrchestratorTest`, `RenderPassPlannerTest` and `HeartbeatMontageProfileTest`. The first run caught incorrect new fixture assumptions (two music cues intentionally outside the allowed cut margin, and empty static visual maps); those fixtures were corrected and the same selected run passed. Production was not changed to satisfy test assumptions. The coordinator owns the final full-suite result and mutation execution.

## Limits and remaining opportunities

No confirmed new production bug was found in the reviewed contracts. Passing JVM tests cannot prove that Android decoders, encoder/muxer, GLES shader compilation, frame-texture freshness, real inference or the application's user interface work together. Test names containing “decoded” often mean that a pure helper received synthetic pixel arrays or fabricated decoded reports. A real instrumented export and independent decoding must cover those integrations; frame/report fixtures cannot substitute for it.

Full reference-timeline error tolerance controls remain a design-level opportunity. They are recorded as a coverage gap rather than claimed solved by count increases or source-string checks. BALANCED device quality and multiple-onset cooldown/budget gaps found during this audit have been covered.
