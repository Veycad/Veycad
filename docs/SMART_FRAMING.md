# Saved SMART framing

The common project's selection ID, inspected geometry, source hash, current aspect and
`SourceFramingSettings` are authoritative. The manual editor exposes MANUAL,
SMART_PERSON and BLURRED_FIT for each selected source and aspect. Changing modes
preserves each mode's saved settings and uses the existing revision/CAS/undo path.
An explicit project intent opens that project at zero; recreation restores its saved clock.

SMART preparation runs on a media worker before preview READY, or after display/audio
release and before export admission. It uses the existing indexed
`SequentialBitmapDecoder.decodeActual`, `LocalSemanticFrameAnalyzer` and shared
`MediaFrameVisualAnalyzer` subject metrics with EDITORIAL_SEMANTICS and a250 ms cadence.
The measured raw window is `[firstVideoPtsUs, videoEndPtsUs)`; a shorter declared
duration never clips its real tail. Every indexed target must equal its decoded PTS.
The existing analysis limits remain:60 seconds of measured content,240 samples,
maximum decoded side480,128 MiB estimated attachment data and checked disk headroom. Analysis planes are
transient producer data; the returned immutable track contains only source PTS,
normalized centers, status and usable windows.

`MediaFrameAnalysisCache` stores the derived compact track in its existing cache
directory. Its identity includes the SHA256 source hash, raw first/end PTS, duration,
complete usable windows, analysis profile/version, cadence and SMART generator version.
Payloads are bounded to128 KiB/4096 points, have a SHA256 footer, and validate counts,
tags, coordinates, ordering and trailing bytes before restore. Corruption becomes a
cache miss; regeneration must finish before READY. At most64 compact entries remain.
The project codec stores the mode/settings, never this disposable derived cache.
Missing or corrupt saved project analysis is a different condition: it is not silently
reconstructed with new model output. Manual opens that project read-only with an
explicit recovery message and backup entry, creates no preview consumer, and permits
neither edits nor export. Fresh mutation/publication reads also check the recovery flag.
Existing bytes and the store's pending recovery obligation remain unchanged.

The same table samples each incoming/outgoing/temporal role using that role's own
source PTS. Seeking and output FPS do not alter the track. Ambiguous or unknown face
evidence centers the frame; confident subject masks supply the center. Brief subject
loss holds500 ms and blends back over300 ms. The UI reports tracking, holding and
centered fallbacks. The shared GL budget charges compact tracks, and disposal drops
their references. A moving SMART crop does not add one Fear pivot-cache key per frame.

BLURRED_FIT preserves the complete foreground source. A centered fill is rendered
behind it at half resolution with a separable Gaussian (sigma2% of project short
edge, radius ceil3sigma). Two decoder roles have separate atlas pages with half-texel
insets. Existing GL allocation ownership reserves and releases all scratch targets
and programs, including partial construction. Published720p/1080p paths are supported.

Automatic and camera winners use the same common project handoff. Physical source
copies, inspected metadata, selection order and capture origin/recommended take are
saved before the completed result is linked. A failed result publication deletes only
its newly owned provisional project. Main opens the exact result project in Manual;
music/text and backup entries retain the same identity.

Verification is split deliberately: JVM fixtures cover deterministic source-time
sampling, cache validation, mode geometry and ownership invalidation. Native fixtures
encode all four aspects in SMART and BLURRED_FIT, check decoded pixels, validate the
production compact-cache path and capture project save/reopen/undo. Separate ML Kit
fixtures count0/1/2 real faces using blank and derived versioned reference frames.
Failure mapping is injected and identified separately from real model inference.
Passing these checks does not assert human visual acceptance or physical-phone behavior.

The owner excluded physical-device and emulator execution for this delivery. Native
fixtures are test source coverage only; runtime SMART, decoded pixels and actual
ML Kit face inference remain unverified until a separate acceptance run.
