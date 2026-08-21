plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hpremote.agent.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hpremote.agent"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // A fixed, checked-in debug key (shared by every app-* module) so every CI
        // build (and every local build) produces the same signature. Without this,
        // Gradle auto-generates a random ~/.android/debug.keystore per machine, so a
        // fresh GitHub Actions runner signs each build differently and re-installing
        // over an older build fails with "app not installed" instead of updating it.
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":feature-remote"))
}
