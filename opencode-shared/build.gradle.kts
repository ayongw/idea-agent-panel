plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    // Shared module only needs serialization libraries
    // IntelliJ Platform types are provided by root project via pluginModule
    implementation(libs.kotlin.serialization.core.jvm)
    implementation(libs.kotlin.serialization.json.jvm)
}