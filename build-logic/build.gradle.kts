plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(localGroovy())
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
    implementation(libs.plugins.kotlin.jvm.toDep())
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
}

tasks.test { useJUnitPlatform() }

fun Provider<PluginDependency>.toDep() = map {
    "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}"
}
