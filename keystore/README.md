# Keystore

This directory holds the **release-signing keystore**. The `*.jks` glob
in the repo `.gitignore` keeps any actual keystore file out of git —
this README is the only thing tracked.

## Local setup (one-time, per developer)

The CI release pipeline signs release APKs with the canonical Onym
upload key. To sign a release locally (e.g. to reproduce CI's APK or
to build a one-off ad-hoc APK), drop the keystore into this directory
as `release.jks`:

```sh
cp ~/Downloads/onym-release.jks keystore/release.jks
# Verify gitignore is doing its job — git should NOT see this file:
git status keystore/release.jks   # → no output
git check-ignore -v keystore/release.jks
```

The keystore is a credentialed asset — share it via the same channel
the team uses for other secrets (1Password, Bitwarden, encrypted
Slack DM with rotation), never via plain email or unencrypted
storage.

## CI

CI doesn't read `keystore/release.jks` from disk — the keystore is
stored as a base64-encoded GitHub repository secret named
`ANDROID_KEYSTORE_BASE64`. The `Sign APK` step in
`.github/workflows/release.yml` decodes it into `/tmp/keystore.jks`
just before invoking `apksigner`, then deletes it.

To rotate the CI keystore (or set it up for the first time):

```sh
# 1. Inspect the keystore so you know what alias / passwords to set.
keytool -list -v -keystore keystore/release.jks
# Note the "Alias name:" line — that's $ANDROID_KEY_ALIAS.

# 2. Base64-encode the JKS and copy to clipboard.
base64 -i keystore/release.jks | pbcopy   # macOS
# or
base64 -w 0 keystore/release.jks | xclip  # Linux

# 3. Set the four secrets on the repo (you'll be prompted for each).
gh secret set ANDROID_KEYSTORE_BASE64    --repo onymchat/onym-android   # paste from clipboard
gh secret set ANDROID_KEYSTORE_PASSWORD  --repo onymchat/onym-android
gh secret set ANDROID_KEY_ALIAS          --repo onymchat/onym-android
gh secret set ANDROID_KEY_PASSWORD       --repo onymchat/onym-android
```

## Why a single fixed upload key matters

Sideloaded APK installs require **the same signing key** for in-place
updates. A user who installs `onym-v0.0.1.apk` signed by key X then
tries to install `onym-v0.0.2.apk` signed by key Y will see a
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` error and have to uninstall +
reinstall (losing all app data, including the encrypted identity
secrets stored in `EncryptedSharedPreferences`).

So: do not rotate the CI keystore once a release has shipped to real
users. If the keystore is compromised, rotation requires a coordinated
"reinstall to upgrade" announcement.
