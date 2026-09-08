plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    alias(libs.plugins.mavenPublish) apply false
}

allprojects {
    group = "org.kodein.rest.nomi"
    version = "0.1.0"
}
