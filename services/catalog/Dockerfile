## BUILD ##

FROM eclipse-temurin:21-jdk AS builder

WORKDIR /workspace/
COPY gradlew settings.gradle.kts ./
# Nivel 2 — config de build
COPY gradle ./gradle
COPY build-logic ./build-logic
# Nivel 3 — build files de los módulos
COPY services/catalog/build.gradle.kts services/catalog/
COPY contracts/catalog/build.gradle.kts contracts/catalog/
# Nivel 4 — sources
COPY services/catalog/src services/catalog/src
COPY contracts/catalog/src contracts/catalog/src

RUN chmod +x ./gradlew
RUN ./gradlew :services:catalog:bootJar -x test --no-daemon

## EXECUTE ##

FROM eclipse-temurin:21-jre

WORKDIR /app/
COPY --from=builder /workspace/services/catalog/build/libs/*.jar app.jar
# Solo para documentación, expone puertos internos
EXPOSE 8080 9090  
USER 1001

ENTRYPOINT ["java", "-jar", "/app/app.jar"]