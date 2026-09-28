import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware

group = "com.ayongw.plugins"
version = "0.1.0"

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
        // Explicitly use JBR 25 from local IntelliJ IDEA installation
        // This is project-scoped and doesn't affect system default JDK
    }
}

subprojects {
    apply(plugin = "org.jetbrains.intellij.platform.module")
    apply(plugin = "rpc")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    intellijPlatform {
        // 使用本地 IntelliJ IDEA 2026.2.3 安装，避免从外网下载
        local("/Applications/IntelliJ IDEA.app")

        pluginModule(implementation(project(":opencode-shared")))
        pluginModule(implementation(project(":opencode-frontend")))
        pluginModule(implementation(project(":opencode-backend")))
        testFramework(TestFrameworkType.Platform)

        // 如果需要 Java PSI 支持（上下文注入需要）
        bundledPlugin("com.intellij.java")
    }
    // OkHttp + SSE 扩展（用于与 OpenCode Server 通信）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // JSON 处理（用于解析 OpenCode 的 REST/SSE 响应）
    implementation("com.google.code.gson:gson:2.11.0")

    // Markdown 转 HTML（用于 JBHtmlPane 渲染）
    implementation("org.jetbrains:markdown:0.7.3")
}

intellijPlatform {
    splitMode = true
    pluginInstallationTarget = SplitModeAware.PluginInstallationTarget.BOTH

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2.3")
        }
    }
}
