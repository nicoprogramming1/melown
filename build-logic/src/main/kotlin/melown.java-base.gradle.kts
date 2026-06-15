// Convention plugin base para cualquier módulo Java del monorepo.
// Aplica: toolchain 21, JUnit 5, ArchUnit (dependencia), Spotless.
// Reglas ArchUnit concretas se escriben en cada módulo (o se factoriza a
// `libs/arch-rules/` cuando haya 2+ servicios con tests).

import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    java
    id("com.diffplug.spotless")
}

group = "com.melown"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val libs = the<LibrariesForLibs>()

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        ktlint()
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn("spotlessCheck")
}
