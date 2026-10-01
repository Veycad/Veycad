# Local Veycad issue ledger

> Measurements and APK sizes in individual cards belong to the dated runs
> described there. They are not current build statistics. The 2026-09-25 clean
> Windows baseline produced an approximately 75 MiB arm64 debug APK and 187 MiB
> universal debug APK; see [baseline checkpoint](../BaselineCheckpoint-2026-09-25.md).

This directory is the project-local source of truth for engine defects and validation gaps. Nothing
in it is synchronized to an external tracker. Test media remains in ignored test-fixture/artifact
directories and is never embedded in the APK.

## Open

### VME-034 — Final subject-stage darkness had no decoded-frame acceptance gate

- Found: 2026-09-04 while comparing Golden 0 at `16.5–17.2 s` with the author reference. The old
  report accepted the render even though source-room pixels remained visible outside the isolated
  subject during the final dark stage.
- Root cause: the inspector proved only that `SUBJECT_STAGE` reached GLES; decoded acceptance did
  not measure pixels outside an independently inferred, source-aligned person matte.
- In progress: decoded QA now samples background-only luma at every confident live stage frame and
  rejects missing evidence or a non-isolated stage. Golden 0 exposes the prior false positive at
  `0.14012` maximum background luma. The threshold still needs calibration against the author
  reference before this issue can be resolved; Leonid has prioritised matte fidelity next.

### VME-003 — Pose-depth coverage was zero on the three current Golden sources (semantic-depth path resolved)

- Found: 2026-09-02, API 36 emulator, semantic regression of all three local Golden MP4s.
- Evidence: person segmentation produced 45–53 mask frames per source, but the pose detector did not
  return the shoulder/hip set required for the coarse depth plane (`depths=0`).
- Resolution: a person matte plus composition scale now produces an explicitly labelled semantic
  near-subject/far-background plane. Golden 0 produced 49 depth planes and executed the dedicated
  GLES `DEPTH_COMPOSITE` framebuffer eight times; decoded MP4 acceptance passed.
- Remaining debt: this is not metric learned depth. A true bundled monocular-depth model still
  needs a validated tensor/orientation contract and a physical A25 regression.

### VME-004 — Bundled perception models make the debug APK 123 MB

- Found: 2026-09-02 after enabling bundled face, selfie-segmentation and pose models.
- Evidence: `app-debug.apk` is 123 MB; Golden MP4 and phonk assets are absent from the archive.
- Mitigation: ABI splits now produce a `46 MB` arm64 APK for the emulator and Samsung-class devices,
  a `38 MB` armeabi-v7a APK and retain a `123 MB` universal fallback. This also removed an emulator
  install failure at 529 MB free space without deleting user media.
- Remaining impact: the universal fallback is still unsuitable for direct distribution without an
  app bundle/model-delivery strategy. Measure per-model contribution and retain only models that
  materially improve accepted edits.

## Resolved

### VME-051 — Samsung cold analysis repeats random 4K/HEVC seeks

- Found: 2026-09-15 on Samsung SM-S918B. A 23.156 s 4K/60 HEVC/HDR10+ gallery source spent 195 s
  in cold analysis, with repeated `FrameDecoder`/`WOULD_BLOCK` failures and only 34 semantic frames.
- Fixed: one sequential `MediaCodec`/GLES analysis decode, bounded semantic inference, immediate
  codec-buffer release, cheaper immutable-mask interpolation, cache schema v2 and a partial render
  wake lock.
- Physical-device regression: the authorized 1,554-frame source decoded 104 analysis targets in
  12.164 s; cold analysis completed in 50.142 s with 52 semantic frames. The candidate MP4 passed
  inspection with 1,271 shader frames, 467 dual-decoder frames, 115 blackout frames, 912 AAC
  samples, 13,288 us A/V delta and rotation 0. Full evidence is in
  `samsung-analysis-double-decode.md`.

### VME-050 — Heartbeat pinned one sharp face over its strongest echo

- Found: 2026-09-14 in a synchronized 24-frame and 1x review of the VME-049 candidate against the
  author reference from 11.733–19.717 s. The radial trail moved, but its face region remained mostly
  the unmodified primary image and read as haze instead of displaced head motion.
- Root cause: after composing the Heartbeat trail, the shader restored a constant 70% of the base
  face even at full layer opacity. That contradicted the measured strong-to-clean resolve already
  authored by the style recipe.
- Fixed: only the Heartbeat echo face restore now follows its existing opacity envelope, from 30%
  at peak echo back toward 70% as the layer resolves. Sigma and generic double exposure are unchanged.
- Verification: the API 36 render passed decoded acceptance with 1,271 frames, 92.3% beat-hit,
  100% author accents, 13,288 us A/V drift and 2.71% face loss. All 1,271 decoded primary and
  secondary source PTS match the previous candidate; 1x browser review reports zero dropped frames
  and no decode error through the entire reprise phrase.

### VME-049 — Heartbeat ignored the reference's real-motion cadence

- Found: 2026-09-14 after VME-048 improved shot-scale cadence. The selected source windows still
  followed the generic pool's motion order; across the 12 remappable roles its motion agreement
  with the author reference was negative despite acceptable decoded output.
- Root cause: the Heartbeat recipe measured shot scale but did not export or consume the existing
  camera- and residual-subject-motion evidence during authored-role assignment.
- Fixed: debug reference analysis exports both motion vectors, the recipe stores measured
  per-role motion targets, and the director uses a deterministic one-to-one assignment weighted
  60% by relative scale and 40% by real source motion. Opening, action insert, light accent and
  finale stay pinned; missing or flat motion evidence preserves the scale-only fallback.
- Verification: API 36 `emulator-5554` rendered and accepted 1,271 frames. Scale Spearman agreement
  improved `0.692308 → 0.818182`; motion agreement improved `-0.195804 → 0.524476`. Beat-hit is
  92.3%, author-accent hit 100%, A/V drift 13,288 us, face loss 2.33%, and the maximum decoded
  artifact/black-block score is 1.04%.

### VME-048 — Heartbeat preserved pool order instead of the reference's shot-scale cadence

- Found: 2026-09-14 after VME-047 corrected subject presence. Twelve ordinary face cues still ran
  in the generic DYNAMIC pool order, producing a long sequence of similarly large faces while the
  author reference alternates close, medium and wide compositions.
- Root cause: the recipe measured reference timing and luma but owned no composition targets beyond
  one historical close/wide pair. The source selector discovered useful windows but their rank had
  no relationship to the authored cue scale.
- Fixed: Heartbeat stores nullable measured face widths per authored role and rank-matches the
  already selected source windows. The mapping runs only with complete, materially varied face
  evidence, remains a permutation, and pins the opening, subjectless action, finale hero and
  verified light-accent portrait. Absolute widths are not compared across unlike aspect ratios.
- Verification: API 36 `emulator-5554` rendered and accepted 1,271 frames. Across the 12 remappable
  face cues, normalized rank MAE fell `0.303030 → 0.189394` (37.5% lower) and Spearman agreement
  rose `0.318740 → 0.707532`. The first phrase retains 15 distinct source windows. Beat-hit remains
  92.3%, author-accent hit 100%, A/V drift 13,288 us and face loss 2.32%, below the 5% gate. An
  unrestricted control that moved the light role was rejected at 6.56% face loss.

### VME-047 — Heartbeat placed its animal interlude in a measured face cue

- Found: 2026-09-14 after exporting face-region observations for the author reference and sole
  `IMG_1647.MOV` source. The 3.733 s action cue and its 11.750 s reprise contain no detected face,
  while the 8.700 s cue and its 16.717 s reprise do. The current edit had the inverse subject
  presence at all four windows.
- Root cause: `selectDistinctSubjectlessInterlude` assigned a useful animal/object interval to
  authored role 12 without measuring which reference role was actually subjectless. That erased a
  readable portrait twice and left two fast action cues occupied by another close portrait.
- Fixed: the same content-derived interlude is now assigned to measured role 4. Role 12 again uses
  its unique human pool window, while the finale keeps its separately selected readable hero.
- Verification: API 36 `emulator-5554` rendered and accepted 1,271 frames. Face-presence agreement
  at the four affected reference/reprise windows changed `0/4 → 4/4`; exactly clips 4, 12, 15 and
  23 changed (171 frames), and the first phrase retains 15 distinct source windows. Beat-hit remains
  92.3%, author-accent hit 100%, A/V drift 13,288 us and `foreground_reentries=0`. Targeted tests
  and arm64 APK assembly pass; full-suite/lint verification is recorded in the accompanying report.

### VME-046 — Bright source erased Heartbeat's measured dark/light scene rhythm

- Found: 2026-09-14 in a full aligned contact-sheet review after VME-045. The author reference
  alternates dark, neutral and bright plates, while 15 of 26 rendered scenes remained `0.16–0.30`
  luma brighter because the source was recorded in a white daylight room.
- Root cause: Heartbeat had measured cut, pulse, light-accent and finale curves, but ordinary scenes
  inherited the generic source exposure plus one weak global toe. Their grade never consumed the
  reference scene-luma map or the selected source window's measured luma.
- Fixed: the Heartbeat recipe now stores the median decoded luma of each author-reference scene.
  Ordinary scene grades invert the existing GLES transfer function from the current selected
  window's median visual-map luma. The two authored white plates and adaptive finale retain their
  dedicated curves; missing source evidence preserves the prior fallback; Sigma is untouched.
- Verification: API 36 `emulator-5554` rendered and accepted 1,271 frames. Scene-median luma MAE
  against the author reference fell `0.157710 → 0.025238` (84% lower), and aligned frame-luma MAE
  before the black tail fell `0.177755 → 0.065547` (63% lower). Decoded contact frames retain face,
  clothing and room detail while restoring the reference's dark/light cadence. Beat-hit remains
  92.3%, author-accent hit 100%, A/V drift 13,288 us and `foreground_reentries=0`. Full unit tests,
  Android lint and arm64 APK assembly pass.

### VME-045 — Heartbeat finale clipped the close hero after semantic role correction

- Found: 2026-09-14 in the decoded A/B/C review after VME-044 moved the intended close emotional
  window into the finale. The previous grade had been tuned for a darker wide plate and drove the
  replacement close-up to luma `0.879`, washing out the face, shirt and room detail.
- Root cause: the Heartbeat finale used fixed exposure keyframes independent of the luma of the
  source window selected by the recipe. A semantically correct role change therefore invalidated
  the old exposure calibration.
- Fixed: when the selected finale window has at least two measured visual samples, Heartbeat now
  derives its grade from their median source luma and the measured finale targets. It preserves the
  fixed fallback when evidence is missing and keeps the dark musical punctuation as a separate
  post-echo target.
- Verification: API 36 `emulator-5554` rendered and accepted all 1,271 frames. On the aligned
  18.667–19.800 s finale window, luma-trajectory MAE against the author reference fell from
  `0.178469` to `0.049241` (72% lower). At 19.000/19.400/19.700 s decoded luma changed from
  `0.879/0.854/0.822` to `0.692/0.642/0.374`; the reference is `0.720/0.733/0.414`. The result
  retains 92.3% beat-hit, 100% author-accent hit, 13,288 us A/V drift and zero Sigma foreground
  re-entries. Full unit tests, Android lint and arm64 APK assembly pass.

### VME-044 — Heartbeat close and wide action windows were assigned to opposite cues

- Found: 2026-09-14 in an emulator A/B/C against Leonid's author reference and the unchanged
  `IMG_1647.MOV` source. The 3.733 s close cue showed a wide kitchen plate, while the 8.300 s
  wide-action cue showed the hand-over-face close-up.
- Root cause: Heartbeat preserved the generic DYNAMIC pool order even when its two selected
  windows had the inverse semantic face scale required by the measured style cues.
- Fixed: the recipe now swaps only this pair when at least two confident face samples per window
  prove a scale inversion larger than 0.08. The same mapping drives the authored reprise and
  finale; missing or ambiguous evidence preserves the original order.
- Verification: API 36 `emulator-5554` rendered 1,271 frames. Visual checkpoints at 3.9 s, 8.5 s
  and 19.0–19.7 s now use the closer matching composition roles. Candidate score improved
  0.7570864 → 0.7675381 and decoded face loss 0.024193548 → 0.016129032. Beat-hit remains
  0.9230769, all 26 pulses match, A/V drift remains 13,288 us, and the first phrase retains 15
  unique source windows. Full unit tests, Android lint and debug APK assembly pass.

### VME-043 — Heartbeat imported Sigma's foreground re-entry recipe

- Found: 2026-09-09 when Leonid reviewed the full `IMG_1647.MOV` Heartbeat result and identified
  the dark-stage person cutout as the Sigma treatment.
- Root cause: both product styles exposed the same `DYNAMIC` director value and were separated by
  an independent boolean. `HeartbeatDirector` then explicitly selected a stable matte role for
  scene 8 and assigned the shared `FOREGROUND_REENTRY` transition for the entire scene.
- Fixed: product/render requests carry a typed `SIGMA` or `HEARTBEAT` recipe. Heartbeat no longer
  selects an isolation role and emits only its measured opening/hard cuts; exact-matte preparation
  is graph-driven and runs only when the selected graph actually requests a foreground transition.
- Verification: the API 36 emulator rendered all 1,271 frames with `foreground_reentries=0`,
  `foreground_reentry_source_pts=0`, no foreground/transition/depth pass, 26/26 Heartbeat pulses
  and 13,288 us AAC drift. The 6.1–7.3 s contact frames show the complete moving source plate rather
  than a retained silhouette or dark Sigma stage. Full unit tests, Android lint and APK build pass.

### VME-042 — Heartbeat encoded the complete MP4 twice to evaluate an already-known matte

- Found: 2026-09-09 while profiling the 21.167-second `IMG_1647.MOV` Heartbeat regression.
- Root cause: exact entrance masks were requested only after the first candidate had already been
  encoded; the selected graph was then encoded again. Debug isolation A/B also encoded another
  complete 1,271-frame movie, and every one of 27 cuts recreated its decoder codecs.
- Fixed: Heartbeat resolves its 33 PTS-aligned masks before MediaCodec starts and performs one final
  encode. Isolation control is limited to the measured `6.1–7.3 s` window (72 silent frames).
  Export now owns two decoder/surface sessions for the complete timeline and uses `flush + seek`
  between directed source ranges instead of recreating codecs at every cut.
- Verification: API 36 emulator diagnostics report `encode_passes=1`, `decoder_instances=2` and
  `clip_count=27`. Before/after inspector comparison found 1,271 identical decoded texture clocks,
  zero changed or missing PTS, with 532 dual-decoder frames, 65 transition frames, 26/26 authored
  pulses and 13,288 us AAC drift. Full unit tests, Android lint and the arm64 APK build pass.
- Superseded scope: VME-043 removed the misplaced foreground operation from Heartbeat. One-pass
  exact-matte preparation remains available by graph topology for Sigma and future measured cues.

### VME-041 — Heartbeat midtones stayed flatter and brighter than the author reference

- Found: 2026-09-09 from sequentially decoded frame measurements, excluding intentional black
  and white accents. The current candidate averaged `0.4533` luma while the author reference
  averaged `0.4096`; the same mismatch remained across the central phrase.
- Root cause: Heartbeat inherited the neutral source grade outside its two measured light plates.
  Its own dark opening bias did not establish a coherent tone through the rest of the edit.
- Fixed: the existing Heartbeat shader profile applies a bounded `1.10` toe to ordinary shots;
  measured high-exposure plates, pulse overlays and Sigma stay on their prior branches.
- Verification: the emulator candidate moves working luma to `0.4253`, reducing the aggregate
  distance to the reference by about 64%. Black/white frame counts, all 1,271 source/decoded PTS,
  all layer envelopes, AAC timing and cut positions are unchanged. Face-loss improves from
  `0.09328` to `0.07836`; no new clipped or crushed frames appear.

### VME-040 — Heartbeat finale lost its last double-image return

- Found: 2026-09-09 by decoding the author reference and the current candidate at
  `19.600–19.800 s`. Both contain the dark hit at 19.650 s and terminal white pulse at
  19.767 s, but Veycad resolved its scene-wide echo before the dark hit and returned one clean
  frame while the reference returns several displaced live copies.
- Root cause: the one-way per-scene echo envelope correctly resolved the earlier long shot, but
  could not express the separate six-frame reprise after the final dark punctuation.
- Fixed: an explicit measured `19.666667–19.766667 s` echo-stutter reuses the existing Heartbeat
  GLES trail and is superseded by the existing white pulse on the next encoded frame.
- Verification: API 36 emulator rendered 1,271 frames with the Heartbeat author audio. Exactly six
  layer frames changed; all 1,271 requested and decoded primary source PTS remain identical to the
  previous accepted route. Decoded frames now show moving separated copies after black and before
  white, with no extra cut, freeze, rotation, A/V drift or Sigma transition.

### VME-039 — Heartbeat collapsed two adjacent compositions into one portrait role

- Found: 2026-09-09 by comparing matched frames at `6.150–7.500 s` after removing Sigma's
  foreground stage. Slot 8 and the following light-accent slot 9 both selected pool role 2, so the
  output repeated one high-angle portrait where the reference changes composition.
- Root cause: role 8 had been redirected to the clean mask portrait solely for the former Sigma
  isolation experiment; its reprise also gained an unrelated portrait crop. Both survived after
  that transition was removed.
- Fixed: role 8 and its reprise now use their own selected source window with identical full-plate
  framing. Role 2 remains limited to the two measured bright portrait accents.
- Verification: the emulator render changes exactly 133 frames in graph clips 8 and 19. The first
  phrase now cuts from a defocused frontal medium shot to a distinct high-angle light portrait;
  the second phrase repeats that medium-shot motif under directed echo. Face-loss improved from
  `0.10227273` to `0.09328358`; 1,271 video and 912 AAC samples, 13,288 us A/V delta, rotation 0.

### VME-038 — Heartbeat inherited Sigma's foreground re-entry stage

- Found: 2026-09-09 by decoding matched frames around the authored `6.167 s` cut. The Heartbeat
  graph placed a segmented subject on a black stage because slot 8 explicitly used Sigma's
  `FOREGROUND_REENTRY` transition and entrance transform.
- Root cause: an earlier contact-sheet interpretation mistook the reference's backlit, defocused
  full plate for an alpha-matted character isolation effect. Reusing the Sigma transition then
  produced a grey cutout, mask edge artifacts and the exact mixed-style impression reported by
  Leonid.
- Fixed: Heartbeat now uses measured hard cuts throughout; slot 8 keeps the complete live source
  plate and the existing 250 ms defocus node supplies the visible accent. Sigma remains unchanged.
- Verification: the API 36 emulator rendered all 1271 frames with `style=heartbeat`,
  `graph_profile=HEARTBEAT_V1:production`, `music=heartbeat_author` and zero authored transition
  events. Matched frames at 6.20/6.40 s show the live plate moving from defocused to sharp, without
  the black stage; foreground edge-leak and temporal-instability issues disappeared. All 181 unit
  tests and debug assembly pass.

### VME-037 — Heartbeat production shader did not compile on Android GLES

- Found: 2026-09-09 by repeating the product Heartbeat path on the API 36 emulator. The UI restored
  the old result after nine seconds and recorded only `IllegalStateException`, which looked like a
  Sigma/Heartbeat mixture because no new Heartbeat MP4 had been produced.
- Root cause: `rawPersonMask` and `stageErodedAverage` were referenced outside their GLSL local
  scopes. JVM source-contract tests do not compile the shader and therefore stayed green.
- Fixed: the translated-matte average is now computed in the same foreground branch that consumes
  it; failure diagnostics retain the driver message; a regression locks the declaration before use.
- Verification: the same UI path rendered 1271/1271 shader frames and completed a 21.17-second MP4.
  Diagnostics prove `style=heartbeat`, `graph_profile=HEARTBEAT_V1:production`,
  `music=heartbeat_author`, 912 AAC samples, 13,288 us A/V delta and rotation `0`. All 181 unit
  tests and debug assembly pass. Visual QA still rejects this candidate and remains separate work.

### VME-Heartbeat route — Heartbeat silently used Sigma music and source profile

- Found: 2026-09-08 after Leonid observed a Sigma/Heartbeat mixture and the Sigma score.
- Root cause: built-in music selection ignored montage style; the Sigma score then activated its
  reference beat/source profile before the Heartbeat graph was substituted.
- Fixed: style-specific audio selection and refresh, render-time SHA guard, graph-derived result
  label, and explicit style/music/profile diagnostics.
- Verification: API 36 production UI render logged `style=heartbeat`,
  `graph_profile=HEARTBEAT_V1:production`, `music=heartbeat_author`, 1271 rendered frames and
  13,288 us A/V delta. Full unit/lint/build passed. Visual QA still rejects the candidate and is
  tracked separately. Details: `heartbeat-style-music-routing-0908.md`.

### VME-036 — Live subject arrived ahead of the authored 1.5-second checkpoint

- Found: 2026-09-04 by comparing exact `0.0/0.5/1.0/1.5/2.0/2.5 s` opening sheets for Golden 0
  against the author reference. At `1.5 s` the old curve already exposed the shoulders and chest,
  while the reference still holds a head-and-neck composition.
- Root cause: the final 60% of the subject travel began at transition progress `0.45` and ended at
  `0.73`, so the live decoder texture and its PTS-aligned matte moved too far before the phrase
  landing.
- Fixed: the final movement ramp now spans `0.48..0.76`. The subject remains live throughout; no
  frame is frozen or cached as a still.
- Verification: API 36 emulator outputs `opening-midcurve-g0-101.mp4`,
  `opening-midcurve-g1-102.mp4`, and `opening-midcurve-g2-103.mp4` preserve 72 distinct opening
  source PTS. Exact opening sheets now match the reference framing more closely at `1.5 s`; Golden
  0 reports edge leak `0.0`, temporal IoU `0.99031`, face loss `0.02`, and A/V drift `14,649 us`.

### VME-035 — Independent low-resolution selfie masks made the live cutout breathe

- Found: 2026-09-04 in the Golden 0 opening at `0.93–1.87 s`. The decoded silhouette had a visibly
  unstable hair/shoulder edge; its measured edge leak was `0.01862` and temporal IoU `0.92316`.
- Root cause: sequential video frames were sent to ML Kit in `SINGLE_IMAGE_MODE`, disabling its
  previous-frame smoothing, and the native `256x256` model mask was narrowed to `192` columns before
  GLES enlarged it to the portrait output.
- Fixed: the analyzer now uses `STREAM_MODE` for ordered video frames and retains the full native
  `256x256` matte grid. A unit test locks both properties so the video path cannot silently regress
  to still-image segmentation.
- Verification: API 36 emulator render `video-matte-stream-g0-94.mp4` reduces decoded edge leak to
  `0.0` and raises minimum temporal IoU to `0.99048`; face loss stays `0.02`, all nine author accents
  still hit within one frame and A/V drift remains `14,649 us`. Visual review shows a steadier
  silhouette, while a thin low-chroma hair spill remains the next explicit matte defect.

### VME-033 — Valid 15–18.034 second sources crashed the author-profile pipeline

- Found: 2026-09-04 in the first API 36 blind regression with the previously unused 17.136-second
  `crowd.MP4` source. Product capture policy accepts videos from 15 seconds, but the author profile
  independently required 18.034 seconds and threw before directing or rendering.
- Root cause: three layers assumed that source duration must be at least output duration even though
  the renderer already maps each clip across a unique selected source interval and can play it more
  slowly. The fixed 18.034-second author phrase therefore contradicted the 15-second capture floor.
- Fixed: only `SUBJECT_REENTRY_PULSE_V2` may extend a source accepted by the 15-second product
  contract to the fixed author phrase. Generic graphs retain the strict source-duration rule. Unit
  tests verify the lower boundary, rejection below it, non-overlapping source intervals and bounded
  decoder PTS for a 17.136-second source.
- Verification: the same source now produces a 542-frame MP4 at 18.034 seconds with video/audio PTS
  zero, all nine accent hits and 14,649 us A/V drift. Decoded QA intentionally rejects this particular
  screen recording because it contains no person (`69` semantic samples, `0` foreground samples),
  so it cannot satisfy the reference's subject re-entry; the product exposes that as a QA warning
  instead of crashing. The remaining 16.5-second colour jump is a real screen-state change in this
  unsuitable source, not a duration-extension artifact.

### VME-032 — Android purged an active production render and Share crashed after durable migration

- Found: 2026-09-04 in the complete product UI path on the API 36 emulator. While three candidates
  were rendering, `installd` purged the 55 MB imported source and pending MP4s from quota-managed
  `cache/imports` and `cache/pending-renders`; candidate two then failed in `FileSource`, the UI
  returned silently to the director and the user's source was no longer retryable.
- Fixed: imported source drafts now live in `files/source-draft`, candidate MP4s in
  `files/pending-renders`, and only picker scratch stays in cache. A failed/restarted render keeps the
  latest source and restores its display name/music. New Edit explicitly clears it. `FileProvider`
  was migrated from the stale cache root to the durable pending-render root after the UI regression
  exposed a real `IllegalArgumentException` on Share.
- Verification: the same 55 MB source survived APK reinstall and produced all three candidates.
  The result screen displayed a playing non-black preview. Before Save, MediaStore contained only
  the input and `files/my-edits` was empty; Share opened the Android resolver without saving. Save
  then created exactly one `Movies/Veycad` row and one private My Edits copy. Unit tests assert the
  directory lifetime contract and FileProvider path.

### VME-031 — Guided matte QA compared the settled subject with a fictitious 15% vertical shift

- Found: 2026-09-04 in production render `production-ui-guided-matte-80.mp4`. Decoded QA reported
  edge leak `0.23568` at `1.800 s` even though the inspector showed the authored subject envelope
  already at `0.99776` and visually settled.
- Root cause: GLES drives entrance travel from the nonlinear `foregroundReentry` subject envelope,
  while decoded QA used raw transition time (`0.72`) and therefore translated the expected mask by
  another 15% of frame height. The source matte also used only semantic smoothing and did not snap
  its uncertain fringe to real image edges.
- Fixed: QA now derives the identical subject envelope through `TransitionTimeline`; local semantic
  masks receive compact colour-guided edge refinement before temporal interpolation. Confident
  interiors remain authoritative, while only the soft alpha fringe is pulled toward neighbouring
  foreground/background colour evidence.
- Verification: API 36 one-candidate render `reference-parity-guided-matte-82.mp4` is accepted.
  Maximum edge leak fell from `0.23534` to `0.01862` (gate `0.02`), temporal IoU is `0.92316`,
  face loss is `0.02`, all nine accents hit within one frame, decoded effect signatures pass and
  A/V drift is `14,649 us`. Synthetic unit tests cover colour-edge snapping and the nonlinear
  settled-envelope geometry.

### VME-030 — One clean opening matte did not guarantee a stable live entrance

- Found: 2026-09-04 in decoded Golden 2 render `reference-parity-live-cutout-g2-71.mp4`.
  The opening began on a clean segmentation sample but only 40 of 75 entrance frames executed the
  foreground composite; decoded QA found only four matte samples and no temporal pair. The same
  source also missed the 2.514 s phrase accent and reported 5.48% face loss because 300 ms face-QA
  results were carried across newly cut frames.
- Fixed: opening selection now ranks the complete advancing source window by mask confidence,
  temporal cleanliness and subject quality, rather than accepting one clean start frame. Decoded
  matte history persists across sparse low-confidence samples inside the same entrance. Face QA
  runs on its actual 100 ms sample clock, and the authored 2.514 s boundary has a short restrained
  screen pulse so visually similar adjacent source frames still produce a measurable music hit.
- Verification: API 36 emulator render `reference-parity-live-cutout-g2-72.mp4` is accepted. It
  executes 104 foreground-composite frames, yields 27 decoded matte samples, temporal IoU `0.98169`,
  edge leak `0.0`, face loss `0.03448`, all nine accents within one frame, and still records `72/32`
  distinct opening/finale source PTS. Visual contact/layer/transition review shows a moving subject,
  no pause, no horizontal bands and a content-aware dual-source whip.

### VME-029 — Boxed semantic planes exhausted the Android heap before AAC analysis

- Found: 2026-09-04 while running the post-live-cutout regression on Golden 0. Visual analysis
  retained 101 person masks and 98 depth planes at `192x256` as boxed `List<Float>` values, raising
  Java allocation to about 190 MB of the 192 MB heap. Decoding the separate 18-second author track
  then failed in `MediaCodecAudioDecoder` while growing its PCM buffer.
- Fixed: masks, depth planes, all hot mask transforms/metrics and decoded QA mattes now use primitive
  `FloatArray` storage. Sparse PTS interpolation still reuses its two immutable source planes, and
  `Plane` preserves content equality explicitly so graph/timeline semantics do not change.
- Regression: a unit test constructs a full `192x256` plane, verifies primitive-array storage and
  value-based equality/hash codes. All 102 unit tests, lint and debug assembly pass.
- Verification: API 36 emulator render `reference-parity-live-cutout-g0-70.mp4` now completes and is
  accepted: 101 semantic masks, 98 depth planes, `72/32` distinct opening/final subject PTS,
  temporal IoU `0.97613`, edge leak `0.0`, face loss `0.01333` and A/V drift `14,649 us`. During the
  formerly failing stage, Java allocation was about 44 MB rather than about 190 MB.

### VME-028 — Live cutout texture advanced faster than its sparse matte and exposed a bright fringe

- Found: 2026-09-04 after making both subject cutouts live. `g59` kept `72/32` distinct source PTS
  and the contextual whip, but decoded edge leak reached `0.06448` at `1.800 s` because the decoder
  texture advanced every frame while the semantic matte changed only every `250 ms`.
- Rejected attempts: materialising a full interpolated mask/depth/flow plane for every planned frame
  caused an Android `OutOfMemoryError` at the 192 MB heap limit (`g60`). Colour-only edge
  decontamination (`g64`) did not measurably change leakage and was removed. Weak erosion in
  `g61–g65` improved the metric but remained above the strict `0.02` gate.
- Fixed: frame plans now carry two immutable sparse matte references plus exact PTS blend progress;
  only one temporary byte buffer is interpolated during GLES texture upload. The completed entrance
  phase progressively tightens only the outer mask fringe and removes the decorative outline while
  leaving the early reveal and interior face/hair untouched.
- Verification: API 36 emulator render `reference-parity-accepted-live-cutout-g1-68.mp4` passes with
  edge leak `0.01995`, temporal IoU `0.91159`, face loss `0.01493`, `72` opening source PTS and `32`
  final-stage source PTS. Contact/layer review shows no horizontal bands, no frozen person and no
  blocking holes around the face or upper hair.

### VME-027 — Finale cutout froze or selected an active hand-over-face frame

- Found: 2026-09-04 in visual review of `reference-parity-live-cutout-clean-split-g1-50.mp4`
  at `16.53–17.43 s`. Doubling semantic cadence exposed a `7.875 s` source sample whose segmentation
  quality was high even though a moving hand crossed the face.
- Rejected attempts: wrist/face and forearm/face pose proxies produced byte-identical MP4s and were
  removed. A source-window fallback also did not run because the bad sample was a direct candidate.
- Rejected attempt: flow-aware retained-frame ranking produced a cleaner still in `g54`, but still
  violated the product requirement because the final person remained paused. It was removed.
- Fixed: `SUBJECT_STAGE` now always follows its clip's advancing decoder PTS and PTS-aligned matte.
  The director ranks a complete calm live window instead of a single anchor; it rejects a fast
  head/camera move that becomes blurred after dark-stage grading. A face-region guard is applied
  after spill suppression, without replacing the live texture.
- Verification: `reference-parity-accepted-live-cutout-g1-68.mp4` records `32` distinct final-stage
  source PTS, face loss `0.01493` and a clean moving finale. The inspector rejects fewer than eight
  distinct PTS as `subject_stage_source_frozen`.

### VME-026 — Mirror accent rendered six crude bands and foreground entrance froze the person

- Found: 2026-09-04 in the decoded layer sheet at `13.33–14.12 s` and opening source schedule at
  `0–2.514 s`. The mirror shader used six hard horizontal slices; the opening forced every frame to
  the same source PTS and reused one boundary matte.
- Fixed: the mirror accent is one feathered moving vertical temporal reflection with no horizontal
  bars. Opening texture PTS now advances continuously and uses masks sampled every `250 ms`, while
  screen-space entrance motion still translates texture and its matching matte together.
- Verification: the final API 36 render `reference-parity-accepted-live-cutout-g1-68.mp4` records
  `72` distinct opening source PTS and `32` final-stage PTS, decoded mirror strength `0.48297`,
  temporal matte IoU `0.91159`, edge leak `0.01995`, and zero black blocks. The decoded layer sheet
  has one vertical reflection and no stacked horizontal bands.

### VME-025 — Video PTS-zero requirement existed only in the plan, not container acceptance

- Found: 2026-09-04 during Definition-of-Done audit. The inspector stored only last video/audio PTS,
  so a shifted track could pass duration and A/V drift checks.
- Fixed: container scanning records first PTS for both tracks; non-zero video start independently
  rejects the inspector and decoded MP4 acceptance report.
- Verification: `reference-parity-live-cutout-stable-final-g1-54.mp4` reports video and audio first PTS
  exactly `0`; a unit regression supplies `33,333 us` and requires `video-not-pts-zero`.

### VME-024 — Required authored effects could pass from graph evidence without decoded visibility

- Found: 2026-09-03 while auditing the double-exposure, mirror-slice and glitch Definition-of-Done
  requirement. The inspector proved that GPU passes ran and the timeline gate proved that nodes were
  authored, but neither condition proved that encoding preserved a visible result.
- Fixed: the MP4 sampler now adds exact effect-window checkpoints and derives pixel-only signatures
  from decoded luma/chroma planes. Reference candidates are rejected independently when double
  exposure, mirror slice or glitch falls below the calibrated visible threshold.
- Verification: API 36 emulator renders on the three unchanged Golden sources measured decoded
  double-exposure peaks `0.38534`, `0.45369`, `0.56019`; mirror-slice peaks `0.03300`, `0.02159`,
  `0.05035`; and glitch peaks `0.00972`, `0.01413`, `0.02766`. The acceptance floors are kept below
  the weakest observed source (`0.28`, `0.015`, `0.007` respectively). Unit regressions remove each
  decoded signature in turn and require the corresponding explicit rejection.

### VME-023 — Generic grammar score was not reference timeline recall

- Found: 2026-09-03 while auditing the `>=0.95` Definition-of-Done gate. Accepted Golden renders
  reported only `0.892–0.907` because the value scored average shot length, role variety and a
  generic hard-cut ratio; it did not compare the authored event card.
- Fixed: the report now keeps that general diagnostic and separately compares all fifteen measured
  cut boundaries, opening foreground re-entry, all eight authored overlay windows, and the fixed
  glitch window with one-frame tolerance. Aggregate recall below `0.95` rejects the candidate, and
  every missing opening/layer/effect event rejects independently even if aggregate recall remains
  above `0.95`.
- Verification: `reference-parity-timeline-recall-g0-45.mp4` reports exact timeline recall `1.0`,
  decoded author accents `1.0`, maximum accent offset `33,243 us`, temporal matte IoU `0.99631`,
  edge leak `0.00588`, face loss `0.02778`, A/V drift `14,649 us`, and no acceptance issues. A unit
  regression removes only the mirror-slice event and verifies that it is named as missing despite
  aggregate recall remaining at least `0.95`.

### VME-007 — Foreground matte edge leak exceeded the reference target

- Found: 2026-09-02, physical Samsung A25 system regression. Earlier decoded ratios were `0.0302`,
  `0.1081`, and `0.0625` against the `0.02` target.
- Fixed: retained PTS-aligned masks, compact-mask refinement, spill suppression and registered
  decoded-output comparison replaced the drifting coarse matte. The acceptance gate now rejects
  edge leak above `0.02`; it is no longer report-only.
- Verification: strict API 36 emulator renders on all current Golden sources pass: Golden 0
  `reference-parity-strict-g0-43.mp4` reports `0.00588`; Golden 1
  `reference-parity-strict-g1-44.mp4` reports `0.0`; Golden 2
  `reference-parity-three-stage-entry-g2-42.mp4` reports `0.0`. Their decoded temporal IoU values
  are respectively `0.99631`, `0.99662`, and `0.99688`.

### VME-022 — Two author phrase accents had no decoded visual response within one frame

- Found: 2026-09-03 on the API 36 emulator after replacing the broad beat proxy with the nine
  measured author-track accents. `reference-parity-nine-accents-g2-36.mp4` hit only seven of nine;
  `0.551473 s` and `2.513560 s` were missing.
- Root cause: the QA decoder sampled only a 100 ms grid, the retained subject did not enter the
  frame by the first accent, and the original plate completed its reveal before the second accent.
- Fixed: reference renders add the exact nine sample-clock targets to decoded sampling. The opening
  now uses a three-stage subject curve (head entrance, readable hold, full arrival), a short
  mask-aligned colour pulse at `0.551473 s`, and lands the background on `2.513560 s`. A rejected
  zoom prototype produced `0.34086` edge leak; it was removed, and edge leak above `0.02` now
  rejects a candidate instead of being report-only.
- Verification: `reference-parity-three-stage-entry-g2-42.mp4` detects all nine accents with maximum
  offset `33,243 us`, while decoded edge leak is `0.0`, temporal matte IoU is `0.99688`, face loss is
  `0.04828`, A/V drift is `14,649 us`, duration error is `667 us`, and black-block score is `0.0`.
  Visual checkpoints show only the top of the head at `0.93 s`, the complete isolated subject at
  `1.87 s`, and the original plate after the `2.514 s` landing.

### VME-021 — Temporal matte stability was inferred from input masks, not decoded output

- Found: 2026-09-03 while auditing the reference-parity Definition of Done.
- Root cause: the inspector recorded source-mask IoU, while the MP4 acceptance sampler discarded
  the output segmentation after measuring edge leak. A renderer defect could therefore pass by
  presenting a stable input mask even if the encoded silhouette flickered.
- Fixed: consecutive output masks are now retained during the completed dark-stage checkpoint,
  registered only for a two-cell detector offset, and compared by temporal IoU. Missing evidence or
  an IoU below `0.88` rejects the rendered candidate.
- Verification: the API 36 emulator render `reference-parity-decoded-matte-g1-35.mp4` reports decoded
  temporal IoU `0.99656`, edge leak `0.0`, face loss `0.00763`, beat-hit `1.0`, A/V drift `14,649 us`
  and no black blocks.

### VME-009 — Authored temporal layers obscured otherwise readable faces on Golden 2

- Found: 2026-09-02 on A25 and reproduced 2026-09-03 on the API 36 emulator.
- Root cause: double-exposure and mirror-slice mixed their complete secondary texture uniformly over
  the primary frame. Eight of ten strict face-loss samples were inside those authored layer windows,
  even though the base source still contained a readable face.
- Fixed: local semantic analysis carries a normalized face region with each PTS attachment. The GLES
  compositor keeps the primary face readable only inside that measured region while retaining full
  temporal-layer opacity elsewhere; this is not a global opacity reduction or a disabled effect.
- Verification: on unchanged Golden 2, `reference-parity-face-preserved-g2-32.mp4` reduces decoded
  face loss from `0.07042` to `0.04930`, below the strict `0.05` reference gate. The mirror slices
  remain visibly present at `13.73 s`; duration error is `667 us`, beat-hit `1.0`, A/V drift
  `14,649 us`, edge leak `0.0` and black-block score `0.0`. Cross-source emulator renders also pass:
  Golden 1 (`reference-parity-face-guard-g1-33.mp4`) has face loss `0.00763`, and Golden 0
  (`reference-parity-face-guard-g0-34.mp4`) has face loss `0.02837`; both retain visible decoded
  double-exposure/mirror layers, report 100% beat hits, `14,649 us` A/V drift and no black blocks.

### VME-020 — Foreground re-entry used a wipe, mismatched OES coordinates and an occluded finale anchor

- Found: 2026-09-03, API 36 emulator, visual comparison of
  `reference-parity-retained-matte-21.mp4` and the author reference at `0.00–2.51 s` and
  `16.38–17.55 s`.
- Root cause: the opening revealed a stationary source through a horizontal threshold instead of
  moving a retained subject; a cheap post-effect sampled the unshifted source again; direct offsets
  in transformed OES space did not follow the CPU matte; and the final stage accepted the first
  confident mask even when a raised hand crossed the face.
- Fixed: source attachments now carry explicit subject quality, occlusion and mask-stability evidence;
  the director rejects obstructed anchors and retains the cleanest stable frame. The opening authors
  motion once in screen UV, derives the ML-matte coordinates and transformed decoder coordinates from
  that shared position, suppresses edge spill and restores the original plate only after arrival.
- Verification: `reference-parity-spill-suppressed-29.mp4` visibly shows the top of the head entering
  from below at `0.93 s`, a complete stable portrait on the dark stage at `1.87 s`, and a clean finale
  without the previous hand-over-face anchor at `17.07 s`. Emulator acceptance passes with duration
  error `667 us`, beat-hit `1.0`, A/V drift `14,649 us`, face loss `0.03053`, decoded edge leak `0.0`
  and black-block score `0.0`. The remaining cross-source matte-quality regression stays tracked by
  VME-007.

### VME-019 — Final isolated-subject stage leaked a bright source-background halo

- Found: 2026-09-03, API 36 emulator, visual review of
  `reference-parity-subject-stage-16.mp4` at `17.07 s`.
- Root cause: the final `SUBJECT_STAGE` composited the softly downsampled selfie matte directly, so
  high-contrast window pixels survived along hair and shoulder boundaries as a white fringe.
- Fixed: the subject-stage shader now erodes the retained alpha by one compact-mask texel and
  replaces only the discarded fringe with a restrained violet rim tied to the authored stage.
- Verification: `reference-parity-subject-stage-matte-17.mp4` visibly replaces the white spill with
  a thinner coloured boundary. Candidate score improved from `0.78879` to `0.79049`, maximum colour
  jump fell from `0.18836` to `0.18180`, and strict output gates remain green: duration error
  `667 us`, beat-hit `1.0`, A/V drift `14,649 us`, face loss `0.04839`, edge leak `0.00412` and
  black-block score `0.01042`.

### VME-018 — Reference layer peaks reused the current frame instead of a temporal source

- Found: 2026-09-03 while visually comparing `reference-parity-black-release-14.mp4` with the author
  reference.
- Root cause: double exposure and mirror slicing sampled a displaced/mirrored copy of `uIncoming`;
  there was no decoder advancing a second source clock outside transition windows.
- Fixed: temporal layer windows now keep a second MediaCodec decoder active, sample `uOutgoing` in GLSL
  and use authored offsets (`-420 ms` for double exposure, `+260 ms` for mirror slicing). The inspector
  rejects authored temporal layers unless both distinct source PTS values reach the draw call.
- Verification: `reference-parity-temporal-layers-15.mp4` renders 61 temporal two-source frames. At
  output `3.500 s`, source PTS are `21.355 s` and `20.935 s`; at output `13.500 s`, they are `11.711 s`
  and `6.776 s`. Decoded QA passes with duration error `667 us`, beat-hit `1.0`, A/V drift `14,649 us`,
  face loss `0.02459`, edge leak `0.00412` and black-block score `0.01042`.

### VME-017 — Reference-parity face preservation exceeded the strict target

- Found: 2026-09-03, API 36 emulator, `reference-parity-glitch-07.mp4`.
- Root cause: the analyzer carried the last detected face indefinitely through later no-face semantic
  samples, and the director did not reserve a clean finale before consuming unique source windows.
- Fixed: no-face semantic samples now clear stale face/composition evidence; the author profile reserves
  a guarded clean finale, applies its strict `0.05` face-loss and 20 ms A/V gates, and performs the
  measured 17.15–18.034 s GPU release to black.
- Verification: `reference-parity-black-release-14.mp4` is 18.034 s, reports face loss `0.02459`,
  beat-hit `1.0`, A/V drift `14,649 us`, zero decoded black-block/artifact score and a visually confirmed
  clean portrait at 17.07 s followed by black at 18.03 s.

### VME-016 — Subject re-entry started at the first cut instead of the opening frame

- Found: 2026-09-03, API 36 emulator, side-by-side 500 ms contact sheets.
- Root cause: the director only allowed `FOREGROUND_REENTRY` on incoming clips, the opening source
  window was not pinned to an exact confident mask PTS, and the GLES branch required an outgoing
  decoder texture.
- Fixed: the author profile now holds its first cut until `2.514 s`, snaps the opening source window
  to a confident mask PTS and renders a 2.5-second incoming-only dark-stage choreography.
- Verification: `reference-parity-opening-mask-05.mp4` executes 72 foreground-composite frames;
  decoded checkpoints show black at 0.0 s, progressive subject insertion from 0.5–2.0 s and the
  original background at 2.5 s. Edge leak is `0.0010` and black-block score is zero.

### VME-015 — AUTO virtual camera cut the right edge of portrait footage

- Found: 2026-09-02 in user validation of the production AUTO flow.
- Root cause: the director added arbitrary horizontal drift without having a measured subject
  centre, while also scaling the portrait frame. The result preferentially removed one edge;
  preview scaling was implicit and made export-versus-display diagnosis ambiguous.
- Fixed: push/pull is horizontally centred and capped at 1.12x; directional movement remains only
  inside its short two-texture whip window. Result playback explicitly uses scale-to-fit.
- Verification: unit tests enforce zero horizontal drift and the zoom safety cap; physical
  verification remains required on the user's exact source.

### VME-014 — Device-regression runner looked like a broken black application screen

- Found: 2026-09-02 during the A25 foreground re-entry regression.
- Root cause: the debug-only `GoldenRenderActivity` rendered offscreen and had no content view.
- Fixed: the runner now shows an explicit local render-test status instead of an unexplained black
  surface. Production navigation remains owned by `MainActivity`.
- Verification: the runner is debug-only; the normal launcher was restored and confirmed as the
  top resumed activity on the physical A25.

### VME-013 — Static footage never received the reference's subject re-entry beat

- Found: 2026-09-02 while comparing AUTO output with the Subject Re-entry Pulse reference card.
- Root cause: `FOREGROUND_REENTRY` required a semantic reveal event and compatible silhouettes from
  two different source moments. A stable/static subject therefore usually fell back to a hard cut,
  and the shader stylized the background instead of staging the subject on black.
- Fixed: the director now authors one readable middle-phrase re-entry when the incoming PTS-bound
  person mask is confident. A retained 96x128 boundary matte is spatially feathered on GPU; the
  900 ms envelope inserts the subject bottom-to-top over a near-black stage, holds it, then restores
  the incoming frame's original background in a separate eased phase.
- Verification: unit tests assert the one-effect rule, the delayed background envelope and shader
  uniforms; physical A25 render evidence is recorded with the regression artifact.

### VME-012 — Unsaved production renders and imported copies persisted in app files

- Found: 2026-09-02 while validating the production result flow on a physical Samsung A25.
- Root cause: imports and render candidates were written under persistent `filesDir`, regardless of
  whether the user chose Save.
- Fixed: production imports and candidates now live only under private cache. They are removed on a
  new edit, explicit exit and next launch. A persistent `my-edits` copy and a MediaStore copy are
  created together only after `tap_save`; Share exposes a temporary read-only FileProvider URI.
- Verification: an explicit Save created an 11 MB entry in both `Movies/Veycad` and `my-edits`; the
  final cleanup regression must additionally assert that an unsaved result leaves neither location.

### VME-011 — Completed montage could leave the user looking at a black/empty area

- Found: 2026-09-02 in the production `MainActivity` render flow.
- Root cause: the result card lived below the progress card in the same ScrollView, so its retained
  scroll position could expose empty black space; VideoView also had no visible preparation state.
- Fixed: result is a separate full-screen destination with an explicit loading state, decoded-video
  reveal, preview-error fallback, Save, Share and New edit actions. The original director screen is
  hidden once rendering completes.
- Verification: the next full A25 production render logged `render_complete` and an active preview
  (`tap_preview`, `playing=true`); Save and My edits navigation were reachable from the result.

### VME-010 — Completed renders were discarded when every candidate missed strict visual QA

- Found: 2026-09-02, production `MainActivity` flow on a physical Samsung A25.
- Evidence: all three 17.792-second MP4 candidates encoded, muxed and passed container inspection,
  but all missed decoded acceptance because of `face-loss`; the UI logged `render_failed` with
  `IllegalArgumentException` and exposed none of the completed files.
- Root cause: product orchestration called the strict `RenderedCandidateSelector.choose`, whose
  contract deliberately rejects a set with no accepted candidate.
- Fixed: the strict selector remains available for regression enforcement; product orchestration
  uses `chooseBestAvailable`, preserves the highest-scoring completed MP4 and exposes an explicit QA
  warning instead of reporting a false render failure.
- Verification: repeated AUTO render on A25 returned `status=ok`, selected the DYNAMIC candidate,
  copied an 11,543,581-byte final MP4, and recorded the real warning `face-loss`. The output has 13
  clips, two whips, 28 dual-decoder transition frames, AAC audio, 100% beat-hit and 25,334 us A/V
  drift.

### VME-008 — Isolated codec-flow noise authored a whip on static footage

- Found: 2026-09-02, physical Samsung A25 static regression.
- Evidence: mean flow confidence was only `0.00835` with two active frames, yet nine directional-blur
  frames were rendered.
- Root cause: any single horizontal camera vector above the per-frame threshold could create a
  `CAMERA_MOVE` event.
- Fixed: production-sized timelines now require at least three directional samples and at least 5%
  population support. Focused small synthetic maps keep their existing behaviour.
- Verification: repeated A25 static render reports `camera_events=0`, `whips=0`, no directional pass,
  13 virtual-camera clips and accepted decoded MP4. Dynamic Golden 1 still reports 31 camera events,
  two whips and 29 directional-blur draws.

### VME-005 — Foreground mask alignment lacked physical A25 validation

- Found: 2026-09-02.
- Resolution: all three physical-A25 Golden renders executed foreground/depth draws and passed
  decoded black-block, face-preservation, colour-jump and artifact gates. Twelve orientation-aware
  control frames were also inspected locally.
- Follow-up: edge quality is tracked separately as VME-007; passing this device-validation issue
  does not mean the matte has reached the reference target.

### VME-006 — Foreground re-entry produced black rectangular background regions

- Found: 2026-09-02 on the API 36 emulator; Golden 1 and 2 initially failed decoded-output QA.
- Root cause: the foreground branch used the outgoing OES texture as its background during the mask
  peak. A partially valid outgoing texture therefore became visible outside the incoming person.
- Fixed: foreground re-entry now separates the incoming person from a stable, locally stylized
  incoming background; two-decoder overlap remains active for whip and occlusion transitions.
- Verification: all three 17.792-second phonk renders pass decoded MP4 acceptance. Black-block score
  is 1.04%, 0% and 0%; the limit is 8%. Beat-hit is 100% and A/V drift is 25.334 ms on each render.
