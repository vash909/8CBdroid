plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
kotlin.sourceSets["test"].kotlin.srcDir("src/testReference/kotlin")
dependencies { testImplementation(libs.junit) }
