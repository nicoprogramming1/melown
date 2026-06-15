plugins {
    id("melown.spring-service")
}

dependencies {
    // Persistence
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    runtimeOnly(libs.postgresql)
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    // HTTP (solo para que Actuator exponga /actuator sobre HTTP — el dominio expone gRPC)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // gRPC server (integración con Spring Boot — anotaciones @GrpcService, etc.)
    implementation(libs.grpc.spring.boot.starter)

    // gRPC runtime — el BOM pinea todas las versiones gRPC coherentemente
    implementation(platform(libs.grpc.bom))
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.protobuf.java)
    runtimeOnly(libs.grpc.netty.shaded)

    // Stubs generados desde los .proto de contracts/catalog
    implementation(project(":contracts:catalog"))
}
