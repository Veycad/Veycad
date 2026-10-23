# Prepared smart-framing semantic contract

Task5a supplies analysis values, cache v19, and the immutable track restore contract.
Actual semantic blob v2, lazy reader, DraftSemanticAsset.smartTrack, and publication
before READY belong to the common storage owner in Task5b/Task3. They are pending.
No project store or competing source identifier is introduced here.

## Face evidence

LocalSemanticFrameAnalyzer.Result, VisualEventMap.Observation and FrameAttachments
carry `detectedFaceCount: Int?` and independent `faceInferenceSucceeded: Boolean`.
Successful inference counts **all** faces (0, 1, 2, ...), while face/faceRegion retain
the largest detected face. Failure/unrequested inference yields null; null never
means zero and must never be inferred as one from a largest-face region. Non-null
counts must be nonnegative and require successful inference. Legacy callers may
have successful inference with unknown count; they remain conservatively UNKNOWN.
Interpolation takes count and success from the same nearest sparse attachment,
without averaging counts. Analysis cache v19 persists both fields in observations
and attachments; v18 and all earlier layouts are rejected, not silently upgraded.

Reliable body segmentation with zero faces can TRACK a person facing away. Zero
faces without reliable mask evidence is NO_PERSON (following any existing hold).
Multiple faces or ambiguous foreground is AMBIGUOUS and immediately centered.
Unknown count is immediately centered UNKNOWN, even following reliable tracking.

## Immutable header payload needed from common storage

Persist the precomputed value without retained masks, images, models, or playback
state. Store `SmartFramingTrack.GENERATOR_VERSION = 1`, the canonical source windows,
and ordered points. Window fields are `startUs: Long`, `endUs: Long` (exclusive).
Point fields are `sourceTimeUs: Long`, `centerX: Float`, `centerY: Float`,
`status: SmartFramingStatus`. PTS is display-oriented **source** microseconds;
centers are normalized display-oriented bitmap coordinates [0,1]. Stable status
tags for a future binary header should be explicit, not Kotlin enum ordinals:
TRACKING, HOLDING, NO_PERSON, AMBIGUOUS, UNKNOWN. Each point needs a timestamp,
two floats, and one status tag (17 payload bytes with a one-byte tag).

The builder sorts/merges overlapping or touching windows, resets processing at
gaps, and retains only points within windows. Restore with
`SmartFramingTrack.fromPoints(points, sourceWindows)`; this copies into unmodifiable
lists and rejects unsorted/duplicate PTS, noncanonical windows, out-of-window
points, invalid coordinates, and negative PTS. The windows are essential: restoring
points alone must not interpolate through omitted source spans. Sampling outside
windows gives centered UNKNOWN. Sampling uses immutable piecewise linear knots
and left-knot status; equal source PTS is independent of seek order.

Semantic asset cache identity must bind physical source hash, analysis version
(v19/profile/cadence as appropriate), and track generator version; include the
retained source windows in the header/validation because they affect the track.
The common resolver owns physical asset and selection identifiers. Selection
framing binds to selection ID later; do not derive identifiers from seek order.

The common lazy reader must expose `smartTrack(): SmartFramingTrack?` from this
compact header without loading full semantic planes. Preparation must atomically
persist/validate blob v2 and track before READY becomes observable. Existing
projects need explicit compatible-version/null handling. The common owner must
validate bounded header/window/point counts before allocation and account for
track/header, semantic planes, thumbnails, preview caches and FBOs in the shared
additional **32 MiB** application budget; MediaCodec buffers are measured separately.

## Initial generator policy (requires device/visual acceptance)

Threshold alpha >=0.5 for foreground. Two 4-connected components each containing
at least 15% of all foreground pixels and separated by normalized centroid
distance >=0.20 are ambiguous. Reliability requires mask confidence >=0.6,
subjectQuality >=0.6, subjectOcclusion <=0.35, foreground coverage 0.02..0.85.
The target equally averages alpha-weighted foreground centroid and foreground
bounds midpoint. An interior center jump >0.20 is rejected when the next reliable
center is not within 0.08 of it; surrounding centers need not agree with each other.
Endpoints cannot establish an isolated outlier without neighboring witnesses.
Smooth reliable runs with forward/backward source-PTS EMA, tau=300ms, resetting
at missing/ambiguous evidence and omitted windows.

For known-count mask loss, retain the last displayed reliable sample center for
500ms from its source PTS, then return to center over 300ms (HOLDING during that
return). Recovery blends from the displayed center to the smoothed target for
300ms from the recovery PTS. Sparse samples and transition deadlines become
piecewise linear immutable knots; no inference or elapsed playback clock runs
inside sample(). Null counts/ambiguity override hold immediately. FramingPlan must
subsequently clamp the sampled center for the actual source geometry, format,
and zoom; this format-independent track does not choose a crop rectangle.

Deterministic domain/cache tests and compiled device mapping source are partial
evidence. Real on-device ML 0/1/2-face/failure fixtures, mask ambiguity thresholds,
natural movement review and end-to-end persisted READY restoration remain pending.
