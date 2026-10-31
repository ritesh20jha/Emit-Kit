pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provisions JDK 21 for the Java toolchain.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "emitkit"

include(
    "common",
    "domain",
    "vcs",
    "spec-engine",
    "generators:core",
    "ai",
    "api-app",
    "worker-app",
    "architecture-tests",
)
