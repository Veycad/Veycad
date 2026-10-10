# Genuine physical-v3 fixtures

Captured on 2026-10-10 before changing the encoder in A1, with the production
codec at base `01276586783c5de83cdf678204a11a8505aec65a` (Task 3 physical-v3
implementation from `06811435`). No header patching or hand-written revision
payloads were used.

Generation was a temporary JVM test, run with the unchanged production sources:

```kotlin
val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
val moved = HybridEditCommands.moveCut(editProject(), "right", 28)
val project = HybridEditCommands.restoreAutomatic(moved)
File(path, "project.bin").writeBytes(codec.encode(project))
codec.encodeRevisions(listOf(project.original) + project.undo + project.current)
    .forEach { (id, bytes) -> File(path, "revision-$id.bin").writeBytes(bytes) }
```

The temporary writer was replaced by a permanent reader test. Original revision
0 has phase zero; revision 1 moves the right cut by -2 frames; revision 2 is the
real automatic-source reset event. Graphs contain no analysis sidecars or real
user media. All binaries have physical version 3.

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| project.bin | 5223 | `2712f90f465769af3f0cbf1ff3d9b70cebf82d7d1b4c529e683c05a2e3826a3b` |
| revision-0.bin | 1553 | `66d681c9acb9ca86036727b88939d95a0dfb3a2dc89d527311dae5e274785b1e` |
| revision-1.bin | 1918 | `e8906392c427ec595765e9550b6d5f4975df92c7675d59f9ca09c268b05ebf2a` |
| revision-2.bin | 1561 | `c65942f3b69b43a5aa92d1700a776bb2fd40c7956007b8d42a4e024969bb7be4` |

`HybridIntegrationA1Test` loads these fixtures, checks original/undo canonical
sharing, phase/reset semantics, explicit unknown metadata, and immutable old
store manifests/blobs across a new v4 edit.
