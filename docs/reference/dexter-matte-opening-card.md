# Reference Montage Card: live cutout entrance

## Identity

- Reference: user-supplied local file `dexter-matte-reference.mp4`.
- Observation method: temporal review of the first 3 seconds at 100 ms intervals.
- Confidence: high for the opening motion and background reveal; medium for the original matte-production method.
- Intended original profile name: `LIVE_CUTOUT_RISE`.

## Montage grammar

- Energy curve: black hold → continuous live rise → full readable portrait → fast plate reveal.
- Shot vocabulary: one close/medium portrait whose source time continues advancing throughout.
- Cut rhythm: no internal cut during the rise; the first major state change is the plate reveal.
- Motion language: constant-scale vertical translation from below the frame. Hair appears near
  0.2 s, eyes near 0.9–1.0 s, shoulders near 2.0 s.
- Transition language: isolated foreground over black until about 2.1 s; original plate becomes
  readable around 2.2 s and is substantially assembled by 2.3–2.4 s.
- Palette and texture: near-black stage, warm subject, narrow naturally lit hair boundary. There
  is no uniform coloured outline around the body.

## What requires real source material

- Required capture prompts: continuously moving face/upper-body take; hair separated from the
  background by contrast or rim light; enough source duration to keep every output frame live.
- Cannot be credibly created from a single still: changing expression, live hair motion, and the
  final transition into the original moving plate.
- Optional original treatment: restrained light wrap derived from the source edge, never a global
  synthetic stroke.

## Original implementation rules

- Timeline constraints: preserve advancing source PTS; translate the texture and PTS-aligned matte
  together; do not scale or freeze the subject during the rise.
- Candidate-selection rules: require a stable face and person matte across the complete entrance;
  reject the effect when hair/background separation is insufficient.
- GPU/compositor effects: opaque person core plus a genuinely inferred soft hair alpha; black
  stage; 100–250 ms original-plate reveal; no face-rectangle approximation for hair.
- Device-tier fallback: use a strict whole-person core without a fake hair rim and choose another
  transition when a validated hair matte is unavailable.

## Acceptance gates

- Required number of distinct source moments: every rendered entrance frame must advance source
  PTS; minimum 20 unique decoded PTS across the rise.
- Maximum repeated crop/range: no frozen source frame and no scale change above 1%.
- Cut/beat tolerance: hair-on-screen, full-face, shoulders, and plate-reveal landmarks within
  50 ms of the authored profile.
- Visual checks on exported MP4: no original background before plate reveal; no detached islands;
  no global outline; hair boundary remains narrow and soft; shoulders/body remain opaque; no
  double edge or matte breathing over three consecutive frames.

## Rights boundary

- Do not copy or bundle the supplied footage, audio, or creator assets in the application.
- Only the timing and compositing grammar above may be implemented as an original engine profile.
