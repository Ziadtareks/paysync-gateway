# Building & publishing a release

Release APKs are built and signed on the maintainer's machine. Every release
must be signed with the **same release key** as all previous ones; otherwise
Android refuses to install it over an existing install, and users would have
to uninstall (losing their settings).

## Requirements

- Android Studio, or JDK 17 + Android SDK platform 34.
- `keystore.properties` + the release `.jks` file in the repo root. Both are
  gitignored — never commit them:

  ```properties
  storeFile=your-release-key.jks
  storePassword=…
  keyAlias=…
  keyPassword=…
  ```

## 1. Prepare

- Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`.
- Add the version's entry to [`CHANGELOG.md`](../CHANGELOG.md).

## 2. Build

From the repo root on `main`:

```bash
# Unit tests + lint + signed release build (R8). All must pass.
./gradlew :app:testDebugUnitTest :app:lint :app:assembleRelease

# Device tests on a connected phone or emulator.
./gradlew :app:connectedDebugAndroidTest
```

Output: `app/build/outputs/apk/release/app-release.apk`.

If `keystore.properties` is missing, the build prints
`release APK will be UNSIGNED` and produces `app-release-unsigned.apk` —
never publish that file.

## 3. Verify

```bash
# Must print the release certificate:
# SHA-256: b28b28630e30714332f0857bd8d13380e5af7294bfb2cde6475ef64fa52c8748
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk

# Checksum for the README "Verify the download" section:
sha256sum app/build/outputs/apk/release/app-release.apk
```

Then run the real-device checklist,
[`DEVICE_TEST_CHECKLIST.md`](DEVICE_TEST_CHECKLIST.md) — always including the
**install-over-the-previous-release** upgrade test.

## 4. Publish

1. Update the checksum and version in `README.md` and date the CHANGELOG entry.
2. Tag the release commit `vX.Y.Z` and create a GitHub Release for it.
3. Attach the APK as **`app-release.apk`** (keep this exact asset name: the
   README links to the latest release, and the in-app update notice reads the
   latest GitHub release).

APKs are never committed to the repository (`*.apk` is gitignored); the
GitHub Release is the only distribution channel.
