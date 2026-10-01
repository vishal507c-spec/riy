import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Versioning strategy (mirrors com.vishal.riy.update.VersionUtils — keep in sync):
 *
 *   versionCode = major * 1_000_000 + minor * 1_000 + patch
 *
 * This guarantees a monotonically increasing numeric versionCode with correct
 * ordering (e.g. 1.0.10 -> 1_001_010 > 1.0.9 -> 1_000_009) and no string
 * comparison bugs. Each segment must be 0..999; invalid tags FAIL the build
 * loudly instead of silently producing a bad version.
 */
fun versionCodeFromTag(tag: String?): Long {
    if (tag.isNullOrBlank()) return 1_000_000L // default v1.0.0
    val name = tag.removePrefix("v").removePrefix("V")
    val parts = name.split(".")
    require(parts.size == 3) { "Invalid version tag '$tag': expected vMAJOR.MINOR.PATCH" }
    val nums = parts.map { it.toLongOrNull() ?: throw GradleException("Invalid version tag '$tag': non-numeric segment") }
    require(nums.all { it in 0..999 }) { "Invalid version tag '$tag': each segment must be 0..999" }
    return nums[0] * 1_000_000L + nums[1] * 1_000L + nums[2]
}

fun versionNameFromTag(tag: String?): String = tag?.trim()?.removePrefix("v")?.removePrefix("V")?.takeIf { it.isNotBlank() } ?: "1.0.0"

val releaseTag: String? = (project.findProperty("versionTag") as String?)?.trim()?.takeIf { it.isNotBlank() }
val computedVersionName = versionNameFromTag(releaseTag)
val computedVersionCode = versionCodeFromTag(releaseTag)

/**
 * Fine-grained GitHub PAT for reading the PRIVATE repository releases + assets.
 * Loaded ONLY from LOCAL config (never committed / never hard-coded):
 *   1. env var GITHUB_TOKEN
 *   2. Gradle property GITHUB_TOKEN (gradle.properties or -P)
 *   3. local.properties key GITHUB_TOKEN (gitignored, most convenient)
 * Returns "" when not configured, so builds without a token simply skip
 * authenticated update checks gracefully (public/anonymous request no-ops).
 */
fun githubToken(): String {
    val env = System.getenv("GITHUB_TOKEN")
    if (!env.isNullOrBlank()) return env.trim()
    val prop = (project.findProperty("GITHUB_TOKEN") as String?)?.takeIf { it.isNotBlank() }
    if (prop != null) return prop.trim()
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        val props = Properties()
        localFile.inputStream().use { props.load(it) }
        val fromFile = props.getProperty("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }
        if (fromFile != null) return fromFile.trim()
    }
    return ""
}

fun escapeForJavaString(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

val gitHubToken: String = githubToken()

android {
    namespace = "com.vishal.riy"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
        compose = true
    }

    defaultConfig {
        applicationId = "com.vishal.riy"
        minSdk = 24
        targetSdk = 36
        // Max possible code is 999*1_000_000+999*1_000+999 = 999_999_999 < Int.MAX_VALUE,
        // so the Int cast is always safe.
        versionCode = computedVersionCode.toInt()
        versionName = computedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Drive backup destination is PRECONFIGURED in the build (no secrets here;
        // the Google account is authorized at runtime, never hardcoded).
        buildConfigField("boolean", "REMOTE_BACKUP_ENABLED", "true")
        buildConfigField("String", "DRIVE_FOLDER", "\"RiyBackup\"")
    }

    signingConfigs {
        // Release signing is injected via environment variables by CI (real
        // release keystore via secrets). If those are absent, we sign with the
        // FIXED debug keystore committed at the repo root (debug.keystore,
        // standard Android debug credentials: storepass/keypass "android",
        // alias "androiddebugkey"). This keeps the signing identity STABLE
        // across all builds on every machine, which Android requires for
        // in-place updates — unlike AGP's auto-generated per-machine debug key.
        if (System.getenv("RELEASE_STORE_FILE") != null) {
            create("release") {
                storeFile = file(System.getenv("RELEASE_STORE_FILE"))
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
        create("releaseFallback") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // Token injected for local test builds (same local-config mechanism as
            // release) so the app can self-update from the PRIVATE GitHub repo.
            buildConfigField("String", "GITHUB_TOKEN", "\"${escapeForJavaString(gitHubToken)}\"")
            // Use the SAME repository-controlled signing identity as release so local
            // debug APKs and CI release APKs share one deterministic certificate.
            // This never falls back to the machine-specific ~/.android/debug.keystore,
            // which would produce a different key and make in-place updates fail.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("releaseFallback")
        }
        release {
            isMinifyEnabled = false
            // Release artifacts also carry the token (same local-config mechanism as
            // debug) so the app can self-update from the PRIVATE GitHub repository.
            // Injected in CI from the ANDROID_PRIVATE_PAT secret; never hard-coded.
            buildConfigField("String", "GITHUB_TOKEN", "\"${escapeForJavaString(gitHubToken)}\"")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("releaseFallback")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Google Drive backup: secretless Android OAuth (package + SHA-1, no client
    // secret) via Google Sign-In + GoogleAuthUtil access token. Drive REST is
    // called directly (no googleapis SDK).
    implementation("com.google.android.gms:play-services-auth:20.7.0")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Jetpack Compose (main UI). The BOM pins every Compose artifact version.
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    // Extended icon set (Public / Security / Shield used by the app UI).
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // org.json ships with Android; this copy is only for local JVM unit tests
    // (update ReleaseInfo parsing tests).
    testImplementation("org.json:json:20240303")
    // Deterministic scheduler tests (virtual-time debounce).
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    // On-device instrumented tests (real Compose UI on a device/emulator):
    // these verify that the lock screen appears and cannot be backed out of.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform(composeBom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
