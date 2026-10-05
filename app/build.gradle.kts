import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

// Release signing lives in keystore.properties (gitignored) + a keystore file
// at the repo root. Fresh clones without them still build, but the release
// APK is left UNSIGNED (app-release-unsigned.apk) — never silently signed
// with the public debug key, which anyone could use to forge an "update".
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProps.isNotEmpty() &&
    rootProject.file(keystoreProps.getProperty("storeFile")).exists()

android {
    namespace = "com.paysync.gateway"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.paysync.gateway"
        minSdk = 26
        targetSdk = 34
        // 3 / 1.1.1: service-resume fix above the published v1.1.0 (versionCode 2).
        versionCode = 3
        versionName = "1.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinker on: dead code + reflective-access risk handled by
            // proguard-rules.pro (Gson models, crypto providers).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn("keystore.properties missing: release APK will be UNSIGNED")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // AppLog gates data-bearing logs on BuildConfig.DEBUG.
        buildConfig = true
    }
    kapt {
        arguments {
            // Exported Room schema JSONs are committed under app/schemas and
            // validated by the instrumented schema-integrity test.
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }
    composeOptions {
        // Kotlin 1.9.24 pairs with Compose Compiler 1.5.14
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    sourceSets {
        // Room schema JSONs must be visible to MigrationTestHelper in
        // instrumented tests (read from the androidTest APK assets).
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core + Compose UI (Material3)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Material Components: provides the XML Theme.Material3 parent used by themes.xml
    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // LEGACY READ-ONLY: settings are stored with our own Keystore AES-GCM code
    // (data/security). This deprecated library is used only to read the
    // pre-v1.2 EncryptedSharedPreferences file once during migration; remove
    // it in a later release once installs have migrated.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Network: OkHttp + Gson
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Persistence: Room (annotation processing via kapt)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Background: WorkManager (polling + dispatch retries)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Test-only fake backend: proves the HMAC covers the exact raw bytes sent
    // (same version as the production OkHttp client).
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    // Phase-6 device-like verification: fake backend, schema integrity,
    // worker outbox behavior, coroutine-driven receiver concurrency.
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.work:work-testing:2.9.0")
}