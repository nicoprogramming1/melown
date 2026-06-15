import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    `kotlin-dsl`
}

// `the<LibrariesForLibs>()` expone el version catalog `libs` dentro del propio
// build script de build-logic. Sin esto, las versiones de los plugin markers
// quedarían duplicadas acá y en el catálogo.
val libs = the<LibrariesForLibs>()

dependencies {
    // Hace visible la clase generada `LibrariesForLibs` al classpath de
    // compilación de los precompiled scripts (`src/main/kotlin/*.gradle.kts`).
    // Sin esto, los convention plugins no pueden hacer `the<LibrariesForLibs>()`
    // para leer el version catalog. Es el workaround estándar en Gradle 7.6+.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))

    implementation(libs.plugin.spring.boot)
    implementation(libs.plugin.spring.dependency.management)
    implementation(libs.plugin.spotless)
}
