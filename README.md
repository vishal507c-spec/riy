# riy

Minimal Android app (Kotlin) with a production-grade **GitHub auto-update** feature.

## How the update flow works

```text
git tag v1.0.1 && git push origin v1.0.1
        ↓
GitHub Actions: unit tests → release APK (version derived from the tag)
        ↓
GitHub Release published with riy-v1.0.1.apk attached
        ↓
User opens the installed app
        ↓
One lightweight GET to api.github.com/repos/vishal507c-spec/riy/releases/latest
        ↓
latestVersionCode > installedVersionCode ?
        ↓ yes
"New Update Available" dialog → [Update Now] [Later]
        ↓
App-controlled HTTPS download of the private APK (real progress)
        ↓
APK verified (package name + no downgrade) → system installer
        ↓
Android verifies the signature at install time
```

## Versioning

- `versionName` = git tag without the `v` prefix (e.g. `v1.0.10` → `1.0.10`).
- `versionCode` = `major * 1_000_000 + minor * 1_000 + patch` (e.g. `1.0.10` → `1_001_010`).
  Comparison is numeric, so `1.0.10 > 1.0.9` with no string-comparison bugs.
  Invalid tags **fail the build** instead of silently mis-versioning.
- Skipping releases is fine: the app always jumps straight to the latest stable release.

## Publishing an update

1. Commit your change and push to `main`.
2. Tag and push:
   ```bash
   git tag v1.0.1
   git push origin v1.0.1
   ```
3. GitHub Actions builds, tests, and publishes the Release with the APK.
4. Users get the dialog the next time they open the app (checks are throttled
   to at most once per hour per device).

## Optional: real release signing

The workflow falls back to the debug signing key if secrets are absent, but a
real distribution must always use the SAME key for every release (Android
rejects updates signed with a different key). Configure these repository secrets:

- `RELEASE_KEYSTORE_BASE64` — base64 of your `.jks` keystore (`base64 -w0 release.jks`)
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

## Local development

```bash
./gradlew :app:testDebugUnitTest   # unit tests (version comparison, release parsing)
./gradlew :app:assembleRelease "-PversionTag=v1.0.1"
```

Requires JDK 17+ and an Android SDK (`ANDROID_HOME`). Open in Android Studio and run on a device/emulator.

## Where the code lives

| File | Purpose |
|---|---|
| `app/src/main/java/com/vishal/riy/update/VersionUtils.kt` | Numeric version parsing/comparison |
| `app/src/main/java/com/vishal/riy/update/ReleaseInfo.kt` | GitHub release metadata parsing + URL allowlist |
| `app/src/main/java/com/vishal/riy/update/UpdateChecker.kt` | Single lightweight HTTPS update check |
| `app/src/main/java/com/vishal/riy/update/UpdatePreferences.kt` | 1-hour check throttle |
| `app/src/main/java/com/vishal/riy/update/UpdateInstaller.kt` | app-controlled HTTPS download, APK verification, install intent |
| `app/src/main/java/com/vishal/riy/update/UpdateDialogFragment.kt` | "New Update Available" dialog + progress |
| `app/src/main/java/com/vishal/riy/MainActivity.kt` | Fires the async check on startup |
| `.github/workflows/release.yml` | Tag-driven build/test/release pipeline |
