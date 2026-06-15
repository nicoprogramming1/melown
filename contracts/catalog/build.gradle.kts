// Módulo de contratos del catalog service.
// Compila los .proto a stubs Java; expone esos stubs (gRPC + protobuf) como API
// para que cualquier consumidor (catalog server, orders client, etc.) los importe
// con `implementation(project(":contracts:catalog"))`.
//
// Layout esperado:
//   src/main/proto/melown/catalog/v1/catalog_service.proto
//
// Sin .proto el build es no-op (no genera nada). Ver ADR `0001` y `0002`.

plugins {
    id("melown.java-base")
    `java-library`
    alias(libs.plugins.protobuf)
}

dependencies {
    // gRPC + protobuf forman parte de la API pública del módulo: los consumidores
    // necesitan estas clases en compile-time. Por eso `api`, no `implementation`.
    api(platform(libs.grpc.bom))
    api(libs.grpc.stub)
    api(libs.grpc.protobuf)
    api(libs.protobuf.java)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
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
