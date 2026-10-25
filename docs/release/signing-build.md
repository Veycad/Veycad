# Signing a release APK

The default release build remains **unsigned** for packaging/CI checks. To request signing, explicitly pass `-PsignRelease=true`. There is no automatic debug-key fallback. The app ID remains `com.veycad.app`.

## Inputs

Provide these environment variables through a local secret manager or protected build environment, not shell-history literals or committed files:

| Variable | Value |
| --- | --- |
| `VEYCAD_RELEASE_KEYSTORE` | Absolute path to the owner's keystore outside the checkout |
| `VEYCAD_RELEASE_STORE_PASSWORD` | Keystore password |
| `VEYCAD_RELEASE_KEY_ALIAS` | Alias of the permanent app key |
| `VEYCAD_RELEASE_KEY_PASSWORD` | Private-key password |

Signing also requires `GIT_SHA` or `-PsourceSha` containing a complete lowercase 40-character source commit. Use the actual reviewed checkout, verify it is clean and record the APK hash; a syntactically valid SHA alone does not prove source provenance. `GIT_SHA` takes precedence over `sourceSha`.

In PowerShell, after configuring the external inputs and the normal JDK/SDK environment:

```powershell
$releaseSourceSha = git rev-parse HEAD
if (git status --porcelain) { throw 'Commit or isolate changes before building the release candidate' }
if ($env:GIT_SHA -and $env:GIT_SHA -cne $releaseSourceSha) { throw 'GIT_SHA must match the reviewed checkout' }
.\gradlew.bat :app:assembleRelease -PsignRelease=true "-PsourceSha=$releaseSourceSha"
```

Outputs are in `app/build/outputs/apk/release/`. Verify the intended APK using Android Build Tools `apksigner verify --verbose --print-certs`, compare its certificate SHA-256 against the owner's expected certificate, then record APK SHA-256, source SHA, package/version and acceptance results. A successful build is not an approval to publish.

Without `-PsignRelease=true`, keystore variables are not used. Malformed boolean opt-in, missing source SHA, missing signing inputs and a missing/unreadable keystore fail configuration when signing is requested. Wrong alias/password/certificate contents are delegated to the Android signing task and must fail the actual signing check.

Do not use `--debug`, build scans or shared configuration-cache archives with production signing secrets. Keep keystore backups under owner control. Do not assume a debug-signed installation can be upgraded to the new permanent certificate without a separate data migration plan.

## Verification checkpoint — 2026-10-25

- Signed opt-in with a source SHA but no keystore: expected failure naming `VEYCAD_RELEASE_KEYSTORE`.
- Signed opt-in without source SHA: expected source-identity failure.
- `-PsignRelease=maybe`: rejected rather than silently interpreted as unsigned.
- A nonexistent keystore path: rejected with a generic error without printing credentials.
- Default `:app:verifySecurityContract`: passed without credentials.
- Positive temporary-certificate signing probe: **not run**. Automatic tool approval rejected the command to create a test certificate and sign an APK; no detailed reason was supplied. No key or signed test APK was created by that rejected call.

The owner has not yet created the production key. Actual signed-APK verification, wrong-password/alias probes and same-key install/update checks remain open. This configuration must not be called production-validated until those pass.
