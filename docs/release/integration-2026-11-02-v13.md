# V13 combined release checkpoint — 2026-11-02

**Not release ready.** Source `cdd9ffe6a4fdec1f0a560b2ad1633500d5db9512`,
published in [draft PR #113](https://github.com/Veycad/Veycad/pull/113), based on
V12 `b4769596a55ebcc225a931e13d368afd6e872c70`. No merge or store submission.

V13 combines the two completed V12 CI failure follow-ups: serialized music
installation ([#112](https://github.com/Veycad/Veycad/pull/112)) and isolated
dirty-overlay frame counting ([#107](https://github.com/Veycad/Veycad/pull/107)).
It includes actionable error/owned validation/cancellation handling (#110),
13 unused prototype/resource deletions (#111), and native final-selection
progress assertions (#72). The sole fixture conflict retained V12's existing
`pickPendingStyle` title selector; no assertions or timeouts were weakened.
Read-only integration review found no Important/Critical delta issue.

## Verified on this combined source

- Fresh `clean baselineVerify :app:assembleRelease :app:assembleUiTest
  :app:assembleUiTestAndroidTest`: PASS, 9m50s; 1394 JVM and 338 Python,
  zero failures/errors/skips, lint/security. All 196 tasks executed.
- Release/UI flags match: manual editor enabled (`LIVE_PREVIEW_ENABLED=true`),
  experimental gallery recipe excluded (`GALLERY_MVP=false`). Normal imports
  for Heartbeat, FEAR and DUALITY remain available. Full source SHA verified in
  generated BuildConfig and all four release DEX payloads.
- Focused combined native/UI group: **19/19 PASS, 215.182s**. Includes all six
  manual-editor cases, concurrent music installation, the failed-capture route,
  pause/seek/play counting, four error-presentation cases, session cancellation
  and scratch/source retention,
  real custom-audio editor export/progress and four native GL lifecycle cases.
- Bundled speech recognition: **5/5 PASS, 186.072s**; English/silence, Russian
  automatic language, overlapping/partial windows, cancellation and invalid input.
- Import recovery: two real controlled process restarts, four exact source-file
  checks. Project recovery: fresh process, six exact project files, retired scratch
  removed and native editor ready. These force-stop probes do not establish OEM
  low-memory behavior or signed-update compatibility.

Local native environment: API36/default x86_64, no GMS image, Emulator37.2.13.0
NVIDIA host graphics, 320×480/density160/font1, 4096-byte pages. These focused
groups are not a completed full UI suite, a physical-device test or 16 KB runtime.
Capture/error/session-cancellation screen cases use the controlled UI render
engine. Manual preview/export, custom-audio export, GL and speech cases execute
their production native paths; the controlled cases do not prove codec cancellation.

The inspected synthetic export has 480 frames, H.264 160×240 and mono AAC16kHz,
16.064s container duration. Source/music/output hashes match native evidence;
full host decoding passed and the decoded source clock is complete. Its
`quality_gate=false` remains recorded (`audio-unclamped-headroom-unavailable`,
`colour-jump`); functional test success does not establish artistic acceptance.

## Unsigned release packages

Version 0.1.16/code17, min26/target36, `com.veycad.app`, not debuggable; no
INTERNET/ACCESS_NETWORK_STATE permission. All expected unsigned signature
rejections were verified. Runtime licence checksums and bundled speech model
hash passed; unused effects-lab/raw shader/drawable entries are absent.

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| Universal | 280869897 | `45739382b227208afaf94e5ec575868f6a4d7f57fcbe9eeb2ac109e7dfd94c30` |
| ARM64 | 220742528 | `f2c82274412a55e28b7adad3a6f76e47cc62f6983ef52c5e600eca1827efd3ea` |
| ARMv7 | 209872046 | `40ff59ce52308e957c9c8215e396280cc5b1b5dacd47d6a152a4fd0f19cda1bb` |
| x86_64 | 225949539 | `9704693f82b8603a0ad446966238fe332ffc458b0b13f5a34825559e6f23ac75` |

All four ZIP alignment checks and 14 unique 64-bit ELF checks passed statically.
Retained source assets, licences, models and authored music/video bytes match
V12; six unused prototype assets are intentionally removed. Generated DEX
profile bytes change with code. Rebuilt native binaries are not byte-identical
to V12, so full non-DEX payload equality is not claimed. The payload report
lists differences; fresh x86_64 speech/preview/GL checks above cover this build.

## Full CI and remaining gates

At the recorded snapshot, GitHub baseline and both legacy/legacy-save jobs
passed on this head. The [full UI job](https://github.com/Veycad/Veycad/actions/runs/41409406343/job/125158746689)
is still running; its final result is required. V12's completed GitHub result
remains 269/271, and its separate local full run ended in a system_server
watchdog abort. No local V13 full-suite or watchdog-resolution claim is made.

Still open: complete UI, actual 16 KB runtime (isolated image download in
progress), permanent key/signed install and same-key update, user-deferred
physical phone and spoken TalkBack, human output review, RuStore account,
third-party music distribution rights, final store artwork/public policy URL,
and owner merge/publication approval. The listing remains a draft.

## Retained evidence

Local aggregate: `C:/Users/rexar/Documents/Codex/veycad-v13-build-and-ui-receipt-20261102.json`.
Sibling files retain build/focused/speech/restart logs and XML, the JVM XML ZIP,
source-tagged original UI/release APKs, and `veycad-v13-native-media-20261102`.
Package, alignment and payload receipts are under `D:/CodexWork/veycad-v13-release-20261102`,
`veycad-v13-alignment-20261102` and `veycad-v13-payload-20261102.json` respectively.
Verified duplicate diagnostic folders remain on disk after automatic safety
review rejected their recursive deletion; their 2.19 GB is not claimed as freed.
