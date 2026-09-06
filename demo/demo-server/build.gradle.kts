plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(projects.demo.demoShared)
    implementation(projects.runtime.nomiRuntimeServer)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.contentNegotiation)
    implementation(libs.ktor.serialization.json)
}