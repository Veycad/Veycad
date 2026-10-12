# Genuine preceding physical-v4 fixtures

Captured on 2026-10-10 with the unchanged production encoder/commands at
`f51a595b909cefab46e2afe9843739f0a7a112cc`, before any v5 encoder edits.
These are not v5 files with edited version headers.

The capture used `editProject()`, with video metadata
`SourceGeometry(1920,1080,90,1.2f)`, size 4321, `video/avc`, transfer 3, audio true.
It then ran `moveCut(right,28)` and `restoreMontage`, and attached export revision 1
as `saved.mp4` (1080x1920, 30 fps, video bitrate 8000000, audio bitrate 192000).
The project contains the original, moved and reset revisions (0,1,2), signed phase
-2 in revision 1, and the scoped reset event in revision 2. No analysis sidecars.

SHA-256:

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| project.bin | 5541 | 153fb4621e2794b4d6db9954a84b815b73dd549f5c950744f61f195341675e44 |
| revision-0.bin | 1627 | 33e620bfb0e4a51f2fa2f66d1124f686f5e7611658f9710a79a1686ce4267a26 |
| revision-1.bin | 1992 | fba04c883eab82094ce6b3d56c78b3d19278aeb1afb9353c92d14ec30655ef88 |
| revision-2.bin | 1644 | f96fb556070eec6b907cdc0f527096b73e83677ac0d4751e23a3e27e634fdf30 |
