# Releasing CoverDeck

Releases are built and published by GitHub Actions (`.github/workflows/release.yml`) when a
version tag is pushed. The APK is attached to the release on the repository's Releases page.

## Once: the signing key

Every update must be signed with the same key, or Android refuses to install it over the
previous version. Create it once, keep it outside the repository, and back it up together
with its passwords.

1. Create the key (keytool ships with Android Studio, in `jbr/bin`):

   ```
   keytool -genkeypair -v -keystore coverdeck-release.jks -alias coverdeck -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Add four repository secrets under **Settings > Secrets and variables > Actions**:

   | Secret | Value |
   | --- | --- |
   | `KEYSTORE_BASE64` | the .jks file as base64 (see below) |
   | `KEYSTORE_PASSWORD` | the keystore password |
   | `KEY_ALIAS` | `coverdeck` |
   | `KEY_PASSWORD` | the key password |

   The base64 text, in PowerShell:

   ```
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("coverdeck-release.jks")) | Set-Clipboard
   ```

3. To sign release builds on your own PC too, copy `keystore.properties.example` to
   `keystore.properties` and fill it in. Both that file and `*.jks` are git-ignored.

## Each release

The tag is the version: `v1.2.0` becomes versionName `1.2.0` and versionCode `10200`
(major x 10000 + minor x 100 + patch, so minor and patch stay below 100).

```
git tag v1.2.0
git push origin v1.2.0
```

A tag with a suffix, such as `v1.3.0-beta.1`, is published as a pre-release.
