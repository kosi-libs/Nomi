plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.ksp.symbolProcessingApi)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(projects.runtime.nomiRuntimeShared)
}

mavenPublishing {
    pom {
        name = "nomi-processor-shared-analysis"
        description = "Shared analysis utilities for Nomi processors"
    }
}
