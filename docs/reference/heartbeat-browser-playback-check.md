# Heartbeat local playback checkpoint — 2026-09-07

This is browser playback verification, not Android player acceptance or full
perceptual audiovisual review. No new render or reference-level quality claim.

Added `tools/heartbeat_review_server.py` and `heartbeat_review.html`: loopback
127.0.0.1 only, read-only mapping of exactly three supplied MP4s plus the page,
no directory listing or third-party requests. Byte-range unit tests: 3 passed.
Golden/reference assets are not copied into application resources.

Inputs:
- User-authorised reference: Downloads/ssstik.io_1788734312850.mp4.
- Prior synchronous control: heartbeat-timepair-g0-0907-synchronous-control.mp4.
- Combined candidate: heartbeat-combined-g0-0907.mp4.

Actually exercised through browser UI: start at 0 at playbackRate=1; observed
advancing video images and clocks at approximately 10.2 and 20.4 s; replay from
11.75 s; all three eventually reported ended=true and error=none. Candidate
muted=false; other videos muted=true. Screenshot at ~20.4 s showed all three
authored black tails. No decoder-reported error; candidate droppedVideoFrames=0
at ~20.4 s of first pass, 1 after the subsequent seek/replay. Those counters are
browser scheduling evidence, not a montage-quality score or Android guarantee.

The three independent clocks differed by about 30–40 ms during playback.
Do not use apparent side-by-side beat offset as evidence of MP4 A/V drift.
The viewer explicitly warns about independent clocks. Exact comparisons remain
based on matched output/source PTS and decoded-frame evidence.

Observation limit: visual evidence was sampled screenshots during actual 1x
playback, not continuous human-like motion perception or subjective listening.
Music presence/unmuted state was verified; quality of sound and musical feel
remain unaccepted. Prior face-readability improvement remains scoped to stills.
Next quality work must evaluate the whole montage rhythm/shot progression,
not treat this technical playback pass as visual acceptance.
