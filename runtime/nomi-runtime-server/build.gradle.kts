plugins {
    alias(libs.plugins.kotlin.jvm)
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
}

mavenPublishing {
    pom {
        name = "nomi-runtime-server"
        description = "Nomi runtime for server applications"
    }
}
