dependencies {
    intellijPlatform {
        // Shared module needs IntelliJ Platform for basic types
        // Use local IntelliJ IDEA installation to avoid network download
        local("/Applications/IntelliJ IDEA.app")
        
        compileOnly(libs.kotlin.serialization.core.jvm)
        compileOnly(libs.kotlin.serialization.json.jvm)
    }
}