// Pin the project name so the built artifact is always yolo-<version>.zip regardless of
// the checkout directory name (GitHub checks out into "YOLO", which would otherwise make
// Gradle produce YOLO-<version>.zip and break the release.yml upload glob).
rootProject.name = "yolo"

pluginManagement {
    repositories {
        // domestic mirror of the Gradle Plugin Portal (avoids plugins.gradle.org / gradle.org)
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        gradlePluginPortal()
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.11.0"
}
