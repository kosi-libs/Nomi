plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(projects.runtime.nomiRuntimeShared)
    implementation(libs.ktor.server)
//    implementation(libs.ktor.server.cio)
//    implementation(libs.ktor.server.contentNegotiation)
//    implementation(libs.ktor.serialization.json)
}
