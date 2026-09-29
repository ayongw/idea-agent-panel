import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware
import java.io.File

group = "com.ayongw.plugins"
version = "0.1.0"

// IDEA 安装路径配置 - 支持通过 gradle.properties 或环境变量配置
val ideaHome: String = project.findProperty("ideaHome")?.toString() 
    ?: System.getenv("IDEA_HOME") 
    ?: "/Applications/IntelliJ IDEA.app"

plugins {
    application
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.kotlin.jvm")
    id("rpc") apply false
    id("org.jetbrains.kotlin.plugin.serialization") apply false
}

// Configure Java toolchain to use JBR 25 from local IntelliJ IDEA (project-scoped)
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

subprojects {
    apply(plugin = "rpc")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    // Configure subproject-specific dependencies
    when (name) {
        "opencode-shared" -> {
            apply(plugin = "org.jetbrains.intellij.platform.module")
            dependencies {
                intellijPlatform {
                    compileOnly("org.jetbrains.kotlin:kotlin-serialization:2.3.20")
                    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
                }
            }
        }
        "opencode-frontend" -> {
            apply(plugin = "org.jetbrains.intellij.platform.module")
            dependencies {
                intellijPlatform {
                    local(ideaHome)
                    compileOnly("org.jetbrains.kotlin:kotlin-serialization:2.3.20")
                    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
                }
                implementation(project(":opencode-shared"))
                implementation("org.jetbrains:markdown:0.7.3")
            }
        }
        "opencode-backend" -> {
            apply(plugin = "org.jetbrains.intellij.platform.module")
            dependencies {
                intellijPlatform {
                    bundledModule("intellij.platform.kernel.backend")
                    bundledModule("intellij.platform.rpc.backend")
                    bundledModule("intellij.platform.backend")
                }
                implementation(project(":opencode-shared"))
            }
        }
    }
}

dependencies {
    intellijPlatform {
        // Use local IntelliJ IDEA 2026.2.3 installation for root
        local(ideaHome)

        pluginModule(implementation(project(":opencode-shared")))
        pluginModule(implementation(project(":opencode-frontend")))
        pluginModule(implementation(project(":opencode-backend")))
        testFramework(TestFrameworkType.Platform)

        // 如果需要 Java PSI 支持（上下文注入需要）
        bundledPlugin("com.intellij.java")
    }
}

// pluginVerification {
//     ides {
//         create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2.3")
//     }
// }
intellijPlatform {
    splitMode = true
    pluginInstallationTarget = SplitModeAware.PluginInstallationTarget.BOTH
}