plugins {
    id("com.android.application")
}

android {
    namespace = "com.klim.voicedatasetcollector"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.klim.voicedatasetcollector"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Silero VAD model + ONNX Runtime are bundled by this library.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
}
