# DUALITY_LOOP_V1 — Reference Montage Card

## Identity

- Reference: user-provided local MP4 `ssstik.io_@jxz.aep0_1789481933808.mp4`
- Observation method: temporal review of all 549 decoded frames plus 0.5 s contact sheets
- Confidence: high for duration, cut boundaries, repeated phrase and palette; medium for semantic intent
- Intended original profile name: `DUALITY`

## Montage grammar

- Energy curve: 3.700 s restrained opener → 0.50–0.60 s cut cascade → repeated rhythmic/visual phrase → 1.833 s bright cyan/white graphic impact, then decay. The earlier description as merely a dark/glitch release missed the graphic energy peak.
- Shot vocabulary: medium introduction, wider/body framing, profile/detail, back/turn, action, close, graphic finale. Two-file input is the user's product requirement, not evidence that the reference mechanically alternates two independent people.
- Cut rhythm: 25 output clips over 18.300 s. Measured boundaries are encoded in `DualityLoopProfile.boundariesMs`; the rapid section averages about 1.8 cuts/s.
- Motion language: contrast calm, readable framing against stronger subject/camera movement. Temporal samples around all cuts show differing apparent pace; softness is particularly visible just after 7.567 s and its reprise at 14.233 s. Whether this is captured motion blur or an authored treatment, and the original speed coefficients, cannot be established without the source takes. Virtual push-in/push-out remains restrained.
- Transition language: hard cuts carry the rhythm; there is no generic transition between every shot. Strong periodic RGB glitches are not evidenced by the reviewed reference samples.
- Palette and texture: low-key, high-contrast imagery with a cool/green bias, warm skin highlights and deep blacks. Bright daylight selfies cannot acquire the reference's physical lighting through a grade alone.

## What requires real source material

- Required capture prompts: two independent videos, each at least 6 s (12 s or longer recommended for distinct coverage); a clean uninterrupted 3.7 s hold in the anchor; several face/profile holds; several genuine turns, walks or lateral moves; visibly different lighting, wardrobe or background between videos.
- Cannot be credibly created from a single static take: the two-role call-and-response, genuine direction changes, different shot scales and distinct backgrounds.
- Product override confirmed by the user on 2026-09-16: omit the separate cyan animation entirely. Continue selected source footage to the end, retaining the author music and 18.300 s duration. The reference's graphic finale remains an observation, not a requirement for the app.

## Original implementation rules

- Timeline constraints: fixed 18.300 s author-score timeline; 3.700 s opener; two measured rapid phrases; final release from 16.467 s.
- Candidate selection: score complete windows from both videos for each role. Reprise the wider/action/profile/close vocabulary instead of imposing A/B alternation. Calibrate occupancy targets at joint human-observation percentiles (wide 10%, introduction 35%, close 85%) rather than interpreting occupancy as a universal shot label. Use a suitability gate from existing face/body/gesture evidence; a foreground mask alone is not evidence of a human. Within suitable windows favour framing and real gestures over face confidence; opener additionally penalises roll, scale/yaw changes and gesture activity. Score outgoing/incoming movement direction and deliberate scale changes. Minimum eight clips per source and maximum three consecutive clips from one source preserve the two-source feature; future-slot feasibility prevents deferring the weaker source to a forced tail. Penalise reused windows, occlusions, weak samples, source cuts and action peaks at boundaries. The 250 ms evidence remains heuristic, not exact-frame cut detection.
- Grade: DUALITY-only tone curve, softer highlights, restrained saturation and cool shadows. Soft RGB-domain warm-colour protection reduces darkening/desaturation of skin independently of decoder index. Converge 40% toward median luminance capped at 0.42, with exposure bias -0.18..+0.12; do not add negative exposure to already low-key windows. This is not learned skin segmentation or automatic white-balance inference.
- Retiming (revision 4): compare full source ranges at 0.90, 1.00 and 1.15 times the measured output interval. Prefer additional approach coverage for real action and restrained stretching of moving details/close shots; quiet material prefers 1.00. Use interior yaw progression, coherent flow or changing gesture evidence to gate a smooth fast-approach → slower-readable-moment → settling curve. Invert the renderer's integrated, normalised mapping so the selected source event reaches the slow part. Strength and event position vary the curve rather than repeating a fixed template. The actual rate is the source/output duration ratio multiplied by the normalised curve, not the raw speed-key label. Retain the measured cut grid and untouched music. 250 ms observations are approximate semantic evidence, not frame-perfect action tracking or emotion recognition.
- GPU/compositor (revision 4): remove the periodic glitch schedule. At up to six stronger action moments, use short decaying soft focus approaching the gesture, restrained lens/RGB displacement and a highlight glow; separate clusters by at least 850 ms. Snap the light accent only to a detected beat/onset within 85 ms of the movement accent, with clip-edge margins; otherwise retain the movement anchor. Lens displacement is not optical-flow interpolation or true motion blur. Clean shots remain between accents. The final selected source window stays visible through 18.300 s; no separate energy overlay, graphic plate, terminal fade or forced black frame is added.
- Movement refinement: signed median camera/subject flow is attenuated by directional disagreement. Up to 250 ms neighbouring evidence supports cut-direction estimates for short windows; it does not extend selected footage. Action windows reward coherent motion and actual yaw progression. Same-source pose/scale similarity is penalised only without those changes. Before exhausting a three-clip source run, one-step lookahead accounts for the next role's coverage loss. This is not emotion recognition or a guarantee of a serious expression.
- Device-tier fallback: the same two-source choices, cuts, curves and tone curve use existing render paths; soft focus/lens sampling is GLES2-compatible and glow uses the existing capability-selected path. Limit the normalised slow portion to at least 0.80 of the source/output interval ratio (approximately 0.72 actual rate for the 0.90 window option). This limits severe stepping but does not synthesize intermediate source frames. The finale requires no extra decoder, texture asset or dependency.

## Acceptance gates

- Required number of distinct source moments: at least 8 per source and both source indices present in the graph.
- Maximum repeated crop/range: target 20% aggregate overlap; zero exact duplicate windows and preferably zero overlap when sufficient readable coverage exists. The 6 s input minimum is a technical floor, not a promise of unique 18.3 s coverage: short inputs may fail the overlap gate.
- Cut/beat tolerance: 85 ms analysis tolerance and at least 60% detected-beat recall (the remaining measured cuts are syncopated picture accents); rendered A/V drift and duration error no more than one 30 fps frame (33,334 µs).
- Visual checks on exported MP4: 25 clips, both videos visible, actual changes of scale/profile/gesture when capture coverage exists; no forced A/B pattern; first hold readable; rapid cuts remain hard; cooler deeper tonal bed with warm readable skin and no clipping. Source footage continues after 16.467 s to the last frame, with no separate cyan animation or artificial terminal black. Face QA stays source-index-bound throughout, including the finale; no graphic exemption remains.
- Retiming checks: verify actual planned and decoded source PTS, not only curve labels; source time advances monotonically inside every shot, with genuinely differing fast/slow portions where suitable source movement exists. Quiet synthetic footage must not receive decorative ramps/effects. On moving synthetic fixtures, at least 75% of frames remain free of the short post-effect accents; test both import orders for identical physical windows and speed curves. Artistic likeness remains a temporal review, not a numeric similarity claim.
- Source-end safety for this 30/60 fps v1 pair: reserve 70 ms before container duration when selecting source ranges. This avoids draining reordered tail frames after the last useful PTS; it does not trim the 18.300 s output or add a freeze/fade to the ending. Broader frame-rate coverage is not validated by this fixed margin.

## V1 test scope

- Use only the two user-supplied test sources: `for_duality.mp4` and `for_duality_man.MOV`.
- Test both import orders against the same physical pair. Synthetic observation maps may test director invariants; they are not artistic validation on additional video footage.
- The reference and previously generated reference trims are not substitutes for these test sources.
- Findings on this pair do not establish reliability on unrelated capture conditions. Broader footage validation remains a separate future scope, consistent with the user's two-video-only restriction.

## Rights boundary

- Do not copy or bundle the reference footage, creator logo, captions or baked visual assets.
- The extracted music file is bundled only under the user's explicit statement that they hold its rights. Any replacement external asset needs equivalent licence evidence.
