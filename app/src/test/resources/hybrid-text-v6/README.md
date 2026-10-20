# Genuine preceding physical-v6 capture

These unchanged production store/codec outputs were generated on 2026-10-20
before changing the encoder to 7. Inputs are synthetic, including saved timing
metadata; no physical media inspection or timing measurement is claimed.

- Actual preceding main: `f96dcc54ec55c46fbf436c74b34dbdf8b091cdc1`.
- Exact producer: `ae8f75842224ddc5c64a85266d22ef2f8b772f23`.
- Codec blob: `59c8ba57b39a1ac4c89ac550f507f98d18ff763b`.
- Test blob: `7412780f0b46988a1fa3589534c87ef83c9c25cc`.
- Android baseline run `39395888593`, job `118538405704`: 999 JVM tests,
  two expected rich-text/projection assertion failures, zero errors/skips;
  independent capture 1/1 passed.
- Outer artifact `12252555074` SHA-256:
  `58e088d339cbb00230f512bf209d4630f8e1804948d0cb4d65d70fa439a338b5`.

Root verified both actual RED failures and the independent genuine capture.
`binding.json` and `capture-verification.json` bind that result to exact source
blobs and output hashes. `capture-source.kt`, inputs, provenance, log, original
global inventory and per-ZIP inventories are unchanged outputs. Original
inventories use literal `\t` separators; `SHA256SUMS.tsv` uses actual tabs and
hashes this curated resource set (excluding itself and local Git attributes).

`store-history.zip` and `store-final.zip` are complete authoritative stores,
including exact hidden `projects/.store.lock`, original CURRENT/UUID manifests,
immutable revisions, synthetic sources and authored-map sidecars. Temporary
reopened copies were outside the upload directory, so the outer inventory has
no missing raw hidden-file entries. Standalone project/history and revision
files are exact read-only extractions, never re-encoded or header relabeled.

P3 and P4 retain the same R0..R3 text/history/export/allocator shape as the
genuine v4 text capture. V6 additionally contains manual clip/effect anchors,
origin IDs, sample windows, local tracks, render overrides, exact held/nonlinear
dense timing, source backings, signed phases and saved first/end PTS metadata.
Nonempty MONO/SCALE text uses genuine legacy rich-field defaults. Reading 6
must preserve these values and immutable byte hashes when new 7 edits are saved.

The temporary capture producer remains in Git history and is removed from the
final test source. Final exact-head baseline/UI, independent review, joint owner
review and phone/STT/acoustic/render/human acceptance are separate gates.
