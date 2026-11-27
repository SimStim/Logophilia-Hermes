// Top-level build file: pins the plugin versions used by all modules.
// Plugin application and module configuration live in app/build.gradle.kts.
//
// AGP 9 provides built-in Kotlin support; the Compose compiler plugin is
// applied explicitly, which also aligns the Kotlin Gradle plugin to 2.4.21.
plugins {
    id("com.android.application") version "9.5.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}
