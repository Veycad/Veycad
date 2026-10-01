# VME-Heartbeat style/music route isolation (2026-09-08)

## Symptom

Selecting `Heartbeat` in the product UI produced Heartbeat layers over the Sigma author score and
the result screen still called the winner `Dynamic`. This was a real mixed-style render, not only a
label defect.

## Root cause

- `BuiltInMusicCatalog.select()` always returned `leonid_reentry`, regardless of selected style.
- That file matches `ReferenceMontageProfile`, so Sigma's fixed beat map and source-selection
  profile ran before `HeartbeatDirector` replaced the visible graph.
- Changing a style did not refresh the already selected music.
- The result label and diagnostic event used the source-pool alternative (`DYNAMIC`) rather than
  the final graph profile.

## Fix

- Heartbeat now owns the author-approved `heartbeat_author.m4a` production resource.
- Music selection is keyed by style on import, draft restore and style change.
- A selection carries its style id; render refuses stale cross-style state.
- `VeycadAutomaticEditor.Request` rejects Heartbeat unless the audio SHA-256 matches the authored
  Heartbeat score.
- Product result copy derives the name from the final graph, and diagnostics record style,
  source-pool alternative, graph profile and music separately.

## Verification

- Full `testDebugUnitTest lintDebug assembleDebug`: passed.
- APK archive contains both production M4A resources and no MP4 fixture.
- API 36 emulator, production UI path with persisted source draft:
  - UI: `Выбрать стиль · Heartbeat` and `♫ Heartbeat · 120 BPM`;
  - one render candidate, 1271 frames;
  - log: `style=heartbeat`, `director_alternative=DYNAMIC`,
    `graph_profile=HEARTBEAT_V1:production`, `music=heartbeat_author`;
  - GLES ran on 1271/1271 frames, 508 dual-decoder frames, 115 blackout frames;
  - AAC sample count 912, A/V last-PTS delta 13,288 us, rotation 0.

The rendered QA gate is still false (`beat-hit-rate`, repeated moments, transition/colour and matte
issues). This issue resolves style/music contamination only; it does not claim Heartbeat visual
parity. Samsung was not attached during this verification; only `emulator-5554` was available.

Local evidence: `artifacts/device-regression/emulator/heartbeat-style-routing-0908/`.
