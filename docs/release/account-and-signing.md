# RuStore account and permanent signing identity

Owner confirmed on 2026-10-25: no RuStore developer account and no permanent signing key exist yet. These are publication dependencies, not reasons to stop code/testing work. No account, certificate or private key has been created by this preparation.

## Account

Create the account in [RuStore Console](https://console.rustore.ru/) using the owner's identity. Choose the actual legal status and review the terms personally; do not invent company details. [Official registration instructions](https://www.rustore.ru/help/developers/developer-account/registration-developer) distinguish individuals, individual entrepreneurs and legal entities. A paid product or in-store monetization may require a different account choice; no monetization integration is added by this release plan.

The owner supplies the public developer/support details and completes login/identity checks. Passwords, OTPs and identity documents do not belong in repository issues, PRs or chat. Account creation and acceptance of terms remain with the owner.

## Signing preparation

- Keep application ID `com.veycad.app` and use one permanent certificate for RuStore and subsequent stores where supported.
- Create an owner-controlled release keystore outside all checkouts, with a strong password held in a password manager. Do not reuse Android's debug key.
- Android Studio's Generate Signed Bundle / APK flow can create the keystore. Use a validity of at least 25 years, as recommended by [Android signing documentation](https://developer.android.com/studio/publish/app-signing).
- Retain the alias, certificate SHA-256 fingerprint and expiry in the private release record; keep private key/password out of build logs and source.
- Make a protected independent backup, then verify the backup can be opened before signing the first published version. Losing the only key risks breaking updates; storing the backup only beside the original is insufficient.
- Build configuration will read the keystore path/alias/password externally. Missing inputs must never fall back to debug signing. Unsigned packaging checks remain separate from a signed store-ready artifact.
- Verify the signed APK with `apksigner verify --verbose --print-certs`, inspect package/version/non-debuggable state, then test install and a same-key versionCode-increasing update without losing edits/settings.
- A debug-signed installation with the same package cannot be assumed to update to a new release key. Preserve user exports and test migration separately; do not uninstall the user's existing application as a routine fix.

RuStore's [signature guidance](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication/apk-signature) recommends a consistent certificate for updates and distribution across stores. This plan uses signed APK distribution first; uploading a private signing key to a store is not authorized or required by this preparation.

## Pending acceptance

- [ ] Owner account registered and access verified.
- [x] Public developer identity/support contact confirmed on 2026-10-26: Leonid Polyakov; whiteking.power12@gmail.com. Console entry remains pending.
- [ ] Permanent key created under owner control; protected backup verified.
- [ ] External signing configuration implemented and tested without publishing secrets.
- [ ] Exact signed artifact accepted; owner approves store submission.

Sources checked 2026-10-25. Recheck account eligibility and store policy at registration/submission.
