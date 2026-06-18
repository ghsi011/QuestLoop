# Release signing

QuestLoop's release APK is signed with a self-managed keystore (not Play App
Signing). The build never hard-codes or commits any signing material — it is
resolved at build time from either:

1. A local, git-ignored `keystore.properties` at the repo root, or
2. Environment variables (used by CI from GitHub Actions secrets).

When no material is found, the `release` build type is left unsigned and the
release workflow falls back to a debug-signed APK, so a secret-less checkout
still builds.

## Material

The build reads four values (`app/build.gradle.kts`, `signingValue(...)`):

| Key                          | Meaning                                  |
| ---------------------------- | ---------------------------------------- |
| `RELEASE_KEYSTORE_FILE`      | Path to the `.jks` keystore             |
| `RELEASE_KEYSTORE_PASSWORD`  | Keystore (store) password                |
| `RELEASE_KEY_ALIAS`          | Key alias (this repo uses `questloop`)   |
| `RELEASE_KEY_PASSWORD`       | Key password                             |

## CI (GitHub Actions)

`release.yml` decodes the keystore from a base64 secret into a temp file, then
builds `:app:assembleRelease`. Add these repository secrets
(**Settings → Secrets and variables → Actions**):

| Secret                       | Value                                            |
| ---------------------------- | ------------------------------------------------ |
| `RELEASE_KEYSTORE_BASE64`    | `base64 -w0 questloop-release.jks` output        |
| `RELEASE_KEYSTORE_PASSWORD`  | the store password                               |
| `RELEASE_KEY_ALIAS`          | `questloop`                                       |
| `RELEASE_KEY_PASSWORD`       | the key password                                 |

If `RELEASE_KEYSTORE_BASE64` is absent the workflow emits a warning and ships a
debug-signed APK instead of failing.

## Local signed build

```
# repo-root keystore.properties (git-ignored)
RELEASE_KEYSTORE_FILE=/absolute/path/to/questloop-release.jks
RELEASE_KEYSTORE_PASSWORD=…
RELEASE_KEY_ALIAS=questloop
RELEASE_KEY_PASSWORD=…
```

```
./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

## Important

- **Back up the keystore.** If it is lost, you can never publish an update that
  installs over an existing copy — Android rejects an APK signed with a
  different key. Keep the `.jks` and its passwords somewhere durable.
- **Signature change.** The earlier `v0.1.0`/`v0.2.0-experimental` builds were
  debug-signed. A device with one of those installed must uninstall it before
  the release-signed APK will install.
- **Never commit** the `.jks`, its base64, or `keystore.properties` — all are
  git-ignored.
