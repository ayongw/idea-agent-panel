import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware

group = "com.ayongw.idea"

/** 基础版本号，需与 CHANGELOG.md 的版本标题一致 */
val baseVersion = "0.1.0"

/**
 * 构建号：-PbuildNumber=N 优先，其次 git 提交数。
 * 用于区分每次打出的包（zip 名与 IDE 插件列表显示的版本都会带上）。
 * git 不可用（如无 .git / PATH 缺 git）时回退数字 0，保证格式始终是 `0.1.0.N`。
 */
val buildNumber: String = providers.gradleProperty("buildNumber")
    .orElse(
        providers.provider {
            runCatching {
                providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
                    .standardOutput.asText.get().trim()
            }.getOrNull()?.takeIf { it.toIntOrNull() != null } ?: "0"
        }
    )
    .get()

// 传 -PbuildNumber=（留空）可打出不带构建号的正式版本 0.1.0
version = buildNumber.trim().let { if (it.isEmpty()) baseVersion else "$baseVersion.$it" }

// IDEA 安装路径配置 - 支持通过 gradle.properties 或环境变量配置
val ideaHome: String = project.findProperty("ideaHome")?.toString() 
    ?: System.getenv("IDEA_HOME") 
    ?: "/Applications/IntelliJ IDEA.app"

plugins {
    application
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog") version "2.5.0"
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
                    // TSD-33：提交窗口「生成提交信息」按钮需要 VcsDataKeys / CommitMessageI
                    // （位于 intellij.platform.vcs.jar，platform-api 公开 API）
                    // 注意：只声明具体 bundled module，不加 com.intellij.modules.platform（split mode 限制）
                    bundledModule("intellij.platform.vcs")
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
                    // 进程管理类（GeneralCommandLine / OSProcessHandler / KillableProcessHandler）来自公开模块
                    // intellij.platform.util（util.jar），随 platform.backend 传递可得；无需（也不允许）声明
                    // internal 可见性的 intellij.platform.execution，见 TSD-31 §12 A8
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

    // 单元测试（JUnit 4）
    testImplementation("junit:junit:4.13.2")
    // 事件流单测：用 MockWebServer 回放真实抓帧 fixture
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

intellijPlatform {
    splitMode = true
    pluginInstallationTarget = SplitModeAware.PluginInstallationTarget.BOTH

    pluginConfiguration {
        ideaVersion {
            // 最低支持 2026.2（build 262）：Phase 2 需要平台 2026.2+ 的 API（如 DebouncedUpdates）；不设 until-build，保持向上兼容
            sinceBuild = "262"
        }
    }

    // 针对最低支持版本做 API 兼容性校验（首次执行会下载对应 IDE 发行版）
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2")
        }
    }
}

// 从 CHANGELOG.md 生成插件变更说明（显示在 IDEA 的 Plugins → What's New）
val changelog = project.changelog // 配置缓存兼容：先取到本地变量
tasks.patchPluginXml {
    changeNotes = providers.provider {
        with(changelog) {
            renderItem(
                (getOrNull(baseVersion) ?: getUnreleased()).withHeader(false).withEmptySections(false),
                Changelog.OutputType.HTML
            )
        }
    }
}

/**
 * 集成测试（`*ITest`）默认可不跑 —— 它们需要真实可达的 opencode serve。
 * 显式 `./gradlew test -Pit=true` 时才纳入执行（同时把开关透给测试 JVM）。
 */
tasks.test {
    val integrationTestEnabled = (findProperty("it") as String?)?.toBoolean() ?: false
    if (!integrationTestEnabled) {
        exclude("**/*ITest.class")
    }
    systemProperty("opencode.it", integrationTestEnabled.toString())
}