plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(findProperty("jvm.toolchain")!!.toString().toInt())
    explicitApi()
}

dependencies {
    api(projects.runtime.nomiRuntimeShared)

    api(libs.ktor.server)

    implementation(libs.kotlinx.coroutines)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.ktor.serialization.json)
    testImplementation(libs.ktor.server.contentNegotiation)
    testImplementation(libs.ktor.server.testHost)
}

mavenPublishing {
    pom {
        name = "nomi-runtime-server"
        description = "Nomi runtime for server applications"
    }
}
