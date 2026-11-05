plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "net.vash.ft8cbdroid.dsp"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    externalNativeBuild {
        cmake {
            path = file("../../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core"))
    implementation(libs.coroutines.android)
}

val copyNdkNotices by tasks.registering(Copy::class) {
    from(androidComponents.sdkComponents.sdkDirectory.map {
        it.file("ndk/${android.ndkVersion}/NOTICE.toolchain")
    })
    into(layout.buildDirectory.dir("generated/ndk-notices/licenses"))
    rename { "Android-NDK-toolchain-NOTICE.txt" }
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/ndk-notices"))
tasks.named("preBuild") { dependsOn(copyNdkNotices) }
