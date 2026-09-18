plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(findProperty("jvm.toolchain")!!.toString().toInt())
    explicitApi()
}

dependencies {
    // KSP types appear in this module's own public signatures (ResourceAnalyzer, Reporter,
    // AnalyzedResource, ...), so they belong in its published POM's compile scope.
    api(libs.ksp.symbolProcessingApi)

    testImplementation(projects.runtime.nomiRuntimeShared)

    testImplementation(libs.kotlin.test.junit)
}

mavenPublishing {
    pom {
        name = "nomi-processor-common"
        description = "Common analysis utilities for Nomi processors"
    }
}
