# Published format-8 writer capture

These bytes were produced by the actual compiled format-8 writer from commit
`63a2b03fa9f1610a4877c8575e161a052ef8b1e9` (the same source tree as main
`2ca8eeb7fc276656ac5606dd28b21ce1e550ab17`). The input was the retained genuine
`hybrid-v7-rich-text/store.zip`, loaded by that writer's HybridProjectStore.
Both the complete project and standalone immutable revision payloads were encoded.
No header or field bytes were patched to manufacture an older format.

The capture covers rich text, caption review/confidence, authored source maps,
analysis sidecars and source backings. Format 8 has no audio-mix field: the
format-9 reader must supply MUSIC and preserve the saved music offset/settings.
SHA256SUMS.tsv authenticates the captured payloads and their sidecars.
