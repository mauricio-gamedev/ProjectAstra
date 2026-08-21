# Project Astra APK signing

Android only permits an APK to update an installed package when the package name and signing certificate are compatible. Project Astra therefore supports a stable development key in GitHub Actions.

## Development signing

The CI workflow reads these repository secrets:

- `ASTRA_DEV_KEYSTORE_BASE64`
- `ASTRA_DEV_KEYSTORE_PASSWORD`
- `ASTRA_DEV_KEY_ALIAS`
- `ASTRA_DEV_KEY_PASSWORD`

`ASTRA_DEV_KEYSTORE_BASE64` must contain the base64 representation of the development keystore. The workflow decodes it only inside the GitHub Actions runner and never commits the keystore to the repository.

When all four values are configured, debug APKs use the same certificate on every build and can update each other in place as long as `applicationId` remains `io.github.astromg01.launcher` and the version is not a downgrade.

If the secrets are absent, Gradle falls back to the normal temporary Android debug certificate. Those fallback APKs are useful for CI validation but should not be considered update-stable.

## Production release

The future Play Store/release key must be separate from the development key. Do not commit either private key to this public repository. Production signing will be added as a distinct release configuration before public distribution.

## Existing alpha installs

Builds produced before stable signing was configured may use a different certificate. Android can require uninstalling one of those old alpha builds once. After the stable key is adopted, later APKs signed by that key can update over the installed app normally.
