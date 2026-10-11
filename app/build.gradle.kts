plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.mcns.virtualfly"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.mcns.virtualfly"
        minSdk = 23
        targetSdk = 28
        versionCode = 2
        versionName = "0.2"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
