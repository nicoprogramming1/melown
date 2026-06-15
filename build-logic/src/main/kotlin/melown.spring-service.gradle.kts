// Convention plugin para servicios Spring Boot.
// Extiende `melown.java-base` con el plugin Spring Boot + dependency-management
// + starters base + Lombok como compileOnly.
// Cada servicio decide su starter web (web, webflux, ninguno) en su propio build.

import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("melown.java-base")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

val libs = the<LibrariesForLibs>()

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
