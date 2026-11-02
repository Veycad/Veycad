# V12: visible manual frame-rate selection

Status: build/package and manual-editor checks passed; a graphics check failed.
Not release-ready. No PR merge or store submission occurred.

Local source `b4769596a55ebcc225a931e13d368afd6e872c70`, branch
`codex/release-integration-v12-local`, combines [V11](integration-2026-11-02-v11.md)
with [PR #108](https://github.com/Veycad/Veycad/pull/108): selected 30/60 fps buttons
have a distinct background, contrasting checkmark and state description.
Only `ManualMvpActivity.kt` changed in production; the rest of the five-file delta
is its regression and owning-branch screenshot evidence.

The exact tested source is also published as the draft integration checkpoint
[PR #109](https://github.com/Veycad/Veycad/pull/109), branch
`codex/rustore-v12-candidate`. It lets GitHub CI check the combined changes in
another environment; no completed overall CI verdict is claimed here. Separate owning PRs
remain available for review. Neither the checkpoint nor these local checks
authorize merging or store submission.

GitHub checkpoint: the [legacy-save job](https://github.com/Veycad/Veycad/actions/runs/41390100270/job/125099378370)
and [legacy job](https://github.com/Veycad/Veycad/actions/runs/41390100133/job/125099376633)
passed on this exact head. The [baseline job](https://github.com/Veycad/Veycad/actions/runs/41390100143/job/125099376610)
also passed. UI was still running at 05:00 UTC; this is not a completed overall
CI verdict.

## Completed checks

| Check | Result and scope |
| --- | --- |
| Build and baseline | PASS, 6m44s; 1392 JVM and 338 Python cases, zero failures/errors/skips; lint/security, four unsigned release APKs and UI app/instrumentation built |
| Release package inspection | PASS: ID/version/min26/target36, non-debuggable, manual entry enabled, expected native/model/notices, no INTERNET/ACCESS_NETWORK_STATE; actual DEX contains this source stamp |
| V11 non-DEX payload comparison | PASS for all four APKs: manifest, resources, assets/models/notices and native libraries match preserved V11 bytes; DEX is outside this comparison |
| Static 16 KB compatibility | PASS: four ZIP alignment checks, fourteen 64-bit ELF LOAD/RELRO checks, split/native byte identity; not a 16 KB runtime test |
| Full `ManualMvpScreenTest` class | PASS, 6/6, 88.944s: empty/reopened prompts, FPS selection/restoration, import/formats/trim/undo/export/share, 60 fps preview lifecycle and recoverable missing-source UI |
| Large-text follow-up | PASS, 2/2, 7.008s at fontScale2.0: empty prompt and FPS selection with a genuinely new Activity instance after recreation |
| Import process restarts | PASS: two distinct-process restarts, four exact source-file byte checks and restored selection/ready UI |
| Project process recovery | PASS: a new process retained six exact project files, retired scratch was removed and native paused preview became ready |

UI checks used the dedicated API36/default x86_64 emulator, Emulator 37.1.11.0,
NVIDIA host graphics, 320x480/density160 and 4096-byte pages. Font scale was restored
to 1.0. Both release/uiTest have LIVE_PREVIEW_ENABLED=true and GALLERY_MVP=false.
Controlled force-stops do not establish OEM low-memory-kill or signed-update behavior.

## Graphics gate remains open

The original compositor/style selection completed 15 cases in 26.787s: 14 passed,
one failed. `GlesFrameCompositorDeviceTest.supplied_manual_window_has_display_encoder_parity_and_centered_legacy_parity`
failed at its centered-manual-versus-legacy display comparison, first byte 255
versus 0. Its earlier off-center display/encoder comparisons completed before that
assertion. No comparison tolerance or production graphics workaround was added.

All three style cases passed in this short selection. That does not clear the
earlier long-run freeze or V11 Android system crash. A complete V12 screen suite
has not run. The isolated Emulator 37.2.13 download remains a diagnostic lead;
it has not been accepted as a fix. Text/speech/AAC and other V11 results retain
their original binary scope.

### Matched graphics-environment comparison

The exact failing method passed separately in 74.484s on a fresh instance of
the same API36 AVD and Emulator 37.1.11.0 using `-gpu swiftshader`. Emulator logs
confirm Google SwiftShader. The installed app and instrumentation hashes were
verified against the original V12 APKs listed below; no probe, app or test changes
were used. Display, density, font scale and 4096-byte page size match the host run.

The unchanged fifteen-case compositor/style group then passed on software
graphics: 15/15, 562.605s. A fresh NVIDIA host instance was given the same sequence:
first the isolated method, then the same fifteen-case group. Both used the
verified original V12 APKs and the same AVD/display settings.

| Emulator 37.1.11.0 backend | Isolated method | Subsequent compositor/style group |
| --- | --- | --- |
| Google SwiftShader | PASS, 74.484s host elapsed | PASS, 15/15, 562.605s host elapsed |
| NVIDIA host | PASS, 6.344s host elapsed | FAIL, 14/15, 28.237s host elapsed |

The host group again failed the centered-manual-versus-legacy display comparison
at line202, this time first byte 0 versus 255. No manual-editor preload was needed.
Thus an isolated host pass does not establish stability. The comparison supports
investigating both backend and prior workload; it does not establish the driver
as the root cause, prove a production fix or clear full-suite/device acceptance.
No comparison tolerance or production workaround was introduced.

Receipts under `D:/CodexWork`: `veycad-v12-software-parity-20261102.json`,
`veycad-v12-software-graphics-style-20261102.json`,
`veycad-v12-host-parity-20261102.json` and
`veycad-v12-cold-host-graphics-style-20261102.json`, with matching `.log` files.
Renderer identities are retained in `veycad-v12-swiftshader-20261102.stdout.log`
and `veycad-v12-cold-host-20261102.stdout.log`.
The isolated newer emulator download is a separate experiment, not yet a fix.

## Unsigned APK identities

Version 0.1.16/code17. Expected signature rejection was verified; no permanent
certificate, signed install or same-key update acceptance is claimed.

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| Universal | 280876275 | `5ad8c1f31ce33a712d37e87bf7aa66ae9db54350cca4b1cca1bebcfe5785c1e0` |
| ARM64 | 220748906 | `773f6309a89c20dc4f76538f8c6db255b50291121cfb540f5aba7f4babc1e38c` |
| ARMv7 | 209878424 | `9519fe8cf9e1be69b814701eb3047d165ab7c1da839fd5aa34841849eaa25cd9` |
| x86_64 | 225955917 | `22879002e37d331f7d93211bc847a7d9583ee32ab60d470fcf877976114b2914` |

Original UI app SHA-256: `497b08fdbe33b49a288592ae459edc051e578d42c9106536faa6dad5c307b08b`.
Instrumentation SHA-256: `cee2eb3555f650df88d8ec183df122716a24b9bd6b2994c3bf0868c7e0243477`.
Both are retained in `D:/CodexWork/veycad-v12-original-ui-apks-20261102`.

## Retained evidence and remaining gates

Combined receipt: `D:/CodexWork/veycad-v12-build-and-ui-receipt-20261102.json`.
It records exact run logs, APK identities, flags and source-tagged hashes for
19 native manual-editor PNGs. Captures are in
`D:/CodexWork/veycad-v12-manual-preview-evidence-20261102`; they use synthetic
fixtures and do not establish final artistic/media or store-image acceptance.

Package, payload and alignment receipts are respectively
`D:/CodexWork/veycad-v12-release-20261102/receipt.json`,
`D:/CodexWork/veycad-v12-unchanged-payload-20261102.json` and
`D:/CodexWork/veycad-v12-alignment-20261102/receipt.json`.
Restart receipts: `D:/CodexWork/veycad-v12-atomic-import-restart-20261102.json`
and `D:/CodexWork/veycad-v12-project-recovery-20261102.json`.

Still open: graphics/full-suite resolution, signed and physical-device acceptance,
16 KB runtime, spoken TalkBack, human output review, account/key setup, third-party
music rights, final store images/public policy and owner approval for publication.
