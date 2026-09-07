plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(projects.processor.nomiProcessorCommon)
    implementation(libs.ksp.symbolProcessingApi)
    implementation(libs.kotlinPoet.ksp)

    testImplementation(libs.kctfork.ksp)
    // Pins the KSP2 implementation to match ksp-symbolProcessingApi's version - without this,
    // kctfork:ksp's own POM drags in an older impl (2.3.9) than the API we compile against (2.3.11),
    // an API-newer-than-impl skew that risks AbstractMethodError.
    testImplementation(libs.ksp.aaEmbeddable)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(projects.runtime.nomiRuntimeShared)
}

mavenPublishing {
    pom {
        name = "nomi-processor-shared"
        description = "Nomi processor for generating shared code"
    }
}
