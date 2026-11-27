import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Local secrets (secrets.properties, git-ignored) are compiled into BuildConfig.
// The file is optional so that a fresh checkout builds out of the box;
// see SPECIFICATION.md §3 (the initial API key is compiled into the app).
val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Release signing identity (keystore.properties, git-ignored).
// See SPECIFICATION.md §10: the keystore is created early and kept out of VCS.
val keystore = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "eu.logophilia.hermes"
    // Minor-level API 37.2: required by the current stable Compose BOM
    // (2026.11.01); targetSdk remains the current stable major level, 37.
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "eu.logophilia.hermes"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField(
            "String",
            "API_KEY",
            "\"${secrets.getProperty("API_KEY") ?: ""}\"",
        )
    }

    signingConfigs {
        if (keystore.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystore.containsKey("storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Kotlin jvmTarget defaults to this targetCompatibility under built-in
    // Kotlin (AGP 9).
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.1")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Networking and JSON for the API client (SPECIFICATION.md §2, §3).
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")

    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Offline unit tests (e.g. the manifest validator, SPECIFICATION.md §3).
    testImplementation("junit:junit:4.13.2")
}
