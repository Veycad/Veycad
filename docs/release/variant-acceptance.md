# Release and UI-test feature visibility

## Current V11 configuration — 2026-11-02

Generated BuildConfig for release and uiTest at
`fca633adb0eb863562cfe8fe38b18a475fc95c41` has matching feature visibility:

| Flag | release | uiTest | User-visible effect |
| --- | --- | --- | --- |
| `LIVE_PREVIEW_ENABLED` | true | true | Manual editor entry enabled in MainActivity |
| `GALLERY_MVP` | false | false | Experimental GALLERY_MONTAGE recipe excluded |

Normal gallery import for Heartbeat, FEAR and DUALITY remains available.
The manual editor is required for the first release by the owner's decision;
its entry must be reachable through the normal interface of the final signed APK.
Matching flags and a successful build do not close that device check.

Evidence: `D:/CodexWork/veycad-v11-variant-privacy-delta-20261102.json` and
[V11 build/package report](integration-2026-11-02-v11.md).

## Historical pre-change audit — 2026-10-26

Checked 2026-10-26 against generated BuildConfig and owning source, not variant
names alone. Release build: `1f67643bfada2f6c14e9d5956f6da3f4c4889a0c` tree;
UI integration: `b9105cdc3bf5b96b8439a10bcc23730141179693`.

| Flag | release | uiTest | User-visible effect |
| --- | --- | --- | --- |
| `LIVE_PREVIEW_ENABLED` | false | true | MainActivity hides/shows the manual editor entry button |
| `GALLERY_MVP` | false | false | Experimental GALLERY_MONTAGE recipe excluded from the normal style catalog |

The second flag does **not** disable normal gallery import for Heartbeat, FEAR
or DUALITY. Do not describe it as absence of gallery montage in general.

Evidence: `app/build.gradle.kts` defaultConfig and uiTest configuration;
`MainActivity.bindActions()` controls `manualMvpButton` visibility;
`MontageStyleCatalog.all` and recipe acceptance use `GALLERY_MVP`.
Generated files under `app/build/generated/source/buildConfig/{release,uiTest}`
confirmed the values above in their respective checkouts.

Owner decision on 2026-10-26: expose the manual editor in the first RuStore
release and complete its acceptance before publication. The values above are
the audited pre-change state, not the desired release configuration.
The release flag implementation is in PR #92 below; verify the entry in
the actual signed release candidate. Do not defer the editor or hide its button as
a workaround for failing tests. The complete manual scenario including landscape
passed on V5 plus PR #100; see the [V5 receipt](integration-2026-10-26-v5.md).
This does not close signed-release entry or device acceptance.

Acceptance remains on the final release candidate: check available entry
points, exact feature flags, imports, preview, export, save/share, lifecycle and
update preservation. Recheck screenshots and listing against that candidate.
Launching an Activity directly from instrumentation does not prove a normal
user can reach it. UI-test success is supporting evidence, not the release gate.


Implementation: [PR #92](https://github.com/Veycad/Veycad/pull/92),
commit `96f00e499b8f669a2505fc641c51bcce37494ef7`, explicitly sets
`LIVE_PREVIEW_ENABLED=true` for release. Generated release BuildConfig was
checked; `assembleRelease` and `verifySecurityContract` passed (2m 09s).
The unsigned arm64 APK SHA-256 is
`4e26f747c05029aad8c8409f92f374997a1515592beef68f761e230dd0ec80ac`.
This is an isolated draft change, not yet merged or verified as a signed
release on a physical device. The acceptance requirements above remain open.
