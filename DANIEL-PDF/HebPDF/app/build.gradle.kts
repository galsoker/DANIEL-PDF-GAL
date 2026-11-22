plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.personal.hebpdf"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.personal.hebpdf"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "ttf" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // עריכה ושמירת PDF (רישיון Apache 2.0)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // סורק מסמכים של Google (רץ במכשיר)
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
}
