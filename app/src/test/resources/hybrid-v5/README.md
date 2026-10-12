# Genuine preceding physical-v5 store

`store.zip` is the unchanged 19,809-byte store archive emitted by the actual
v5 writer during Android baseline run 38229087150, artifact 11737649274.
Its SHA-256 is `65a2afb2520efd865fefee737e70d6d21f343eba6ef6f16fa0ab62960060a095`.
The outer downloaded artifact SHA-256 is
`8c55ec0033ba110a0a014c55b122a0efd92de8e90fb65fb24e23b3274e4c20c4`.

The controller verified all outer/inner lengths and hashes, CURRENT, seven
manifest envelopes and seven revision identities. The actual checkout was
`e03c81735f468c458c1e18eb2c240d90ff97eea1`; the utility author/tested PR head
was `4124d0c2a582c0c54017b9789256c6167c17460b`, bound separately using provider
and complete Git metadata. The unchanged production tree was
`9dbc3b957eec3d99c73a2b0c7862f7c595764270` and codec blob
`81dd37d2321e1f5d2b7baa67eb9dce558cb99906`.

Captured `provenance.txt` retains its honest shallow-checkout UNKNOWN fields.
`store-SHA256SUMS.tsv` and `literal-expectations.md` are unchanged capture files.
The ZIP retains the actual hidden empty lock, original manifests, authored maps,
revisions and synthetic sources. No header relabeling or re-encoding occurred.
Convenience project/revision copies are omitted to avoid duplicating the archive.
Synthetic bytes and unversioned endpoints prove storage compatibility only,
not physical media inspection, measured timing, rendering or human acceptance.
