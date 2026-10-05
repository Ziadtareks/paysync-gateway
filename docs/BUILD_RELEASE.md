# Building the release APK (v1.2.0)

The release APK must be built on **your** machine: it has to be signed with
your release key (the same one as v1.1.x), otherwise it cannot be installed
over an existing install and the settings migration cannot run.

## Requirements

- Android Studio (or JDK 17 + Android SDK with platform 34).
- `keystore.properties` + your `.jks` file in the repo root (gitignored —
  never commit them):

  ```properties
  storeFile=your-release-key.jks
  storePassword=…
  keyAlias=…
  keyPassword=…
  ```

## Build

From the repo root, on branch `claude/quirky-ritchie-i58scf`:

```bash
# 1. Unit tests + lint + release build (R8). All must pass.
./gradlew :app:testDebugUnitTest :app:lint :app:assembleRelease

# 2. Optional but recommended: device tests on a connected phone/emulator
#    (includes SettingsMigrationTest, RepositoryReliabilityTest, OutboxWorkerTest).
./gradlew :app:connectedDebugAndroidTest
```

If `keystore.properties` is missing, the build prints
`release APK will be UNSIGNED` and produces `app-release-unsigned.apk` —
do not ship that.

Output: `app/build/outputs/apk/release/app-release.apk`

## Verify before publishing

```bash
cp app/build/outputs/apk/release/app-release.apk PaySync-Gateway.apk

# Must print the SAME certificate as v1.1.x:
# SHA-256: b28b28630e30714332f0857bd8d13380e5af7294bfb2cde6475ef64fa52c8748
apksigner verify --print-certs PaySync-Gateway.apk

# New file checksum for the README:
sha256sum PaySync-Gateway.apk
```

Then run `docs/DEVICE_TEST_CHECKLIST.md` (install over v1.1.1 first!).

## Publish

1. Commit the signed APK as `release/app-release.apk` (the README download
   button points to it on `main`) and put its `sha256sum` in README.md
   ("Verify the download"); date the CHANGELOG heading.
2. Recommended: also create GitHub release `v1.2.0` and attach the same file
   as **`PaySync-Gateway.apk`**, so the Releases page and the in-app update
   notice (which reads the latest GitHub release) know about it.
