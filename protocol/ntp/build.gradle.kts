plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    api(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
