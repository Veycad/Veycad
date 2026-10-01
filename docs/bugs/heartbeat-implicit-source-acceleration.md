# Heartbeat: accidental source acceleration

Confirmed from `heartbeat-combined-g0-0907-inspector.json`: constant speed-curve
value 1 was not absolute playback speed. The director copied a selected source
range into a shorter authored output slot without trimming source handles.
Effective source/output slopes included 2.35x, 2.61x, 2.9x and 3.33x. Output
15.3–15.783 s advanced through about 1.26 s of source (2.61x). Previous reports
showed speed=1 because they described only the curve value.

Fix: retain each selected role's source midpoint, trim its handles to at most
the output-slot length. Preserve all clip output times, pulse timing, music,
source bounds and role order/reprises. Existing shorter source ranges still
slow down; this is not optical-flow interpolation. No acceleration is inferred
merely from slot length. Deliberate reference speed ramps remain a separate
unmeasured requirement, not claimed implemented by this correction.

Unit tests cover source bounds, midpoint within 1 ms, source span <= output
span, and frame-plan slopes <=1 (rounding tolerance). Inspector additionally
records nullable effective_source_speed from adjacent planned source/output
PTS, leaving the old speed curve value distinct. Test covers a 1-valued curve
with actual 3x movement and reset at clip boundaries.

Verified Android Emulator output: `heartbeat-natural-clock-g0-0907.mp4`.
1271 frames, max planned-source slope exactly 1.0, AVC/AAC 720x1280, rotation 0,
container accepted, A/V last-PTS delta 13288 us, 26/26 pulse checkpoints.
General visual QA remains rejected. The render preceded the diagnostic-only
effective_source_speed field addition; slope was independently calculated
from its source_us/output_us fields, not from the old misleading speed value.

Browser UI playback reached ended=true at 21.183 s, rate=1, error=none,
1271 total frames, 0 dropped, candidate unmuted. This verifies desktop-browser
playback, not subjective listening or Android product preview acceptance.
Decoded stills are under `natural-clock/`. Source windows intentionally changed;
do NOT describe before/after stills as identical-footage shader comparisons.

Tests, lint and assembleDebug passed for both director fix and final diagnostic
addition. This is a real timing correction, not reference-level style approval.

## Safe neighbouring source handles

Next measured issue: clips 14/25 ran at .586/.569x with only 20/19 unique
decoded source frames across 66/67 output frames. Added optional expansion to
1x around the selected midpoint when existing semantic timeline evidence is
continuous (endpoint tolerance 125 ms, interior gap <=300 ms), face confidence
>=.65, subject quality >=.55 and occlusion <=.35. Missing/unsafe coverage keeps
the original shorter range; no synthetic frames or unconditional extension.
Tests cover absence, temporal gaps, occlusion, source boundaries and full graph
activation with readable evidence.

`heartbeat-handles-g0-0907.mp4` completed on emulator. Clips 2/8/12/19/23 moved
from .857–.873x to 1x; unique source frames increased to 27/33/29/34/28.
Clips 14/25 FAILED the suitability gate and remain .586/.569x (20/19 unique
frames). Therefore the most severe slow plans remain unresolved: this is only
a partial correction, not a claim of fully smooth output. All output cut times
and 26 pulse checkpoints retained. Container accepted; general QA rejected.
Decoded new handles (`handles/sheet-0.jpg`) were visually checked for source
coverage; no subjective continuous-motion or music-quality acceptance inferred.

Six completed face-supported original/refined g0/g1/g2 private test copies
were archived in face-supported-private-backup.tar, every member SHA-256 checked
against its emulator source before deletion. Original user videos untouched.

## Paired handle experiment (in progress)

The subsequent shifted-handles render selected different source events than
the centred-handles run. Its smoother clip 14/25 therefore did not establish
a causal improvement from handle shifting. Do not compare those two independent
analyses as if they were the same input selection.

Added a debug-only paired path: retain the exact analysed DYNAMIC pool, render
the shifted recipe, then build the centred-only control from that same pool.
Both use synchronous echo and the same authored output timing, music and GPU
effects. A new unit test exercises a genuinely different source window and
asserts graph equality apart from clip source start/end; the original pool is
unchanged. Full unit suite, lint and APK build passed 2026-09-07.

Current run: `heartbeat-paired-handles-g0-0907`, emulator-5554. Completion requires
both the main marker and the `-centred-handles-control.mp4.result` marker. At
dispatch neither visual acceptance nor an improvement was claimed. This is
still short of the first convincing Heartbeat deliverable.

### Paired result and next hypothesis

Both paired-handles MP4s completed. Every one of their 1271 inspector frame
records is identical (including both decoded texture PTS); there are no missing
records. File hashes differ, so byte identity is not asserted. Clips 14/25 still
have slopes .586/.569 and 20/19 unique source pictures. The bounded shift had
no effect on this pool. Main container passed (AV delta 13288 us); general QA
failed. Decoded stills at 10.7/11/11.3/11.65 and 18.75/19.1/19.5 show the same
profile turn; no new continuous-playback acceptance is claimed.

Inspector evidence around source 12.375–12.625 seconds shows a short missing
face region during that profile turn, despite mask confidence around .95,
no occlusion, and mask temporal IoU above .65. The strict all-samples-face gate
therefore conflates a detector gap with unsuitable footage.

Next narrow experiment allows one faceless sample only if adjacent samples
both have confident faces and bracket it within 500 ms, its mask confidence
is >=.9, IoU >=.65, quality >=.5 and occlusion <=.35. End gaps, consecutive
missing faces, missing/unstable masks and occlusion remain rejected. This is
not identity recognition, face reconstruction or an optical-flow model.
Negative and positive unit cases pass; full tests/lint/assembleDebug passed.
`heartbeat-profile-gap-g0-0907` compares it to `-strict-face-control` on the
same analysed pool. Visual improvement remains unverified until that pair is
finished and reviewed; Heartbeat remains unavailable in the product picker.

Installation initially failed with INSUFFICIENT_STORAGE; no new run started
on the old APK. Five completed paired/shifted MP4 copies were removed from the
emulator only after SHA-256 equality with retained local project copies was
verified (about 66 MiB). Source fixtures, drafts and My Edits were untouched.
Installation then succeeded and profile-gap/strict-face paired rendering started.

The preceding paired-handles videos were also played at 1x beside the reference
in the local browser. All reached ended=true with no media error, candidate
unmuted; browser dropped 4/4/6 frames respectively under simultaneous emulator
load. Screenshots during playback around 6.4 and 14.2 s and the black ending
were inspected. This is sampled temporal observation, not continuous human
viewing/listening or frame-accurate sync validation. The pair remains visually
unapproved; no perceptual benefit follows from its identical frame evidence.
