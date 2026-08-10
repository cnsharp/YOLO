import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform
import java.util.Properties

plugins {
    id("org.jetbrains.intellij.platform")
    kotlin("jvm") version "2.3.20"
}

/**
 * 产品名的唯一来源是 messages/YoloBundle.properties 里的 `plugin.name`。
 * 这里读出来喂给 pluginConfiguration.name（它会覆写 plugin.xml 的 <name>），
 * 于是 UI 文案、插件市场名称、Settings 页标题全部来自同一处，改名只改一行。
 */
val bundleFile = file("src/main/resources/messages/YoloBundle.properties")
val productName: String = Properties().apply {
    bundleFile.inputStream().use { load(it) }
}.getProperty("plugin.name")
    ?: error("plugin.name missing from ${bundleFile.path}")

/**
 * 版本号的唯一来源是 gradle.properties 里的 `pluginVersion`（CI 可用 -PpluginVersion=x 覆盖）。
 * 必须赋给 project.version：Gradle 的归档任务默认从它取 archiveVersion，
 * 之前只设了 pluginConfiguration.version，project.version 停在 "unspecified"，
 * 分发产物就退化成不带版本号的 yolo.zip。
 */
version = providers.gradleProperty("pluginVersion").get()

intellijPlatform {
    // 用本机已装的 IDEA 作为 SDK 进行离线构建（不下载 IntelliJ SDK）
    pluginConfiguration {
        id = "com.cnsharp.yolo"
        name = productName
        version = project.version.toString()
        vendor {
            name = "CnSharp Studio"
            email = "support@cnsharp.com"
        }
        ideaVersion {
            sinceBuild = "261"
        }
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        // 本地 IDE 的 bundled 插件（acp / terminal）通过本地 Ivy 仓库解析（离线构建用）。
        // 本地用：取消下一行注释，并注释掉 dependencies 里的 intellijPlatform.idea(...)。
        // localPlatformArtifacts()
        // 代码插桩（instrumentCode）所需的 java-compiler-ant-tasks 来自 JetBrains Maven 仓库
        releases()
        intellijDependencies()
    }
}

dependencies {
    // 直接以本机 IDEA 安装目录作为 IntelliJ Platform 依赖（离线构建，不下载）。
    // 本地用：取消下一行注释，并注释掉 intellijPlatform.idea(...) 那行，同时恢复 repositories 里的 localPlatformArtifacts()。
    // intellijPlatform.local(file("/Applications/IntelliJ IDEA.app"))
    // CI / Release 工作流（无本机 IDEA）：下载 IntelliJ Platform 进行构建
    intellijPlatform.idea("2026.2")
    intellijPlatform.bundledPlugin("org.jetbrains.plugins.terminal")
}

kotlin {
    jvmToolchain(25)
}
