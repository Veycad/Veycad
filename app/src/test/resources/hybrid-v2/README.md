# Historical v2 fixtures

These files were captured on 2026-10-10 from the unmodified physical-v2 encoder
compiled at `ed167b7762716df6f407a4c24a90629698462ca8`, before adding revision
reset provenance or changing the encoder to v3. Do not regenerate with the current codec.

The source-mode Java capture used `editProject(30, 30)` and
`HybridEditCommands.moveCut(base, "right", 28)`, then wrote `codec.encode(project)`
and both `codec.encodeRevisions` blobs. Original revision is 0, current revision
is 1 with parent 0, undo contains 0, next ID is 2, and the right clip has phase -2.
There are three video clips, one audio asset, no analysis sidecars or exports.

The capture used Android Studio JBR `java.exe --class-path` with the already
compiled debug/debugUnitTest Kotlin classes and Kotlin stdlib 2.2.21. It verified
the actual header was v2 before writing. The ignored capture source/output are
`.superpowers/sdd/2026-10-10-hybrid-mode/Task3V2FixtureCapture.java` and
`task-3-v2-capture.log`. Exit 0: project 3660 bytes, two revision blobs.

SHA-256:

- `project.bin`: `8694cb2b6cbd1080a03bad893fa3b4eaa1aa866cd604ce53214baa5c5fea40f7`
- `revision-0.bin`: `3bf0922966025313031b7bd6f3abba7eacffe97f898f5d33641c86dc119fcfaa`
- `revision-1.bin`: `86ce7324cdcd8b8df64abc3e925e406667237288059a451a4c8c1ee1465cc3af`
