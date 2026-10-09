# Event-driven Veycad engine

The reboot branch now has a renderer-independent decision pipeline:

1. `AudioBeatMapAnalyzer` converts mono PCM into sample-indexed beats and onsets. Every event has
   normalized strength and a low/mid/high/broadband classification; downbeats are inferred from
   the strongest four-beat phase.
2. `VisualEventMapAnalyzer` converts local vision observations into camera motion, subject motion,
   face turns, gestures, occlusions, stable subject reveals, composition peaks and clean holds.
   Camera and subject motion are separate inputs by contract. A subject reveal additionally
   requires person-mask confidence and temporal IoU.
3. `EventMatchingDirector` creates dynamic, balanced and cinematic alternatives. Output boundaries
   follow the audio clock, source windows are unique within each alternative, and static material
   receives restrained push/pull virtual-camera moves. Debug AUTO validation renders all three,
   runs decoded-MP4 acceptance on each and ranks only accepted candidates against the reference
   rhythm, shot count, hard-cut dominance, role vocabulary and semantic transition grammar.
   A device-generated 20-second near-static master verifies this path end to end: all 13 clips in
   the winning dynamic graph use deliberate virtual-camera motion, with no invented whip.
4. `EffectOrchestrator` validates every semantic transition and applies an accent budget, global and
   per-effect cooldowns, and incompatibility rules. A whip without horizontal camera-motion evidence
   is downgraded to a hard cut; foreground re-entry without an actual PTS-bound mask plane is also
   downgraded. Blackout is restricted to an original hard cut on a strong low-band downbeat.
5. `RenderPassPlanner` describes fused cheap work and dedicated foreground, directional-blur,
   depth and glow passes at a resolution selected from explicit device capabilities.
6. `RenderedMp4Acceptance` compares decoded output measurements with a reference montage card:
   beat-hit rate requiring both a sample-clock beat and a decoded pixel-change peak, repeated source
   time, visible transition strength, A/V drift, artifacts, colour
   discontinuity and face loss. Face loss is measured by decoding output YUV frames, converting a
   compact local RGB sample and running the bundled face detector wherever the selected source PTS
   contained a confidently detected face; missing evidence can no longer pass as a zero loss rate.

## Deliberate boundary

`MediaCodecAudioDecoder` now feeds a separate real music file into `AudioBeatMap`, and
`MediaFrameVisualAnalyzer` supplies a pixel-based motion/composition fallback. The debug-only Golden
runner rejects attempts to use the video source as its music source and keeps fixtures outside the APK.
Both product callers and the Golden runner use the same `VeycadAutomaticEditor` orchestration
contract; a candidate cannot be returned unless its encoded MP4 passes decoded acceptance.
The reusable reference montage card is also evaluated as an explicit structural metric: shot
cadence, number of distinct moments, hard-cut ratio, semantic role coverage and transition
vocabulary. A candidate below the grammar threshold is rejected rather than merely ranked lower.
Automatic edits require at least 15 seconds of source, target an 18-second story arc, may extend to
22 seconds, and snap the ending to a strong nearby musical boundary. Rendering then runs through
GLES, H.264 and the owned AAC mux.
It sequentially decodes the completed MP4 back into the acceptance gate using actual frame PTS.

The local Android perception path now samples bundled ML Kit person segmentation, face pose and
body pose alongside the luma motion fallback. It emits compact PTS-bound masks only when coverage
is plausible, measures temporal mask IoU and derives gestures from wrist pose. When pose depth is
unavailable, an explicitly labelled semantic monocular-depth estimator combines the person matte
with composition scale to create stable near-subject/far-background separation. A compact dense
block-flow field is generated between every pair of sampled frames and uploaded to the transition
shader for measured blur direction. Learned flow/interpolation and true metric monocular depth are
still required for generated slow-motion frames and physically accurate parallax.
The GLES backend executes planned directional blur, semantic-depth parallax and two-pass glow in
dedicated RGBA framebuffers at the resolution selected by device capabilities. A depth pass is
recorded only after its actual FBO draw call. The API 36 Golden-0 regression produced 49 depth planes
and eight depth draws while retaining 100% beat hit, 25.334 ms A/V drift and passing decoded
acceptance. Physical A25 verification is still required.
