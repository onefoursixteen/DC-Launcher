import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release key lives in signing/keystore.properties (git-ignored). Without it, release builds
// fall back to the debug key so the project still builds on another machine.
val releaseSigning = rootProject.file("signing/keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use(::load) }
}

android {
    namespace = "me.cazan.zen"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.cazan.zen"
        minSdk = 23          // Android 6.0+: covers most older smart TVs and boxes
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = rootProject.file("signing").resolve(releaseSigning.getProperty("storeFile").substringAfterLast('/'))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    // Don't embed the Play dependency-metadata blob; nothing phones home.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // Deliberately tiny: Compose foundation only (no Material, no image/DI/network libraries).
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.core:core-ktx:1.18.0")
}
