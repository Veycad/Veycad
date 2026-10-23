# Shared text persistence v8 — source-only checkpoint

Prepared 2026-10-18 from published `codex/samsung-integrated`
`891dfb9d2230877a03530a787aa30a12f5b22ad9`, in isolated clone
`AutoEdit-text-persistence`, branch `codex/text-persistence-v8`.
Inputs: [A2x compatibility contract](../superpowers/specs/2026-10-11-text-core-compatibility-contract.md)
and [text-owner acceptance matrix](text-core-a2x-acceptance.md). This is a draft
implementation candidate; neither owner approval nor A2x acceptance is claimed.

The sole `HybridProjectCodec` writes physical version 8, retaining schema 1.
After each style's existing fields it writes nullable `plate`; after each layer's
style, `kind`; after each cue's interval, nullable Double `confidence`,
`manuallyReviewed`, then `confidenceCalibrated`. `needsReview` stays derived.
Owner values in `TextEditProject.kt` are unchanged. Unknown enum names and invalid
confidence values/combinations use the existing bounded reader and owner
constructor validation.

Readers pass the physical version through project/revision text and nested manual
graph paths. Versions 1–7 retain their existing field order; pre-v8 text defaults
are `plate=null`, `kind=LEGACY`, `confidence=null`, and both flags false. MONO/SCALE
remain valid enum names for old v4; long LEGACY layers are not reclassified.
Music source offset remains gated at v7. Shared caption projection explicitly
uses SUBTITLE with the existing end-exclusive time interval.

No store, model, renderer, audio, UI, source-map, attachment, allocator or export
implementation is replaced. The existing store keeps CURRENT read-only on open
and retains published immutable revision bytes on later saves. Version 8 is a
forward format: apps whose reader supports only up to v7 cannot open v8 saves;
there is no downgrade writer. No user project has been opened/upgraded here.

Source tests were added to existing suites for real temporary-file
create/save/reopen, original/current/undo/redo, stale CAS, both resets, retained
export after history pruning, unchanged committed blob bytes, nullable/explicit
plates with both Boolean values, all layer kinds, 2000-unit LEGACY, cue review
derivation, preserved v7 music offset, caption projection and invalid v8 payloads
in both project and standalone revision readers. Current-version assertions were
updated to 8. Existing authentic v1–v5 resources are unchanged. Mutated v8 bytes
in negative tests are explicitly corrupt payloads, never legacy captures.

**NOT RUN — explicitly requested by the user:** tests, compilation, Gradle,
lint/syntax checks, native/emulator/runtime verification, review, acceptance
matrix and APK build. No new encoder or fixture capture was executed. Only
source reads/edits and Git staging/diff/whitespace hygiene accompany this commit.
These test sources are unexecuted and do not establish PASS.

**OPEN before acceptance/merge:** genuine unchanged a531-v4 production-writer
MONO/SCALE project/revision capture (including original/current/history/export),
and genuine preceding-v7 production-writer capture with provenance and hashes.
The existing v4 empty-text fixtures cannot prove that regression. Neither gap is
filled by a hand-written encoder, patched header or fabricated legacy fixture.
Their capture and reader regressions require an authorized execution slot.
Independent scoped review, text-owner integration review and applicable exact-head
checks/full A2x matrix remain open. This unvalidated migration must not be treated
as accepted for merge or release. Native UI/STT/MP4, physical device and human
acceptance remain separate gates; previously delivered APKs are unchanged.
