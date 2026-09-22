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
    // Generated routes call Route.describe/hide when a service opts into OpenAPI, so this has to
    // reach the consumer's compile classpath. It is already on their runtime one, via ContentNegotiation.
    api(libs.ktor.server.routingOpenApi)

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
