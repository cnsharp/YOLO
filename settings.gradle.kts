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
