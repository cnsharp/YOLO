import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
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
        // JetBrains 官方仓库：IntelliJ Platform 与 bundled 插件（terminal 等）从这里解析
        defaultRepositories()
        // 本地离线构建用：从本机已装 IDEA 解析 bundled 插件（与 dependencies 里的 local(...) 配套）。
        // 本地用：取消下一行注释。
        // localPlatformArtifacts()
    }
}

dependencies {
    intellijPlatform {
        // CI / Release 工作流（无本机 IDEA）：下载 IntelliJ Platform 进行构建
        // 用 Ultimate 以匹配本机开发环境（TerminalAgentProvider 等内部 API 在此可解析）
        create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2")
        // 本地离线构建：取消下一行注释，并注释掉上面的 intellijIdea(...) 那行
        // local(file("/Applications/IntelliJ IDEA.app"))
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
}

kotlin {
    jvmToolchain(25)
}
