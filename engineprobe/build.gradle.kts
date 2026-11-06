plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "net.vash.ft8cbdroid.engineprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "net.vash.ft8cbdroid.engineprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-probe"
    }
    // Committed desktop reference audio is test data, excluded from the main app.
    sourceSets["main"].assets.srcDir("../reference/wsjtcb/channel")
    sourceSets["main"].assets.srcDir("../reference/wsjtcb/recordings")
    sourceSets["main"].assets.srcDir("../core/src/test/resources")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
kotlin.sourceSets["main"].kotlin.srcDir("../core/src/testReference/kotlin")
dependencies {
    implementation(project(":audio"))
    implementation(project(":dsp:ft8"))
    implementation(libs.coroutines.android)
}
