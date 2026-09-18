plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(findProperty("jvm.toolchain")!!.toString().toInt())
    explicitApi()
}

dependencies {
    implementation(projects.processor.nomiProcessorCommon)

    implementation(libs.kotlinPoet.ksp)
    implementation(libs.ksp.symbolProcessingApi)

    // Fixture resources need their Nomi… implementations generated in the same compilation.
    testImplementation(projects.processor.nomiProcessorShared)
    testImplementation(projects.runtime.nomiRuntimeClient)

    testImplementation(libs.kctfork.ksp)
    testImplementation(libs.kotlin.test.junit)
    // Pins the KSP2 implementation to match ksp-symbolProcessingApi's version - without this,
    // kctfork:ksp's own POM drags in an older impl (2.3.9) than the API we compile against (2.3.11),
    // an API-newer-than-impl skew that risks AbstractMethodError.
    testImplementation(libs.ksp.aaEmbeddable)
}

mavenPublishing {
    pom {
        name = "nomi-processor-client"
        description = "Nomi processor for generating client code"
    }
}
