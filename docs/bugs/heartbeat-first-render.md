# Heartbeat first real render — 2026-09-07

`artifacts/reference-analysis/heartbeat/heartbeat-first-g0-0907.mp4`
completed on API36 emulator: 1271 frames, 720×1280, 60 fps, video/audio PTS zero,
last-PTS A/V drift 13,288 us. AVFoundation opens the MP4 with one audio track;
container duration 21.1833 s includes final frame duration. Full-length 500 ms
decoded sheets are in `heartbeat/first-g0/`. This is not a completed 1× review.

Decoded frames show source-plan changes and a black tail. Compared with reference,
the transitions lack blur/stretch, echo is weak, footage has much less scene
diversity. No quality-parity claim. Current generic QA rejects beat hit rate
(0.30769), repeated source moments, transition artifacts and colour jump.
Do not simply disable those gates: authored repeats/pulses need explicit expected
event coverage, and musical alignment still needs independent validation.

Found and fixed after this render: rounded-ms overlay boundaries could skip
single-frame 60 fps pulses. Layer evaluation floors PTS, so overlay boundaries
now floor consistently. Test compares every pulse's expected 60 fps timestamps
with actual layer sampling. All tests/lint/build pass; new MP4 still required.

Remaining diagnostic debt: inherited `matched_events`/`virtual_camera` counts
describe the source-pool alternative, not the transformed Heartbeat graph.
Generic reference metrics do not prove Heartbeat-specific timeline recall.
Pass report lists only source/encoder passes despite inline procedural layering;
distinguish inline shader effects from dedicated framebuffer passes.
