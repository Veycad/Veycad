# VME-002 — overlap target can exceed the last decodable source PTS

- Status: fixed and verified with separate phonk music
- Scope: local API 36 emulator, Golden 0 with separate phonk music
- External reporting: forbidden; keep local

## Reproduction

Build a DYNAMIC graph from the separate `pixabay_drift_phonk` beat map. One selected source
window reaches the end of the video. The temporal overlap planner uses the container duration as
its upper bound, but the final coded video sample has an earlier PTS.

## Symptom

`Outgoing decoder did not reach overlap frame` aborts the export before a complete MP4 exists.

## Fix

`DecoderCursor` now remembers whether it has produced a valid texture. At physical decoder EOS,
targets beyond the last coded PTS reuse that last texture for the remaining tail samples. Output
PTS and AAC duration remain unchanged; the renderer never substitutes an empty/black texture.

## Verification

- 8-second DYNAMIC output: 240 frames, 14 clips, acceptance passed.
- 18-second DYNAMIC output: 540 frames, 13 clips, acceptance passed.
- Both used `pixabay_drift_phonk` as a source distinct from the video master.
- 18-second result: beat-hit `1.0`, artifact score `0.0`, A/V drift `17,334 us`.
