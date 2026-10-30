plugins {
    id("com.android.application") version "9.2.1" apply false
    // AGP 9 compiles Kotlin itself; only the Compose compiler plugin is added.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
