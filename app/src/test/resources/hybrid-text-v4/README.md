# Genuine a531 physical-v4 text capture

These are unchanged outputs from the actual old production store/codec, generated
on 2026-10-20 with synthetic text-file sources. They are a new capture of the old
encoder, not previously archived project bytes and not header-swapped fixtures.

- Semantic anchor: `a53101496244fbb8476dfb314c701380a7eff80a`.
- Immutable execution base: `1d533e3554dae0d9f3d04286ad7dee0ad5027cc0`.
- Exact producer: `ad7fcb17d45d854cd38c01b1e4af4baa1cf5bb8c`.
- Codec blob: `2505cd323fc16d7eaffdbe393f3ab9760c7a94fd`.
- Test blob: `812a8e6387ea4eb61a733ae71186b40f1ae15150`.
- Android baseline run `39395192746`, job `118536083703`: 919 JVM tests,
  zero failures/errors/skips; capture 1/1 passed.
- Outer artifact `12251373559` SHA-256:
  `aef2f44558c2b362b87ff08244425c1cddcd48de57264808639f040040e38ce4`.

`binding.json` and `capture-verification.json` preserve root's run binding and
completed byte/ZIP checks. `capture-source.txt`, inputs, provenance, capture log
and original `inventory.tsv` are unchanged capture outputs. The inventory uses
literal `\t` separators. `SHA256SUMS.tsv` uses actual tabs and hashes this curated
resource set (excluding itself and the local Git attributes).

`capture-source.txt` contains the exact bytes originally captured as
`capture-source.kt`; only the resource filename changed because Android Gradle
Plugin 9.1.1 excludes `**/*.kt` from Java resources. Its original byte length,
SHA-256 and Git blob remain unchanged. Original capture inventory/provenance
retain the original `.kt` filename; the curated checksum list uses `.txt`.

`store-history.zip` and `store-final.zip` are authoritative complete stores;
both contain the exact `projects/.store.lock`, original UUID pointers/manifests,
immutable revisions and imported synthetic sources. Standalone project/history
and revision files are exact production payloads extracted read-only from those
stores. The original global inventory also describes raw convenience store
copies omitted here. GitHub omitted their two raw hidden lock paths during upload;
the exact locks survive inside the authoritative ZIPs. Nothing was regenerated,
zero-filled or inferred. ZIP entries can be checked against the corresponding
`store-history/` or `store-final/` prefix in the original global inventory.

P3 has original R0, current/export R1, undo R0, redo R2, allocator 3. P4 has
original/undo R0, current R3, export R1 and allocator 4, while the store retains
all R0..R3 blobs. All states contain nonempty MONO/SCALE layers and caption style,
BOLD/SLIDE controls and ru/en cues. Absent rich fields deliberately use legacy
defaults: plate null, kind LEGACY, confidence null and both flags false.

These fixtures prove old storage compatibility inputs. They do not prove
physical decoding, acoustic calibration, STT accuracy, rendering or human acceptance.
