# Reference Montage Card: Subject Re-entry Pulse

## Identity

- Reference: `https://www.tiktok.com/@dexter_133714/video/7640044186485443854`
- Local evidence fixture: `app/src/testFixtures/golden-mp4/dexter-author-reference.mp4`
- Observation method: frame sampling on A25 at 100 ms plus a 500 ms contact sheet; audio decoded
  on-device and measured on its sample clock
- Measured properties: `18.034 s`, `720×720`, `121.287 BPM`, 37 beats, 114 onsets
- Confidence: high for timing/audio structure; medium for semantic labels inferred from sampled frames
- Original Veycad profile name: `SUBJECT_REENTRY_PULSE`

Leonid states that the montage and music are his. The extracted audio may therefore be used locally
in Veycad. The film footage visible in the edit is not covered by that statement and remains an
analysis-only ignored fixture; it must never enter the APK or generated examples.

## Montage grammar

- Energy curve: restrained dark reveal, early full-subject reveal, accelerating portrait cuts,
  a layered/double-exposure peak, then a short release to black.
- Shot vocabulary: cropped poster, isolated full-body subject, medium action, close/profile,
  layered portrait and clean final portrait.
- Cut rhythm: visible source moments change roughly every 0.5–1.5 seconds; structural anchors land
  around the measured accent beats at 0.55, 2.51, 4.49, 6.47, 8.45, 10.43, 12.42, 14.39 and 16.38 s.
- Motion language: scale punches and small positional impulses support the source action. Motion is
  not used as a substitute for selecting a different source moment.
- Transition language: hard musical cuts dominate. A foreground subject is isolated, outlined and
  held/reintroduced across a background change. Short double-exposure composites create the peak.
- Palette and texture: dark high-contrast base, warm skin, restrained bloom, bright cutout edge,
  short black opening/ending punctuation.

## What requires real source material

- A credible result needs several distinct poses, scales or source moments. One static selfie can
  use virtual crops, but cannot manufacture the reference's full vocabulary without repetition.
- Foreground re-entry requires a temporally stable person matte. A center ellipse or luma key is not
  an acceptable production substitute.
- Directional transitions still require confirmed camera/subject direction. No evidence means no whip.
- The reference's copyrighted film shots and soundtrack are never test or application assets.

## Original implementation rules

- Choose opening, subject reveal, action, close, layered peak and finale as semantic roles before
  assigning effects.
- Align primary cuts to downbeats/onsets at sample-clock timestamps, with no repeated source window.
- Add one `FOREGROUND_REENTRY` composite in the first two incoming moments when the person-mask
  confidence and temporal stability pass, even if the source has little physical motion.
- Start from the incoming frame reduced to a near-black background, reveal the incoming person matte
  bottom-to-top on a short eased envelope, then reveal that frame's original background only after
  the subject insertion is complete. Dilate the matte for a restrained independent outline.
- Keep the cutout attached to its own source PTS and transform; never reuse an arbitrary last output bitmap.
- Use a brief double-exposure peak only on a strong musical accent and only once per phrase.
- Conservative devices may reduce matte/outline resolution, but must not replace the semantic effect
  with a generic flash or blackout.

## Acceptance gates

- Target about 15–16 visually distinct source moments in an 18-second dynamic result when the source provides them.
- No overlapping/repeated source ranges in the graph.
- Primary cut and re-entry anchors within one 30 fps frame of the selected sample-clock event.
- No un-authored black frame, including the first sample of every transition.
- Person-mask confidence at least `0.80` (with temporal stability enforced by the mask-producing
  analyzer) before `FOREGROUND_REENTRY` may be authored. Post-render semantic edge-leak remains diagnostic because
  recolouring the background changes the segmenter's output; decoded black-block artifacts are
  rejected above `0.08` of the measured tile grid.
- Subject remains visible through the re-entry window; foreground/background PTS and transform are logged.
- Rendered MP4 must pass codec, A/V drift, transition strength, colour jump and face-loss gates.

## Current gap

VME now schedules denser distinct event-driven source windows, renders two live decoder textures, produces
PTS-bound person masks and executes a mask-driven dark-stage foreground re-entry plus dedicated
blur/glow FBO passes. The measured reference profile also authors three restrained layer envelopes:
double exposure at 3.42–4.18 s, mirror slicing at 13.33–14.12 s and a double-exposure release at
14.85–15.42 s. Double exposure and mirror slicing now sample a second live MediaCodec decoder at a
separate source PTS rather than duplicating the incoming frame. The measured 16.376–17.55 s finale
isolates the masked subject on a violet-black stage with an eroded, spill-controlled matte; a monotonic
GPU black release at 17.15–18.034 s supplies the terminal punctuation. The emulator inspector confirmed
113 shader-active layer frames, including 61 temporal two-source frames with both source PTS values
recorded. AUTO validation renders and measures all three director alternatives before choosing the
closest accepted candidate. Remaining gaps are a retained independent foreground FBO, true monocular
depth, explicit halo/temporal matte-channel measurement, missing measured layer peaks in the middle
phrase and full parity across the emulator suite; those capabilities must not be reported as complete.
