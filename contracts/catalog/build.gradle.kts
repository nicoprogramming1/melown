// Módulo de contratos del catalog service.
// Compila los .proto a stubs Java; los consumidores los importan con
// `implementation(project(":contracts-catalog"))`.
//
// Nota: la ruta Gradle es `:contracts-catalog` (single-segment) para evitar
// colisión de simple-name con `:services:catalog`. Ver settings.gradle.kts.
//
// Layout esperado:
//   src/main/proto/melown/catalog/v1/catalog_service.proto
//
// Sin .proto el build es no-op (no genera nada). Ver ADR `0001` y `0002`.

import com.google.protobuf.gradle.id

plugins {
    id("melown.java-base")
    alias(libs.plugins.protobuf)
}

dependencies {
    // gRPC + protobuf en compile-time para los stubs generados.
    implementation(platform(libs.grpc.bom))
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.protobuf.java)

    // protoc-gen-grpc-java emite `@javax.annotation.Generated` (retention SOURCE)
    // que Java 21 ya no provee. compileOnly alcanza.
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
}

// Versiones desde el catálogo. Acceso vía la API explícita (VersionCatalogsExtension)
// porque los type-safe accessors de Kotlin DSL colisionan con la extensión homónima
// del plugin protobuf dentro de este script.
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val protobufVersion = catalog.findVersion("protobuf").get().requiredVersion
val grpcVersion = catalog.findVersion("grpc").get().requiredVersion

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc") { }
            }
        }
    }
}
