plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

kotlin {
    jvmToolchain(findProperty("jvm.toolchain")!!.toString().toInt())
}

dependencies {
    implementation(projects.demo.demoShared)
    implementation(projects.runtime.nomiRuntimeServer)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.contentNegotiation)
    implementation(libs.ktor.server.swagger)

    ksp(projects.processor.nomiProcessorServer)
}
