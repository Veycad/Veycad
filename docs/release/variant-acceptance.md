# Release and UI-test feature visibility

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
Implement the release flag change in a separate PR and verify the entry in
the actual release candidate. Do not defer the editor or hide its button as
a workaround for failing tests. The V3 landscape scenario failure remains open.

Acceptance remains on the final release candidate: check available entry
points, exact feature flags, imports, preview, export, save/share, lifecycle and
update preservation. Recheck screenshots and listing against that candidate.
Launching an Activity directly from instrumentation does not prove a normal
user can reach it. UI-test success is supporting evidence, not the release gate.
