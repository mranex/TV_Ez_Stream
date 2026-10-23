plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    // Optional existing debug key permits in-place updates without losing local settings.
    providers.gradleProperty("debugKeystore").orNull?.let { keyPath ->
        signingConfigs.getByName("debug").storeFile = file(keyPath)
    }
    namespace = "vn.aquastream.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "vn.aquastream.tv"
        minSdk = 23
        targetSdk = 28
        versionCode = 14
        versionName = "0.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
