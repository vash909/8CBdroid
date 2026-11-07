plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "net.vash.ft8cbdroid.hamlib"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    externalNativeBuild { cmake { path = file("native/CMakeLists.txt"); version = "3.22.1" } }
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

val hamlibTasks = listOf("arm64-v8a", "x86_64").map { abi ->
    tasks.register<Exec>("buildHamlib${abi.replace("-", "").replace("_", "")}") {
        inputs.files(rootProject.file("scripts/build_hamlib.py"), file("source.json"))
        inputs.dir("patches")
        inputs.file(androidComponents.sdkComponents.sdkDirectory.map {
            it.file("ndk/${android.ndkVersion}/source.properties")
        })
        val destination = layout.buildDirectory.dir("hamlib/$abi")
        outputs.dir(destination.map { it.dir("lib") })
        outputs.dir(destination.map { it.dir("include") })
        workingDir(rootProject.projectDir)
        doFirst {
            commandLine("python3", rootProject.file("scripts/build_hamlib.py"),
                "--output", destination.get().asFile, "--abi", abi,
                "--ndk", androidComponents.sdkComponents.sdkDirectory.get().dir("ndk/${android.ndkVersion}").asFile,
                "--jobs", System.getenv("CMAKE_BUILD_PARALLEL_LEVEL")?.toIntOrNull()?.coerceIn(1, 4) ?: 2)
        }
    }
}
hamlibTasks[1].configure { mustRunAfter(hamlibTasks[0]) }
tasks.named("preBuild") { dependsOn(hamlibTasks) }
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(hamlibTasks)
}
