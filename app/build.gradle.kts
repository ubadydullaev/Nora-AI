
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "uz.nora.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "uz.nora.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            abiFilters += "arm64-v8a"
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
    implementation(files("libs/sherpa-onnx-2.0.3.aar"))

    // Telefon ichida ishlaydigan llama.cpp
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")

    // Asinxron vazifalar
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
