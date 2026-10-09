# Veycad Reference-Parity Execution Plan

## Fixed objective

Veycad Montage Engine has one visual target: reproduce the montage grammar of the local author
reference `app/src/testFixtures/golden-mp4/dexter-author-reference.mp4` when editing new user footage.
The reference is 18.034 seconds, 720×720 and approximately 121.287 BPM. Leonid states that the
music and montage are his, so the extracted music may be used locally and bundled in Veycad. Film
footage visible in the reference is analysis-only and must never enter the APK, test output intended
for distribution, or application assets.

“Parity” means a defined match of timing, staging, motion and effect grammar after normalising for
different source content. It does not mean identical source pixels.

## Non-negotiable working rules

1. Do not add a library, model, shader or abstraction unless it closes a named failed comparison.
2. Every iteration is one loop: observe mismatch → record hypothesis → change code → run tests →
   render on Android Emulator → decode the rendered MP4 → compare → retain or revert the change.
3. Never report an effect from the graph alone. It counts only if the emulator render inspector confirms
   the GPU pass and decoded frames show its expected visual signature.
4. Never report success without attaching the newly rendered MP4 and its machine-readable report.
5. Keep references and Golden MP4s under ignored local test fixtures. Verify APK contents on every
   release candidate.
6. Keep failures in the local bug store. Do not send files, messages or reports to anyone unless
   Leonid explicitly requests it.
7. Preserve the application name, icon and Veycad engine foundation. The automatic director is the
   product; no manual editing mode is in scope.
8. Use the local Android Emulator as the required render target. Do not wait for, install to, or
   depend on a physical phone unless Leonid explicitly changes this instruction.

## Definition of done

The goal is complete only when all gates pass on three user Golden sources and a final blind source:

- Output duration is `18.034 s ± 33.4 ms`; video begins at PTS zero and AAC drift is at most 20 ms.
- All nine phrase accents are within one 30 fps frame of the reference-normalised audio anchors.
- The reference shot/effect timeline has a measured recall of at least 95%; no required event is
  silently replaced by a hard cut, flash or blackout.
- Opening subject re-entry lasts at least 1.2 s: stable person silhouette over a dark stage first,
  original background second, with no intermediate full-frame crossfade.
- Matte edge leak is at most 2%, temporal matte IoU is at least 0.88 and no one-frame holes appear
  around hands, hair or face at sampled checkpoints.
- Required layered peaks, echo/double exposure, mirrored/sliced accents and release to black are
  visibly present at their authored windows.
- Distinct-moment count is at least 15 when the source provides coverage. No source range repeats;
  static material uses intentionally distinct virtual framing rather than fake scene claims.
- Beat-hit rate is at least 95%, maximum un-authored black-block score is below 0.08, A/V drift is
  at most 20 ms, and no portrait crop removes a side of the subject.
- Face-loss regression is below 5% at frames where the source has a confidently visible face.
- Human side-by-side review at 0.5× and 1× finds no blocking transition, crop, matte or orientation
  defect. Any subjective rejection becomes a timestamped local bug and reopens the loop.

## Phase 0 — Freeze evidence and baseline

- Preserve the author reference, extracted audio, 100 ms visual-change series, 500 ms contact sheet,
  current emulator render and render-inspector report.
- Generate a frame-accurate event table containing every source cut, overlay entrance/exit, mask
  phase, scale impulse, dark frame and release.
- Record a baseline comparison report instead of a subjective percentage.

Exit: every visible reference event has a timestamp, duration, type and confidence.

## Phase 1 — Deterministic reference timeline

- Add the named profile `SUBJECT_REENTRY_PULSE_V2`.
- Lock its duration and phrase structure to the author track; do not let the generic duration policy
  move the ending.
- Encode the reference event table as data consumed by the director, not as scattered conditionals.
- Map source moments into semantic slots: opening poster, isolated subject, original-background
  reveal, close/profile build, action build, layered peak and finale.
- Produce explicit fallbacks for missing coverage and expose every fallback in the QA report.

Exit: graph-level comparison passes 100% for timing/event vocabulary before rendering.

## Phase 2 — Production subject cutout

- Retain the foreground in its own FBO instead of recomputing a loosely aligned mask per output frame.
- Stabilise masks across source PTS; add guided edge refinement, small-hole repair, adaptive feather,
  spill removal and independent outline width.
- Drive the entrance with a measured bottom-to-top reveal curve; hold the isolated subject on a dark
  stage; reveal the original background only after foreground completion.
- Keep matte, source texture, crop and transform on the same PTS clock.

Exit: all opening checkpoints pass matte IoU, edge-leak, face-preservation and phase-order gates.

## Phase 3 — Reference layer choreography

- Implement retained two/three-layer compositions for the reference’s double-exposure peaks.
- Add the measured mirror/slice and short glitch windows as shader parameters on the reference
  timeline; do not apply them randomly.
- Couple directional blur to layer motion and measured flow. Blackout remains musical punctuation,
  never a fallback for unavailable imagery.
- Ensure two-decoder overlap advances both sources throughout transition windows.

Exit: every required reference layer event produces both an executed GPU pass and a decoded visual
signature at its expected timestamp.

## Phase 4 — Source-to-role matching

- Rank source windows by person visibility, pose, gaze, gesture, scale, occlusion, composition,
  contrast and motion direction.
- Choose the complete sequence jointly so an individually strong shot cannot create repetition or
  break the arc.
- For low-motion footage, author distinct crops and controlled camera paths per semantic role.
- Reject or visibly warn about missing coverage; never claim a reference-quality result while hiding
  a fallback.

Exit: all three Golden sources fill the required roles without repeated source intervals.

## Phase 5 — Colour, audio and finish

- Match exposure/white balance between selected source windows before applying the profile grade.
- Reproduce the reference’s dark base, warm subject separation, restrained bloom and accent colours.
- Use the original sample-clock beat/onset map and author track; verify loop/cut and AAC duration
  against final video PTS.
- Preserve useful source audio only through an explicit ducking envelope; otherwise mute it.

Exit: colour-jump, black-block and A/V gates pass on decoded output.

## Phase 6 — Emulator regression and blind validation

- For each of the three local Golden MP4s: build, install to the running Android Emulator, render,
  pull output, inspect container,
  sample frames, generate a side-by-side contact sheet and save the report.
- Fix failures in priority order: crash/save → orientation/crop → audio → matte → missing transition
  → rhythm → colour polish.
- Run one previously unseen local source as the blind test. Do not tune profile constants against its
  identity; only fix general mapping failures.
- Confirm the app launches to the interface after splash, gallery import works, render progress is
  visible and the completed-video screen obeys save/share semantics.

Exit: every Definition of Done gate passes on all four sources and the final APK contains no Golden
MP4 or reference film frames.

## Required deliverable after every substantive loop

- Clickable local MP4 rendered during that loop.
- Exact git diff/commit identifier.
- Tests and emulator image/API used.
- Comparison table: improved, unchanged, regressed.
- Remaining failed gates with timestamps; no unsupported completion percentage.

## Stop conditions

Continue autonomously while an in-scope code, test or emulator validation step remains. Stop only when
Leonid explicitly asks to stop, the Definition of Done is satisfied, or a genuinely external blocker
requires new material/authority. A failed test or render is not a stop condition: diagnose, fix and
repeat.
