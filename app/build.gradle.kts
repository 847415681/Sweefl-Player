plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sweefl.player"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sweefl.player"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.3.2"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    // Android integration is verified on-device; plain unit tests must not silently use stub defaults.
    testOptions { unitTests.isReturnDefaultValues = false }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.media3.exoplayer)
    // Same NextLib classes/FFmpeg binaries; native Surface lifetime fix is reproducible in third-party/nextlib.
    implementation(files("libs/nextlib-media3ext-1.8.0-0.9.0-sweefl.aar"))
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.media3.effect)
    implementation(libs.media3.ui)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.json)
}
