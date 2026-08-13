import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
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
            // Backward-compatible down to 2023.3: the panel uses only public JediTerm/PTY4J + IntelliJ
            // public APIs (no internal Terminal API). The few version-sensitive calls use their oldest
            // still-present forms (ReadAction.run, TerminalColor(int), OpenFileDescriptor 4-arg,
            // ContentFactory.getInstance(), FilenameIndex.getVirtualFilesByName(name, scope)).
            sinceBuild = "233"
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
            // Use Ultimate to match the local dev environment. Build against the oldest supported version
            // (2023.3) so an accidental use of a newer-only API fails here instead of at Marketplace review.
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2023.3")
        }
        // PSI-based type-name navigation (JavaPsiFacade / PsiShortNamesCache / NavigationUtil) needs the
        // Java plugin on the compile/runtime classpath.
        bundledPlugin("com.intellij.java")
    }
}

// Unit tests run against the SAME local IDEA platform used for compilation (`intellijPlatformClasspath`),
// which already resolves cleanly. We deliberately do NOT use the plugin's `testFramework(...)` helper:
// on IDEA 2026.2 its `ModuleDescriptorsValueSource` fails to parse the new `namespace` attribute on the
// platform's `<module>` descriptors (plugin 2.18.1 predates that schema), so the IntelliJ test classpath
// cannot be assembled offline. Reusing the working main classpath keeps `:test` fully offline. JUnit 4 is
// pulled once (cached) since release IDEA does not bundle it.
dependencies {
    testImplementation(files(configurations["intellijPlatformClasspath"]))
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    useJUnit()
}

kotlin {
    // Target Java 17 bytecode: IDEA 2023.3 (the minimum we support) runs on JBR 17, so the plugin must
    // not be compiled to a newer class-file version. We still compile *with* the local JBR 25, just
    // emitting Java 17-compatible classes, which also run fine on newer IDEA (2026.2's JBR 25).
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Keep the (empty) Java compile task consistent with the Kotlin target above — otherwise Gradle rejects
// the mixed JVM targets. `release` lets us emit Java 17 class files using the local JBR 25.
tasks.withType<JavaCompile> {
    options.release.set(17)
}

// The IntelliJ Platform Gradle Plugin's instrumentCode task resolves the Gradle daemon JDK and, on a
// foreign JDK, looks for a JBR-only "Packages" directory that no longer exists in IDEA 2026's bundled
// runtime — failing the build with ".../Contents/Home/Packages does not exist". Disabling it only skips
// nullability assertion instrumentation; the plugin still builds and runs correctly.
tasks.named("instrumentCode") {
    enabled = false
}
