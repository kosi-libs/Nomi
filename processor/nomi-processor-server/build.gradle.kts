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
    testImplementation(projects.runtime.nomiRuntimeServer)

    testImplementation(libs.kctfork.ksp)
    testImplementation(libs.kotlin.test.junit)
    // Pins the KSP2 implementation to ksp-symbolProcessingApi's version. kctfork:ksp's POM carries
    // whichever KSP it was built against, and an implementation older than the API we compile
    // against is an API-newer-than-impl skew that risks AbstractMethodError. The two agree today
    // (kctfork 0.14.0 brings 2.3.12); this keeps them agreeing when either side moves next.
    testImplementation(libs.ksp.aaEmbeddable)
}

mavenPublishing {
    pom {
        name = "nomi-processor-server"
        description = "Nomi processor for generating server code"
    }
}
