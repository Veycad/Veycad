# Authored source maps and measured source bounds (A2a)

`HybridProject` / `HybridRevision` remain the single editing state. Commands own
revision IDs, history and selection; `HybridProjectStore` owns publication/CAS.
No renderer, inspector, gallery transaction, manual effect payload or second
project store is introduced here.

## Coordinates and unknown legacy facts

`SourceTimeMap` points are exact, nonnegative **absolute decoder request targets**
in microseconds at local output-frame boundaries. They include the exclusive
boundary. They do not prove an actual frame exists at a requested timestamp.
`originalFrameOffset` remains the signed original output-frame phase at local 0.
Opening old bytes never shifts or rewrites these targets.

`MediaSource` and `VideoSourceMetadata` carry nullable `firstVideoPtsUs` and
independently measured exclusive `videoEndPtsUs`. Null means UNKNOWN, including all
preceding constructors and v1-v4 files; it is not a synthetic zero origin or a
computed endpoint. `importVideo` carries both measurements, while same-project
published metadata remains immutable. The
Android inspector and the requirement for measured gallery draft promotion belong
to B; existing legacy import APIs can still represent UNKNOWN.

`SourceTimeBounds(durationUs, firstVideoPtsUs, videoEndPtsUs)` preserves the positive
**declared/container** duration. It validates nonnegative origin, positive endpoint
and strict measured endpoint order. It never adds declared duration to origin:
container duration can already include an initial empty edit. `measuredRawEndUs`
is the nullable independently measured endpoint; `measuredContentSpanUs` exists
only with both facts and is `end - first`, safe for ordered nonnegative Longs.
`requestEndUs` uses the measured raw end when available, or the preceding
conservative declared-duration cap for UNKNOWN. `requireRequest(target,
exclusiveBoundary)` admits requests from zero to the exclusive end (only a map's
boundary may equal that end), including requests before the first observed frame.
`requireActualPts(actual)` requires both known endpoints and enforces `[origin, rawEnd)`.
It never manufactures observation evidence from requests. For origin 120000 and
declared duration 500000 and measured end 620000, request 586666 is valid; actual 0
is invalid. With first 120000, declared duration 1294300 and measured end 1294334,
the usable measured span is 1174334, and last actual PTS 1261000 can be held.
The guessed endpoint 1414300 is never produced. Origin alone leaves the endpoint
unknown and preserves the old declared-duration request cap. A known end with
unknown first can bound requests but cannot validate actual observations.

## Selected hidden curves

`HybridRevision.sourceBackings` is an immutable map keyed by clip ID. Each
`HybridSourceBacking` identifies its physical asset, immutable full selected map,
starting output phase, accumulated source slip, selecting revision ID, and whether
the original automatic curve was explicitly selected. The selecting ID remains
provenance even after that revision leaves undo history. Map and container inputs
are defensively owned; construction/copy of a backing is internal to the core.

Core MoveCut and SlipClip retain the selected backing in their single allocated
revision. Slips translate the backing without copying or re-rounding hidden
samples. Hidden samples may become unavailable after a slip takes them outside
the source, while the visible window remains valid. Retained holds are legal;
extrapolating new frames from a zero-slope edge is rejected.

An external CommitRevision may omit the backing table (no provenance claim), or
carry the current table unchanged. A different nonempty table is rejected. The
core preserves the current selection for unchanged source identity/descriptor and
an exact candidate window, checking every requested sample including its exclusive
end against the currently selected curve. It does not pick a compatible original,
older or redo curve based on coincident overlap. A genuine mismatch selects the
new visible curve only. Hidden samples cannot be supplied implicitly by a new
curve with the same visible subset. Reorder, transition-only and author-only
changes preserve the selected source curve.

RestoreAutomatic and RestoreMontage explicitly select the original curves;
their reset event stays local to that revision, while durable selection survives
later author changes and pruning. Scoped reset preserves author fields; full reset
restores them. Undo/redo restore the exact table and never reuse allocated IDs.

Legacy revisions have an empty table. A real command retains the full envelope
proven by contiguous selected ancestry under the preceding comparison rules.
It reuses a containing curve only when that curve represents all proven windows;
otherwise it materializes exact samples with current/newest-window precedence.
Materialized samples use the oldest proven window's raw coordinate system and
retain its accumulated slip separately. This keeps hidden requests that a legal
slip temporarily moves outside the source; only an attempted visible extension
to those requests is rejected. Reversing the slip can make them available again.
Reset, rewrite and gap barriers still bound this envelope. Missing proof retains
the visible window only or rejects an extension; no unseen samples are invented
and old revision blobs are never rewritten. Automatic continuation survives only
when the immutable original curve actually represents the selection; another
recovered envelope preserves its saved boundaries but rejects continuation beyond
them. Legacy proof, envelope materialization and candidate-window comparisons
reserve work before enumerating sparse frame spans, with a shared
1000000-comparison command budget and the existing map point bound.

## Physical storage and capacity

Physical codec **v5** reads explicit v1-v4 layouts. v5 clip maps and backing maps
reference `projects/<id>/authored-maps/<sha256>.map`. Hash input is big-endian magic
0x56534d50, format 1, point count, then exact `(Int frame, Long targetUs)` pairs.
Identical point sequences deduplicate without lowering precision. Analysis owns
only `analysis/`; authored maps must never be evicted or regenerated as ML cache.

The codec accepts one `AuthoredSourceMapStore` reader/writer boundary. Each load
shares one immutable map instance per hash across original/current/undo/redo and
backing references. A separate `loadRevision` has its own load-scoped cache and
validates the revision against the project's physical assets. Standalone codec
callers may pass a map store explicitly; its compatibility default is a sibling
`<analysis-directory-name>-authored-maps`, outside the analysis directory.

The existing 16 MiB project/revision envelope, 50-step history, generic list/item
bounds and per-map 1398101-point bound remain. The map file includes a 12-byte
header, so maximum file length is 16777224 bytes. Writer and reader additionally
bound **unique** authored map bytes per snapshot to 128 MiB. This supports, for
example, 50 distinct dense 120001-point maps (72001200 bytes) and arbitrarily many
references within the generic manifest bounds; it does not promise unlimited
products of maps and history. An over-limit snapshot fails explicitly without
truncating history/samples. Header/count/length/hash are verified before allocating
points; aggregate allocation is reserved before reading each unique payload.
The writer first streams the canonical digest with bounded scratch space, then
reserves the known unique byte extent before allocating its payload. Equal-content
maps consume that budget once even when their object identities differ. A hash
becomes verified only after successful file verification/publication, and a failed
attempt releases its reservation without leaving a trusted cache entry.

Maps and revision blobs are immutable, synced and written before the sole CURRENT
replacement. Existing files are verified, never silently repaired. An interrupted
publication leaves the previous pointer/readable state and may leave unreferenced
immutable files. Future cleanup belongs to the runtime lease/retention owner.
Missing/corrupt author data fails explicitly, separate from
`missingAnalysisHashes`; no approximate curve or analysis regeneration repairs it.

The tests use real temporary files, CURRENT fault injection, immutable v4 fixtures,
pruned histories, exact samples/phase, duplicate-reference identity and capacity
checks. Android EOF/VFR measurement, visual acceptance, A2b payload and full CI
gates remain separate validation responsibilities.
