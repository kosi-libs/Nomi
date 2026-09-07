plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(projects.runtime.nomiRuntimeShared)
    api(libs.ktor.server)
    implementation(libs.kotlinx.coroutines)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.testHost)
    testImplementation(libs.ktor.server.contentNegotiation)
    testImplementation(libs.ktor.serialization.json)
    testImplementation(libs.kotlinx.serialization.json)
}

mavenPublishing {
    pom {
        name = "nomi-runtime-server"
        description = "Nomi runtime for server applications"
    }
}
