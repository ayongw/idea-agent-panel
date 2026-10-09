import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware
import java.io.File

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

// ==================== 部署到真实 IDE ====================

/**
 * 真实 IDE 的插件目录 = `<IDE 配置目录>/plugins`。
 *
 * 优先 `-PpluginInstallDir=<plugins 目录>` 显式指定；否则读 `ideaHome` 的
 * `product-info.json` 取 `dataDirectoryName`（如 `IntelliJIdea2026.2`）再拼出平台目录。
 *
 * 用配置文件而不是从 build 号推导（262 → 2026.2）：推导规则会随平台变，配置文件不会。
 * `providers.fileContents` 让该文件成为配置缓存输入——IDEA 升级后无需手动清缓存。
 */
private val explicitPluginsDir: File? = project.findProperty("pluginInstallDir")
    ?.toString()
    ?.takeIf { it.isNotBlank() }
    ?.let { File(it) }

val realIdePluginsDir: File? = explicitPluginsDir ?: providers
    .fileContents(layout.file(provider { File(ideaHome, "Contents/Resources/product-info.json") }))
    .asText
    .map { Regex("\"dataDirectoryName\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
    .orNull
    ?.let { dataDirName ->
        val homeDir = System.getProperty("user.home")
        val osName = System.getProperty("os.name").orEmpty()
        // 注意是 **配置目录**（config），不是系统目录（system / caches / logs）：
        // IDEA 只扫描 `<config>/plugins`，装到 system 下会「构建成功但插件不生效」。
        val base = when {
            osName.startsWith("Mac") -> File(homeDir, "Library/Application Support/JetBrains")
            osName.startsWith("Windows") -> File(System.getenv("APPDATA") ?: "$homeDir/AppData/Roaming", "JetBrains")
            else -> File(homeDir, ".config/JetBrains")
        }
        File(File(base, dataDirName), "plugins")
    }

// zip 名随 rootProject.name（改名时只需改 settings.gradle.kts 一处）
val builtPluginZip = layout.buildDirectory.file("distributions/${rootProject.name}-$version.zip")
val unpackedPluginDir = layout.buildDirectory.dir("tmp/install-plugin")

// 探测不到时给个构建目录内的占位路径：真正报错放在 doFirst，
// 否则配置期就中断——本项目 ideaHome 默认写死 /Applications，换机/换 IDE 版本会连带编译失败。
// 注意是 `<配置目录>/plugins/<插件名>`：Sync 的 into 是「内容落地根」，
// 写成 plugins 根会把 lib/ 直接平铺进 plugins/，并删掉 plugins 下原有的插件目录。
val installTargetDir: File = realIdePluginsDir
    ?.resolve(rootProject.name)
    ?: File(layout.buildDirectory.get().asFile, "tmp/install-plugin/unresolved-target")

/** 解压 `buildPlugin` 的 zip 到构建目录（zip 内已含顶层插件目录） */
val unpackPlugin by tasks.registering(Sync::class) {
    group = "intellij platform"
    description = "解压 buildPlugin 产出的插件 zip 到构建目录"
    dependsOn(tasks.named("buildPlugin"))
    from(zipTree(builtPluginZip))
    into(unpackedPluginDir)
}

/**
 * 把插件装到真实 IDE 的 plugins 目录：`./gradlew installPlugin`（已含打包）。
 *
 * 与 `buildPlugin` **刻意分开**：`buildPlugin` 只产出 zip、不碰真实 IDE，
 * 避免「只想打个包」也顺手改掉 IDE 的插件目录（CI、只想验证构建产物时都需要这个语义）。
 *
 * IPGP 2.x 不提供该能力（只有 buildPlugin / prepareSandbox / runIde / publishPlugin…），
 * 这里用 `Sync` 一步完成「删除目标插件目录 + 写入新包」，天然幂等。
 *
 * 生效仍需**重启 IDE**：真实 IDE 默认不开 `idea.auto.reload.plugins`（只有 IPGP 的 runIde 会传）。
 * 想免重启，自行在 `Help | Edit Custom Properties` 加 `idea.auto.reload.plugins=true`。
 *
 * 注意：目标目录不要做成软链（Gradle 会跟随软链删真实目录里的文件），也不要在 IDE 运行中
 * 手动改这个目录——统一走本任务，避免与 IDE 的插件管理状态不一致。
 *
 * 实现约束：doFirst/doLast 是执行期 action，必须**只读 task 自身的 Property**。
 * 直接引用脚本里的 val 会把 Gradle 脚本对象拖进闭包，配置缓存序列化失败。
 */
val installPlugin by tasks.registering(Sync::class) {
    group = "intellij platform"
    description = "把插件安装到真实 IDE 的 plugins 目录（需重启 IDE 生效）"
    dependsOn(unpackPlugin)
    from(unpackedPluginDir.map { it.dir(rootProject.name) })
    into(installTargetDir)

    // ↓ 配置期从脚本取值后存进 task 自身的 Property，供执行期回调使用
    val targetResolved = objects.property<Boolean>().convention(realIdePluginsDir != null)
    // 是否由本脚本**推导**而来（显式 -PpluginInstallDir 时跳过 options/ 自检：用户已明确指定）
    val targetDerived = objects.property<Boolean>()
        .convention(explicitPluginsDir == null && realIdePluginsDir != null)
    val ideaProductInfo = objects.property<String>()
        .convention(File(ideaHome, "Contents/Resources/product-info.json").path)
    val pluginVersion = objects.property<String>().convention(version.toString())

    doFirst {
        check(targetResolved.get()) {
            "未找到 ${ideaProductInfo.get()}，无法推导 IDE 的插件目录；" +
                "请用 -PpluginInstallDir=<IDEA 的 plugins 目录> 显式指定"
        }
        val pluginsDir = destinationDir.parentFile
        check(pluginsDir.isDirectory) {
            "IDE 插件目录不存在：$pluginsDir；" +
                "请用 -PpluginInstallDir=<IDEA 的 plugins 目录> 显式指定"
        }
        // 自检：IDEA 只扫描**配置目录**下的 plugins。装到系统目录（caches/logs 同级）会
        // 「构建成功但插件不生效」且无任何报错——本项目踩过一次，故在此硬拦。
        // 判据：配置目录必有 options/，系统目录（system / caches / logs）必无。
        if (targetDerived.get()) {
            val configDir = pluginsDir.parentFile
            check(File(configDir, "options").isDirectory) {
                "推导出的插件目录 $pluginsDir 不像 IDEA 的配置目录（其上应有 options/）。" +
                    "装到非配置目录会导致插件不生效；" +
                    "请用 -PpluginInstallDir=<IDEA 的 plugins 目录> 显式指定"
            }
        }
    }

    doLast {
        println(
            """
            |
            |✅ 插件已安装：${destinationDir}
            |   版本：${pluginVersion.get()}
            |   ⚠️ 重启 IntelliJ IDEA 后生效
            |     （免重启热重载：在 Help | Edit Custom Properties 里加 idea.auto.reload.plugins=true）
            """.trimMargin()
        )
    }
}
