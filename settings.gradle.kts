pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Resolves and downloads the JDK 25 toolchain on demand from the Foojay Disco API.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "doris-ducklake"

// Single-project build: the Doris DuckLake connector lives at the repo root. The shared
// catalog + corpus-replay come in as published dev.brikk.ducklake artifacts (Maven Central /
// mavenLocal), not as included subprojects.
