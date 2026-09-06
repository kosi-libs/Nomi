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
    testImplementation(projects.runtime.nomiRuntimeClient)
}

mavenPublishing {
    pom {
        name = "nomi-processor-client"
        description = "Nomi processor for generating shared code"
    }
}
