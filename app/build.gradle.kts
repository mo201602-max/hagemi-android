plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "jp.hagemi.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "jp.hagemi.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.7.2"
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
