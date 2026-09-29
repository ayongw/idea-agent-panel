dependencies {
    implementation(project(":opencode-shared"))

    intellijPlatform {
        bundledModule("intellij.platform.editor")
        bundledModule("intellij.platform.frontend")
    }
}