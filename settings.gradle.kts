@file:Suppress("UnstableApiUsage")

import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    repositories {
        // Use Aliyun mirrors for most plugins
        maven { url = uri("https://maven.aliyun.com/repository/central/") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin/") }
        maven { url = uri("https://maven.aliyun.com/repository/public/") }
        maven { url = uri("https://maven.aliyun.com/repository/jetbrains/") }
        // JetBrains repository for RPC plugin
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
    }
    plugins {
        id("rpc") version "2.3.20-RC2-0.1"
        id("org.jetbrains.kotlin.jvm") version "2.3.20"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20"
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.16.0"
}

rootProject.name = "opencode-idea-panel"

dependencyResolutionManagement {
    repositories {
        // Use Aliyun mirrors for all project dependencies
        maven { url = uri("https://maven.aliyun.com/repository/central/") }
        maven { url = uri("https://maven.aliyun.com/repository/public/") }
        maven { url = uri("https://maven.aliyun.com/repository/jetbrains/") }
        maven { url = uri("https://maven.aliyun.com/repository/google/") }
        maven { url = uri("https://maven.aliyun.com/repository/jcenter/") }
        intellijPlatform {
            defaultRepositories()
        }
    }
}

include("opencode-shared")
include("opencode-frontend")
include("opencode-backend")
