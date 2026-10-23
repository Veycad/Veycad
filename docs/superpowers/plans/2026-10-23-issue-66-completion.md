# Issue 66: independently verifiable completion

Owner instruction: implement, check, push and merge the work that can be accepted without a physical phone or the owner's visual/listening judgement. Baseline: main `2ca8eeb7fc276656ac5606dd28b21ce1e550ab17`.

Latest owner instruction: do not execute checks on physical devices or emulators, locally or in CI. Final verification is limited to host tests, static checks and compilation. Native test sources may be compiled; their execution and acceptance remain OPEN.

## Shared design

Keep HybridProject/HybridProjectStore, the canonical ProjectClock/SourceTimeMap, the existing GLES renderer, decoders and streaming audio composer. A saved revision is the edit authority. Preview and export borrow one detached prepared snapshot; no alternate graph, store or preview MP4. Retain bounded ownership, cancellation, generation and stop acknowledgement checks.

SMART retains the existing source/aspect mode settings. Deterministic derived compact source-PTS tracks are regenerated before READY on a cache miss and keyed by source hash, analysis/generator versions and source windows. Full analysis planes are released before playback. All decoder roles use the same sampler, including overlap/temporal roles. Dynamic framing must not grow per-frame caches.

Prepared text preserves rich layers, captions and legacy free-position geometry, and honours authored-title visibility. The preview uses the renderer's existing two-source transition/effect operations and text layout. Reuse identity includes the entire prepared visual/text snapshot.

AudioMixSettings is revision-owned in format 9. Readers retain formats 1-8 (including both published format-7 layouts). Current clip source maps produce the primary speech bus, with the existing HOLD/gap/fade rules. ProjectMusic offset, gain, fades and repeat affect one bounded PCM composition. Preview plays a transient WAV of that composition; export encodes the same composition and STT borrows the pre-music 16-kHz mono bus. No whole-timeline PCM arrays. Existing unprepared legacy exports retain their audio path. AAC container support is established with the actual encoder/extractor/decoder probe; do not remove an API guard without native evidence.

Backup/recovery exports exact owned files and a size/hash inventory. Restore validates before publication and creates a fresh project; it never overwrites an existing draft. Current-format saves do not make an older APK understand newer data. Preserve an exact pre-upgrade backup and a compatible APK for rollback.

Low-memory work covers bounded refusal, explicit trim with owner acknowledgement, recreation and process-death recovery. Injected memory callbacks do not prove OEM LMK behaviour. Physical ARM/OEM, human visual/listening/STT judgement stay open.

## Implementation and ownership

1. Reliability: collect preview evidence before restart/pm-clear, verify its inventory and add host routing regression coverage. Implement portable verified backup/restore and recovery policy/UI. Implement controlled pressure policy/probes with the preview/UI owners.
2. SMART: connect analysis/cache preparation to saved modes, source-PTS framing in all render roles, source/capture winner handoff and exact project intent. Extend the existing editor UI.
3. Preview: expand the existing prepared handoff, text and audio snapshot binding, GL-owner preparation/lifetime, two-decoder playback, text/authored visibility, resource recovery and parity tests.
4. Audio/common editor: persist mode/gains; compose from saved clip maps and verified music; share the PCM result with preview/export/STT; expose audio modes and canonical subtitle editing in the current editor. Add old-format fixtures and native timing/byte checks.
5. Integrate and review each cohesive diff, fix connected regressions, commit named paths and run baselineVerify plus host runner checks. Do not execute emulator/device suites or API compatibility probes under the latest owner instruction.
6. Create PRs with exact-head checks and explicit acceptance limits, attach them, merge only verified current heads, and update issue 66 with evidence. Do not close its physical/human acceptance items.

## Acceptance evidence

- Pure tests: codec/history/compatibility, source-map/audio selection, fades/offset/repeat/headroom, snapshot detachment, framing bounds/identity, backup traversal/corruption/atomic failure and runner order.
- Native tests: same-PTS preview/export pixels for text/framing/transitions; real decoded PCM/AAC markers/count/PTS; STT pre-music bus; cancellation/cleanup/budget failure; no preview MP4.
- Editor journeys: source import/capture handoff, SMART/aspect persistence, audio mode/text edits, undo/redo/save/reopen, export/share, trim/reorder, missing input/retry, lifecycle/controlled pressure/process death, verified backup restore.
- CI: applicable checks on the final pushed SHA, without substituting prior main or earlier local results.

Source-AAC export retains the API >=35 guard. API26–34 MUSIC keeps the existing legacy muxer, fed the composed music WAV so saved gain, offset, fade and repeat edits apply. This legacy path does not claim sample-exact gapless AAC. Source/MIX preview and STT use PCM; API26–34 source/MIX export remains deferred pending native acceptance.

## Progress

- [x] Verify clean baseline and isolate from the original dirty repository/broken refs.
- [x] Reliability, backup and controlled pressure implementation.
- [x] SMART implementation.
- [x] Expanded direct preview implementation.
- [x] Audio, speech and common-editor implementation (source-AAC export keeps API >=35).
- [x] Integrated review and host baseline: 1392 JVM tests, 338 Python tests, zero errors/skips; lint zero errors, release security contract and debug APK passed.
- Native execution is excluded. Runtime rendering, codecs, recognition, device pressure and human acceptance remain OPEN.
- Remote delivery and final-head CI results are recorded in issue #66 and its linked PRs.
