plugins {
    id("com.android.application")
}

android {
    namespace = "io.aodbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.aodbridge"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // LSPosed 新 API（libxposed API 102）；编译期库，不会打进 APK
    compileOnly("io.github.libxposed:api:102.0.0")
}
