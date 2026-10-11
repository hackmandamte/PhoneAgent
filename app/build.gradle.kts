plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phoneagent"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.phoneagent"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
        ndk {
            // Keep common modern ABIs; reduces size vs including every ABI
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // Maintained FFmpeg-Kit (drop-in for retired arthenica package). Full LGPL build.
    // Supports modern Android + 16KB page size.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")
}
