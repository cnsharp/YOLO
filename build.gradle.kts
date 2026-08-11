import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import java.util.Properties

plugins {
    id("org.jetbrains.intellij.platform")
    kotlin("jvm") version "2.3.20"
}

/**
 * The single source of truth for the product name is `plugin.name` in
 * messages/YoloBundle.properties. We read it here and feed it to pluginConfiguration.name
 * (which overrides plugin.xml's <name>), so UI text, the Marketplace name, and the Settings
 * page title all come from one place — renaming only requires editing a single line.
 */
val bundleFile = file("src/main/resources/messages/YoloBundle.properties")
val productName: String = Properties().apply {
    bundleFile.inputStream().use { load(it) }
}.getProperty("plugin.name")
    ?: error("plugin.name missing from ${bundleFile.path}")

/**
 * The single source of truth for the version is `pluginVersion` in gradle.properties
 * (CI can override it with -PpluginVersion=x). It must be assigned to project.version:
 * Gradle's archive tasks take archiveVersion from it by default. Previously only
 * pluginConfiguration.version was set, leaving project.version at "unspecified", so the
 * distributed artifact degenerated into a version-less yolo.zip.
 */
version = providers.gradleProperty("pluginVersion").get()

intellijPlatform {
    // Use the locally installed IDEA as the SDK for offline builds (no IntelliJ SDK download).
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
        // JetBrains' official repository: where the IntelliJ Platform and bundled plugins
        // (terminal, etc.) are resolved from.
        defaultRepositories()
        // For local offline builds: resolve bundled plugins from the locally installed IDEA
        // (pairs with local(...) in dependencies below).
        // For local use: uncomment the next line.
        // localPlatformArtifacts()
    }
}

dependencies {
    intellijPlatform {
        // When a local IDEA is present, use `local` (offline, no download); if CI/Release
        // doesn't provide that property, fall back to `create` (downloads).
        // Resolution order: -PlocalIdeaPath (CLI) > local.properties (gitignored) > download.
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val localIdeaPath = providers.gradleProperty("localIdeaPath").orNull
            ?: localProps.getProperty("localIdeaPath")
        if (localIdeaPath != null) {
            local(file(localIdeaPath))
        } else {
            // Use Ultimate to match the local dev environment.
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2")
        }
    }
}

kotlin {
    jvmToolchain(25)
}
