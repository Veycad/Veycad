# Native final-selection progress regression

[PR #72](https://github.com/Veycad/Veycad/pull/72), test commit
`604e18fb1816dda3ab12b407cea556ef88d15d40`, production fix
`b9f28e8ac7709708f2806cd36812659fccb6871f`.
This is a main-based owning-PR regression, not an integrated V12 test run.
No production code changed in the test commit.

The existing real custom-music render test now records the actual callback events.
It checks one VERIFYING event per candidate plus final selection, final VERIFYING
N/N, bounds of every count and COMPLETE N/N. The exercised fixture selects one
candidate; this is not native coverage of every multi-candidate/refinement route.

| Check | Result |
| --- | --- |
| Original arithmetic negative control | Expected FAIL in 53.687s: final VERIFYING reports 2/1, assertion expected1/actual2 |
| Fixed production arithmetic | PASS in 34.844s with identical installed instrumentation |
| Clean `baselineVerify` | PASS, 9m23s; 1392 JVM + 338 Python, zero JVM failures/errors/skips; lint/security |
| Independent read-only review | No critical/important/minor findings; reviewer did not run builds |
| Retained synthetic MP4 | H264 160x240, 480 frames/14 clips; AAC16k mono; full host decode passed |

APK SHA-256: negative control `9710df6f66049b29618e43c8b519d516fd291a1e8fc0622647ad7a3641735197`,
fixed `5e2b4a9423cef1f4254b1b6ceb7484bc1ed2bb418dd681efd6f7f2134c65f2d4`,
common instrumentation `8cc1c222169b018faa594793ceebad0fd101fe449065e38bb89888ddde0bb3fa`.
Builds preceded the test commit; normalized source hashes were then checked
against its exact Git blobs. Binary hashes are retained; no embedded exact-SHA
provenance or final signed/release identity is claimed for these test APKs.

Evidence: `C:/Users/rexar/Documents/Codex/veycad-progress-native-receipt-20261102.json`,
three retained APKs in `veycad-progress-native-apks-20261102` and synthetic
input/output/decoder reports in `veycad-progress-native-media-20261102` under
the same parent directory. Device: API36 x86_64, Emulator37.1.11.0, NVIDIA host.

The fixture's quality gate is **false**: `audio-unclamped-headroom-unavailable`
and `colour-jump`. It establishes progress-event regression and decodability,
not accepted style/audio quality. No human listening/visual or physical-device
acceptance occurred. Latest-head GitHub CI and merge approval remain separate.
