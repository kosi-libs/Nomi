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
    testImplementation(libs.kotlin.test.junit)
    testImplementation(projects.runtime.nomiRuntimeServer)
}

mavenPublishing {
    pom {
        name = "nomi-processor-server"
        description = "Nomi processor for generating server code"
    }
}
