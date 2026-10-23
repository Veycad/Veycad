# Project copies, version changes and controlled recovery

The manual editor offers **Резервная копия** for the selected project. The
library offers **Восстановить копию**. The Android document chooser writes/reads
a ZIP at a user-selected location; **Поделиться копией** shares that same ZIP
with a temporary read grant. Keep a copy outside the application's private
storage before uninstalling, clearing application data or changing APK versions.

The archive contains the committed project pointer, manifests, immutable
revisions (including revisions referenced only by exports), owned original
sources/music, analysis/authored sidecars, and available referenced completed
videos and metadata. `manifest.json` inventories every archived file's relative
path, byte length and SHA-256. Derived SMART caches can be rebuilt and are not
required for recovery. Already deleted completed videos are reported as missing;
the project and available inputs are still copied.

Restoration validates the complete archive before publishing a new project. It
always allocates a fresh project ID and fresh completed-video names. Existing
projects and outputs are preserved. Raw source, sidecar and historical revision
bytes remain in the restored project; the new current manifest is written by the
current canonical codec. Failed validation does not replace the user's project.
Temporary restore staging and any newly published outputs are rolled back on
failure. Storage cleanup failures are reported rather than silently presented as
complete cleanup.

The portable transfer container is version 1. Limits are 10,000 files, 64 GiB of
uncompressed file data and 4 MiB of JSON inventory, with additional local free
space required while staging. ZIP traversal, duplicate paths, symbolic links,
encrypted/split archives, inconsistent byte lengths/hashes, corrupt project
manifests and unsupported project schemas are rejected. Checksums detect
corruption; they do not authenticate the archive's author.

Old projects without an inspected selection table or video metadata may contain
placeholder source identities instead of saved SHA-256 values. These are preserved
verbatim. The archive still inventories and verifies the actual copied bytes, and
the result/UI explicitly reports `legacyUnverifiedSources`: transfer integrity does
not prove those bytes match the original historical import. A real saved 64-digit
SHA-256 is always verified, including in an old project. Recovery does not invent
inspected metadata or replace historical identities with new hashes.

## Compatibility and rollback policy

The current writer uses project format **9**, including persisted audio mix.
Existing supported older projects are read without rewriting their original
store; restoration publishes a separate project through the current writer.
Both historical format-7 layouts are handled by their compatibility readers.
Format-8 compatibility also preserves rich text and music offsets.

There is no format-9-to-8/7 export. Changing a version integer would lose data or
misrepresent its structure. To return to an older APK, retain that compatible APK
and an independent backup created with that older version **before upgrading**.
Restore it only in a version that supports the backup's project format. Keep the
newer project backup separately if newer edits must be retained. Do not clear or
uninstall the application as a substitute for a backup, and do not delete the
newer project because an older build cannot open it.

## Evidence scope

For issue #66 the owner explicitly excluded physical-device and emulator runs.
Only host tests, static checks and build results may be reported as executed.
The device and force-stop journeys below remain unexecuted acceptance coverage.

`HybridProjectBackupTest` verifies byte inventories, both genuine historical-v7
stores, export-only revisions, rich text, music offset, audio mix, malicious or
corrupt archives and rollback after newly published output files. Existing
format-8 codec compatibility tests remain required.

`ProjectRecoveryDeviceTest` exercises real native preview/export, portable
backup/recovery through actual content-provider streams, share grants and
restored MP4 metadata. The picker response is controlled; it is not a visual
acceptance of DocumentsUI. It also explicitly injects preview memory pressure,
waits for the actual GL/audio release acknowledgement, retries from the saved
clock and exports again. Pressure during an export is a protected-job NOOP;
admission tests verify that cleanup is excluded until actual retirement.

Legacy memory callbacks are no longer delivered on Android 14+; explicit test
injection on API 36 is a controlled simulation. Software budget admission,
acknowledged release, real host force-stop/fresh-PID recovery and source hashes
can be verified on an emulator. They do not prove every OEM's low-memory killer,
physical-device memory survival or a user's visual/listening acceptance.

The ordinary UI runner retains preview PNG/text/movie evidence and its SHA-256
inventory **before** process-restart tests can clear the isolated `.uitest`
package. API-28 `LegacySaveOnly` still runs only its original save-destination
checks. Required CI identity/count verification must include newly added ordinary
cases; partial or skipped runs do not establish completion.
