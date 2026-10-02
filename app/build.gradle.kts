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
        versionCode = 2
        versionName = "0.2.0"
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

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    // Silero model is in assets; inference runs on CPU.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
}
