plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // KSP types appear in this module's own public signatures (ResourceAnalyzer, Reporter,
    // AnalyzedResource, ...), so they belong in its published POM's compile scope.
    api(libs.ksp.symbolProcessingApi)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(projects.runtime.nomiRuntimeShared)
}

mavenPublishing {
    pom {
        name = "nomi-processor-common"
        description = "Common analysis utilities for Nomi processors"
    }
}
