pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provisiona JDKs faltantes (Java toolchain). Sin esto, cada dev tiene que
    // tener JDK 21 instalado a mano en su OS — Foojay lo descarga si no existe.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "melown"

include(
    ":services:catalog",
    //":services:identity",
    //":services:api-gateway",
    ":contracts-catalog",
)
project(":contracts-catalog").projectDir = file("contracts/catalog")
