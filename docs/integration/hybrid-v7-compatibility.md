# Both published format-7 layouts

Two feature branches published different physical format-7 writers with the same
magic/header. The music writer adds `AudioTrack.sourceStartUs` but retains the old
text layout. The text writer retains the old audio layout and adds nullable
`TextStyle.plate`, `TextLayer.kind`, caption confidence and review/calibration flags.

The canonical writer remains format 8 and writes both sets of fields. Formats 1–6
keep their existing readers. For format 7, the codec reads each entire payload with
a fresh stream, authored-map reader, item budget and missing-analysis set. Only one
complete, validated interpretation may succeed. Invalid and ambiguous payloads
are rejected. This applies to the project manifest and standalone immutable
revision blobs, including revisions retained only for old exports.

## Genuine writer captures

`app/src/test/resources/hybrid-v7-music` and `hybrid-v7-rich-text` contain fresh
synthetic captures, not archives of user projects and not patched format headers.
Each directory contains project/revision bytes, a complete store ZIP, the exact
production codec source as `capture-source.txt`, the producer test as
`capture-test.txt`, and SHA-256/size records checked by the regression test.

| Layout | Published head | Unchanged production codec Git blob |
| --- | --- | --- |
| MUSIC | `e6130b042882a81d4641849030cf5e37f1598e35` | `a6958622760480409bbe99b1b29a5338e2d866d4` |
| RICH_TEXT | `0aebc8b29981198b43c2343375995b003740b767` | `90116cacf2ecb146b87956496c9fc140d85e73a3` |

The capture harness used the common model/store dependencies from
`ad8f578ce51f0b76d8f21873a0ae87fc5b4dbfb3`, replacing only the codec with the exact
published source above. The source blobs were checked with `git hash-object`.
The synthetic project uses the existing `storageProject()` fixture. No binary
writer was implemented by the harness. Each old production codec itself called
`encode`, `encodeRevisions` and `HybridProjectStore.create`.

To reproduce in an isolated checkout of that base, restore the selected
`capture-source.txt` to the production codec path and `capture-test.txt` to
`app/src/test/java/com/example/autoedit/HybridV7CaptureTest.kt`. Set
`CAPTURE_LAYOUT` to `music` or `rich-text` and `CAPTURE_OUTPUT` to an empty output
directory. Run `:app:testDebugUnitTest --tests com.veycad.app.HybridV7CaptureTest
--max-workers=2`. Use the JBR bundled with Android Studio. Restore production
sources after capture; the capture harness is not part of the shipping tests.

The regression checks project/history/export references, signed clip phase,
nonzero music offset, MONO/SCALE and rich text, retained immutable blobs, v8
save/reopen, truncated/trailing input rejection and read-only loading. It verifies
old bytes remain intact after a new revision is saved. Android rendering,
physical-device and artistic acceptance are separate checks.
