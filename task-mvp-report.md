# Gallery/text MVP source handoff

Worktree: `C:/Users/rexar/.codex/worktrees/gallery-batch/AutoEdit`.
Private branch: `codex/gallery-text-mvp`.
Exact starting HEAD: `32e4242943ce3d744393d21dd6da66c940699beb`.
Preserved old private ref: `codex/gallery-text-integration` → `32e4242943ce3d744393d21dd6da66c940699beb`.
No other WIP/audio heads merged. The committed delivery HEAD is supplied in the coordinator handoff; a commit cannot contain its own SHA.

The isolated `com.veycad.app.mvp` / Veycad MVP build exposes system document multiselect for 1–20 videos, an ordered process-owned draft, neutral gallery analysis and the existing gallery director. The new thin adapter supplies every source to the existing two-decoder native renderer and existing built-in MUSIC mux. It checks actual MP4 tracks, clocks/dimensions/duration and decodes a sampled frame from every authored clip before publication. Its summary states basic verification and leaves artistic acceptance open; it never fabricates face QA or a `VeycadAutomaticEditor.Result`.

Default duration is up to 30 seconds and shorter when usable footage runs out. MVP inputs remain inspected H.264/SDR MP4, minimum 0.5 seconds each, maximum 30 minutes total. The picker preserves order and count through Activity recreation; render, save and failed/cancelled imports retain the full draft. DUALITY still requires exactly two unique selections. Debug/release/uiTest keep gallery gated.

Imports copy to owned partial files with chunk cancellation checks and atomically finish each new copy before replacing the in-memory selection. Failed/cancelled new copies are removed; MVP never broadly deletes old source copies. Cancellation also reaches real render/analysis checks and the existing job lease. Previous published MP4s and originals are preserved. MVP selection must be repeated after process death; the existing interrupted-job message and durable completed results remain. Resetting a selection preserves copied source files, so storage can grow until app data is explicitly cleared.

Result preview, save/share and finished-MP4 text entry reuse the existing screens. Titles, plates, formatting and manual captions remain in `TextEditActivity`. Batch audio is MUSIC only. Autocaptions require a separately chosen original speech MP4 in the same existing text editor; product copy explicitly distinguishes these flows. No shared Hybrid revision/serialization, SOURCE/SPEECH_AND_MUSIC export acceptance, source AAC guard changes, calibrated confidence or acoustic word alignment are claimed.

The debuggable debug-signed `mvp` variant keeps namespace and native ABIs unchanged. Existing build-time speech model preparation and main assets package the real offline Whisper model and licenses. The PR-only `Android MVP smoke and APK` job checks the exact PR head of `codex/gallery-text-mvp`, builds both isolated APKs and verifies package/target, debug label, absent network permissions, model SHA, native Whisper library and licenses. It then runs API 36 x86_64 with network disabled, using direct AndroidJUnitRunner and the existing XML listener. Default uiTest instrumentation remains unchanged; MVP compiles only real shared helpers/editor tests plus its three new cases, excluding the fake uiTest application.

Selected remote smoke is exactly four cases: real three-source picker/order/recreation/native render/preview/save/share; real-session import/render cancellation and invalid/over-limit preservation; separately selected raw Russian speech recognized in existing TextEditActivity; existing manual text/cue export with Cyrillic/plate and original preservation. No fake-render PASS. Artifacts include `app-mvp.apk`, actual APK/model SHA verification, XML/instrumentation/logcat, three small synthetic input MP4s and device media/render evidence. The original full workflows are untouched; their known unexposed SOURCE/AAC failures are separate from this MUSIC-only MVP smoke.

Checks: all new local tests, compilation, Gradle, lint, syntax, native, emulator, rendering and APK/model execution are **NOTRUN**, forbidden by the resource queue. Only source/API reads, Git diff review and required cached diff whitespace check are performed. Remote build/smoke/API compilation, APK evidence and independent review remain pending with the root coordinator. This is a source checkpoint, not a ready/accepted APK. Existing broad feature gates and human artistic/device acceptance remain open.

Human testing after a successful remote build/smoke:

1. Install `app-mvp.apk` on the emulator and open Veycad MVP. Choose «Ролик из галереи», select 1–20 H.264/SDR MP4s and confirm their displayed order/count.
2. Create the montage, watch it at normal speed, save it and open Share. Check scene choice, framing, rotation, cut rhythm and music by eye/ear.
3. Repeat import/render and cancel it; try an invalid file. Confirm the previous video still plays and original files remain available.
4. Open «Текст» on the finished montage, add a title/plate/manual caption and export a new MP4; confirm the earlier MP4 still exists.
5. For automatic subtitles, separately choose a raw Russian speech MP4 in «Текст», recognize its speech, correct/review the captions and export. Do not use the gallery montage's music as source speech.

Root coordinator owns the independent review, publication, draft PR, real remote CI and APK delivery. No publication, external messaging, local process cleanup or local media execution was performed by this implementer.

2026-10-12 direct human override: «скипни проверку». Independent review and test runs are explicitly skipped. The MVP workflow now only assembles the isolated APK with the existing real-model packaging and uploads `app-mvp.apk`; AndroidTest build, emulator/smoke and APK verification execution are removed. Source tests/helpers remain for future acceptance. Build-only remote execution is pending; no PASS or runtime acceptance is claimed.

Remote APK build `38236413068` at `0a90ed0d47e12de47c86bf42532c97f5bcb81b62` failed in `compileMvpKotlin`: `RenderedVisualSampler.kt` used nullable `scheduledFrame.layer` and passed nullable `scheduledFrame` to `sourceLumaAtCamera`. The Sigma background-loss branch now explicitly requires a scheduled frame and otherwise retains absent (`null`) evidence. Replacement build is pending. Review/tests remain skipped by the human override; no local compilation or test execution, no successful APK or runtime claim.
