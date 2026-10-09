# FEAR: visual effects study and implementation plan — 2026-09-24

## Evidence and limits

This study uses decoded frames of the recovered 1080 × 1080 reference at 30 fps and the current
emulator render. Detailed sheets are stored in `artifacts/fear/effect-comparison-2026-09-24/`:
`reference-opener-study.jpg`, `reference-title-study.jpg`, `reference-cascade.jpg`,
`reference-cascade-mid-67ms.jpg`, `reference-late-cascade-study.jpg`,
`reference-post-shutter-study.jpg`, and the two shutter sheets.

The reference is a finished edit, so some apparent movement belongs to the filmed performance or
camera. An effect is most convincing when the entire background scales with the subject, repeats
at the same place in several shots, or changes abruptly with the music. Numeric zoom strengths
below are initial implementation ranges, not values measured directly from source plates.

## Effect vocabulary

| Effect | Reference evidence | Current FEAR | Proposed implementation |
| --- | --- | --- | --- |
| Slow push / pull | Portraits drift in scale within roughly 0.4–0.5 s shots; clear examples are 12.0–12.4 and 14.4–14.8 s. | Alternating 0.98→1.055 and 1.05→0.99 across whole shots by index. | Preserve a subtle 1–3% base drift, but select direction by shot and source motion rather than index modulo three. |
| Short zoom punch | A stronger scale rise is visible near 8.87–9.03 and 9.40–9.53 s, followed by a hard cut back to a wider scale. Similar accents recur before later cuts. | No separate end-of-shot acceleration. | Use 3–6 frame eased keyframes in the last 100–200 ms of selected shots. Begin with about 4–10% extra scale, then calibrate against decoded frame pairs. Keep the pivot on the visible subject and crop safely. |
| Zoom smear / directional blur | The new portrait is heavily blurred at 5.10–5.17 and 10.97–11.03 s, resolving rapidly; 15.90 s shows the same phrase-entry pattern between shutter frames. | Three correctly placed, 140 ms defocus nodes, sampled as a uniform disk blur. | Retain the measured cues. Add a brief scale-aware radial smear on the first 2–3 visible frames, tapering faster than the defocus. Do not blur ordinary cascade cuts. |
| Chromatic edge split | Subtle red/cyan contour separation accompanies the more intense title/cascade accents. | Generic RGB split on every third cascade scene. | Schedule a small 1–3 frame split at measured accent cuts and drive pixel offset from cue strength. Omit it on quiet cuts and full-black/white frames. |
| Short image echo | Some scale accents have a soft repeated edge rather than a clean single contour; evidence is less certain than for the zoom and blur. | A generic temporal double exposure on every fourth scene, using a 100 ms earlier source frame. | First verify the echo in enlarged frame pairs. If confirmed, render 1–2 faint copies at nearby scales for 2–4 frames, anchored to the same subject. Avoid unrelated earlier source poses. |
| Film texture and title flicker | Fine grain/line texture is prominent over the `FEAR` title around 4.5–5.1 s. Some fine patterns may come from the TikTok transcode. | Global grain, horizontal scan modulation and vignette; title opacity already follows two stages. | Keep the title timing. Compare static-frame texture before adding anything: tune grain and line strength by phase, and do not reproduce compression artifacts. |
| Visible-frame light breathing | In the shutter phrases the black frames are exact; visible portraits vary in brightness, especially around 10.03–10.33 s. | Exact black/white pulses, with no separately authored visible-frame light envelope. | Measure luma on the non-black frames and add a bounded exposure curve only if variation remains after accounting for the footage. Preserve 30/30 black frames and the five-frame finale. |
| Restrained contrast grade | Reference portraits have deep shadows and readable faces; the title is more textured than the clean cascade frames. | One FEAR gamma/contrast treatment plus small RGB biases. | Tune role-specific shadow and highlight curves using a common test source and face protection. Keep the grade adaptive so bright user footage does not clip. |

## Implementation sequence

1. Measure background and face scale on 30 fps frame pairs around candidate zooms; annotate the
   confirmed cue windows. Separate source-native motion from whole-frame digital scaling.
2. Replace the cascade's modulo-based transform choice in `FearDirector` with an authored cue table.
   Use several `ClipTransform` or `ParameterTrack` keyframes for base drift and short zoom punches;
   preserve existing cut timestamps and 549 output frames.
3. Add a FEAR-specific short radial smear and measured chromatic cue schedule to the GPU effect
   graph and renderer. Keep the three established defocus entrances.
4. Evaluate echo at enlarged frame-pair scale. Implement it only where confirmed, using a
   FEAR-specific spatial/temporal layer instead of the generic 100 ms previous-frame blend.
5. Calibrate title texture, visible-frame light and grade after the motion effects are stable.
6. Render all three control sources on the emulator. Compare decoded frames at 1× and 0.5× and
   retain the exact shutter, finale, black-tail, face-visibility, A/V sync and beat gates.

The first implementation target is the short zoom punch: it is the largest remaining perceptual
gap and can be added without changing the accepted cut and shutter choreography.

## Measurement update

The Android emulator decoded the recovered reference at 8.80, 8.90, 9.00, 9.40 and 9.50 s.
The face detector measured frame-width fractions of 0.695, 0.753, 0.935, 0.612 and 0.718,
respectively. This supports a rapid approach just before the 9.03 s cut, followed by a wider
new shot and another approach. Because the performance itself may move toward camera, these
values constrain the *timing* of our added scale, not a literal digital zoom amplitude.

Enlarged reference pairs did not establish a separate delayed-image echo. The previous
every-fourth-scene temporal double exposure was removed; it created arbitrary earlier poses.
Visible-frame luma in the shutter varies substantially, but the underlying source lighting
varies too. No independent visible-frame pulse was added: the 30 authored black frames and
`WW/B/WW` ending remain untouched.
