plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
kotlin.sourceSets["test"].kotlin.srcDir("src/testReference/kotlin")
dependencies {
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
