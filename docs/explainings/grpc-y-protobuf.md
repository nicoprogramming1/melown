# gRPC y Protobuf — explicación progresiva

Documento de aprendizaje. Tres niveles de profundidad: alto nivel (para tener intuición), medio (para entender qué pasa) y bajo (para no tenerle miedo al toolchain). Cierra con la implementación concreta en Melown, los comandos críticos y las trampas comunes.

---

## TL;DR (si solo leés esto)

- **gRPC** = framework de RPC binario sobre HTTP/2 con **generación de código desde un IDL**.
- **Protobuf** = lenguaje para definir mensajes (`.proto`) + protocolo binario para serializarlos eficiente.
- El `.proto` es **el contrato**: define qué mensajes existen y qué RPCs los usan. Una vez en prod, **inmutable** (no se cambian tag numbers, no se borran fields sin `reserved`).
- En Melown: los `.proto` viven en `contracts/<servicio>/`, se compilan a Java vía Gradle, los servicios consumen los stubs generados.
- Tres comandos para todo el día: `./gradlew :contracts-catalog:generateProto`, `./gradlew :services:catalog:build`, `grpcurl localhost:9090 list`.

---

## Nivel 1 — Por qué existe esto

### El problema

Dos servicios tienen que hablarse. Si vas con REST/JSON:

- Cada uno mantiene su propio contrato (Swagger, PDF, "el código es la doc"). Cuando el contrato cambia, descubrís la incompatibilidad **en runtime**, no en compile-time.
- JSON parsea string → object por reflexión cada vez. CPU y latencia.
- HTTP/1.1: una request por conexión TCP (o pool). Patrones fan-out (un request del usuario que pega a 3 servicios paralelos) sufren.
- Sin streaming nativo: si querés "el server me empuja eventos", reinventás SSE/WebSocket.
- Deadlines y cancelación: cada uno los implementa diferente.

gRPC + Protobuf vienen a resolver TODO eso en un solo paquete.

### gRPC en una oración

> Llamás métodos en otro servicio como si fueran locales — el framework se encarga del wire, la serialización, deadlines, retries, tracing.

### Protobuf en una oración

> Definís los datos UNA VEZ en un archivo `.proto`; obtenés serialización binaria + clases tipadas en cualquier lenguaje.

### Comparación rápida

| Aspecto | REST + JSON | gRPC + Protobuf |
|---|---|---|
| Contrato | OpenAPI/Swagger (opcional) | `.proto` (obligatorio) |
| Tipado | Runtime, vía validación | Compile-time, vía codegen |
| Wire | JSON (texto, ~10x más grande) | Binario denso |
| Transporte | HTTP/1.1 o /2 | HTTP/2 obligatorio (multiplexing) |
| Streaming | SSE/WebSocket ad-hoc | Nativo (4 tipos de RPC) |
| Deadlines | Headers convencionales | Built-in via `Context` |
| Status codes | HTTP (genéricos) | `io.grpc.Status` (específicos del RPC) |
| Debug ad-hoc | `curl`, Postman | `grpcurl`, BloomRPC |
| Browser-friendly | Sí | Solo via gRPC-Web (proxy) |

**Cuándo gRPC gana:** servicio ↔ servicio interno. Latencia importa. Equipo necesita disciplina de contratos.
**Cuándo REST gana:** frontera pública (browser, mobile, terceros). Diversidad de consumers que no van a generar stubs.

ADR `0002` decidió **gRPC para todo lo interno, REST solo en el `api-gateway`**.

---

## Nivel 2 — Cómo funciona (conceptos)

### El `.proto` como contrato

Un archivo de texto declarativo:

```protobuf
syntax = "proto3";
package melown.catalog.v1;

service CatalogService {
  rpc CreateProduct(CreateProductRequest) returns (CreateProductResponse);
}

message CreateProductRequest {
  string idempotency_key = 1;
  string vendor_id = 2;
  string title = 3;
}

message CreateProductResponse {
  string product_id = 1;
}
```

Tres bloques:

- **`service`**: los RPCs que el server expone.
- **`message`**: los tipos de datos (request, response, value objects).
- **`package`**: namespace (= versión del contrato: `v1` ahora, `v2` cuando rompa compat).

Las llaves `= 1`, `= 2` son **tag numbers** — identifican el field en el wire binario. **Nunca se reutilizan.** Si borrás un field, marcás su tag `reserved`.

### El codegen — el ahorro real

Un compilador (`protoc`) lee el `.proto` y genera código en cualquier lenguaje (Java, Go, Python, Rust, etc.). Para Java + gRPC genera:

- **Mensajes** (`CreateProductRequest`, `CreateProductResponse`) — clases inmutables con builders, equals, hashCode, toString, serialización a/desde bytes.
- **Service stub base** (`CatalogServiceGrpc.CatalogServiceImplBase`) — clase abstracta que el **server** extiende para implementar la lógica.
- **Client stubs** (`CatalogServiceGrpc.CatalogServiceBlockingStub`, `.CatalogServiceStub`, `.CatalogServiceFutureStub`) — clases que el **cliente** instancia para hacer las llamadas.

Esto es el **ahorro neto** de gRPC: vos no escribís ni un byte de "parseo de request", "validación de tipos", "serialización". Lo que sí escribís es la **lógica del RPC** (`createProduct` adentro de tu `CatalogGrpcService`).

### HTTP/2 como transporte

gRPC corre **siempre** sobre HTTP/2. Esto da:

- **Multiplexing**: varias llamadas concurrentes comparten UNA conexión TCP. En HTTP/1.1 cada request necesita una conexión (o serializa la cola).
- **Streaming bidireccional nativo**: client → server, server → client, ambos a la vez. Sin reinventarlo.
- **Header compression** (HPACK): metadata viaja barata.
- **Binary framing**: el protocol del cable es binario (no texto como HTTP/1).

**Costo**: el load balancing L4 tradicional (round-robin a nivel TCP) **no funciona** para gRPC, porque todas las llamadas van por la misma conexión TCP larga. Necesitás L7 (client-side LB, service mesh tipo Linkerd, o headless services en K8s). Por eso ADR `0002` menciona esto como costo.

### Los 4 tipos de RPC

| Tipo | Request | Response | Caso típico |
|---|---|---|---|
| **Unary** | 1 mensaje | 1 mensaje | `CreateProduct`, `GetProduct`. **Lo que vas a hacer 95% del tiempo.** |
| **Server-streaming** | 1 mensaje | N mensajes | `SearchProducts` con paginación push, suscripciones a cambios |
| **Client-streaming** | N mensajes | 1 mensaje | Bulk upload, ingest de telemetría |
| **Bidi-streaming** | N mensajes | N mensajes | Chat, juegos, dashboards live |

Para Melown — solo **unary** en el walking skeleton. Streaming aparece quizás en Phase 2/3.

### Status codes y errores

REST: `404 Not Found`, `400 Bad Request`. Pocos, genéricos.
gRPC: `Status.NOT_FOUND`, `Status.INVALID_ARGUMENT`, `Status.ABORTED`, `Status.FAILED_PRECONDITION`, `Status.RESOURCE_EXHAUSTED`, `Status.DEADLINE_EXCEEDED`, `Status.UNAVAILABLE`, etc. Más específicos al modelo RPC.

ADR `0005` define el mapeo `DomainException → Status` para Melown:

| Exception del dominio | gRPC Status |
|---|---|
| `ValidationException` | `INVALID_ARGUMENT` |
| `NotFoundException` | `NOT_FOUND` |
| `ConflictException` | `ABORTED` (retryable — optimistic lock) |
| `BusinessRuleException` | `FAILED_PRECONDITION` (no retryable) |

Lo hacés en un **interceptor** (`DomainExceptionInterceptor`), no en cada handler.

---

## Nivel 3 — Lo que pasa por detrás (cuando lo necesites)

### El pipeline de compilación, paso a paso

```
catalog_service.proto
        ↓ (protoc + protoc-gen-grpc-java)
*.java generado (en build/generated/source/proto/main/{java,grpc}/)
        ↓ (javac)
*.class compilado (en build/classes/java/main/)
        ↓ (jar)
catalog-contracts.jar
        ↓ (Gradle resolve)
classpath de services/catalog
        ↓ (tu código)
CatalogGrpcService extends CatalogServiceGrpc.CatalogServiceImplBase
        ↓ (Spring Boot arranca, @GrpcService registra)
Server gRPC en :9090 escuchando
```

### Quién hace qué

- **`protoc`** (binario nativo): compila los **mensajes**. Genera `CreateProductRequest.java`, etc. Es el compilador base de protobuf, no sabe nada de gRPC.
- **`protoc-gen-grpc-java`** (plugin de `protoc`): genera los **service stubs** (`CatalogServiceGrpc.java` con `ImplBase`, `BlockingStub`, etc.). Sin este plugin, tendrías los messages pero ningún stub gRPC.
- **`com.google.protobuf` Gradle plugin**: orquesta a los dos anteriores como tareas Gradle, configura source sets, agrega los outputs al compileClasspath.

Por eso el `build.gradle.kts` de `contracts/catalog` declara las dos versiones — una para `protoc` y otra para el plugin `grpc`:

```kotlin
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion" }
    }
    ...
}
```

### El wire format (binario)

Cuando un cliente manda un `CreateProductRequest` por la red, el wire format es **denso**. Ejemplo simplificado:

```
field 1 (idempotency_key, string):   0a 24 "550e8400-e29b-41d4-a716-446655440000"
field 2 (vendor_id, string):          12 24 "..."
field 3 (title, string):              1a 0a "Auriculares"
```

Cada field se codifica como `<tag>` (tag-number + tipo) + `<longitud para string/bytes>` + `<bytes del valor>`. Los integers van en **varint** (encoding variable: números chicos ocupan 1 byte, grandes hasta 10).

**Comparación con JSON** del mismo payload:
- JSON: `{"idempotencyKey":"550e...","vendorId":"...","title":"Auriculares"}` ~ 130 bytes.
- Protobuf: ~ 80 bytes.

A volúmenes altos (millones de mensajes/seg en una pipeline interna), la diferencia se acumula en CPU + bandwidth.

**El secreto** de la compacidad: el wire **no lleva los nombres de los fields**, solo el tag-number. Por eso **no se reutilizan**: si en `v1` el field `3` era `title` y en `v2` lo cambiás a `description`, los clientes viejos van a leer un `description` como si fuera `title`. Bug silencioso devastador.

### HTTP/2 framing (resumen)

Una request gRPC en HTTP/2:

```
HEADERS frame:
  :method = POST
  :path = /melown.catalog.v1.CatalogService/CreateProduct
  content-type = application/grpc
  te = trailers
  grpc-timeout = 5S
  authorization = Bearer ...
  ...

DATA frame (uno o varios):
  [byte de compresión: 00]
  [length-prefix: 0000004A]   ← longitud del mensaje protobuf
  [bytes del CreateProductRequest serializado]

TRAILERS frame:
  grpc-status = 0   (OK)
  grpc-message = OK
```

El **path** es `/<package>.<service>/<method>` — `melown.catalog.v1.CatalogService/CreateProduct`. Eso es lo que `grpcurl describe` te muestra después.

El **status** viaja en el trailer, no en el header (porque el server puede no saberlo hasta terminar el processing). Por eso gRPC requiere "trailers" del HTTP/2.

---

## Anatomía de las clases que genera protoc

Para nuestro `.proto` con `service CatalogService { rpc CreateProduct(...); }` + dos messages, `protoc` + `protoc-gen-grpc-java` generan ~10 archivos. Acá va cada uno con su rol.

### Las clases de mensajes

Una por cada `message` del `.proto`. Para `CreateProductRequest` se generan **tres** archivos relacionados:

| Clase | Qué es | Cuándo la usás |
|---|---|---|
| `CreateProductRequest` | Mensaje **inmutable**. Tiene `getXxx()`, `toByteArray()`, `parseFrom(bytes)`, `equals`, `hashCode`, `toString`. | Lo recibís en el handler. Lo retornás en builders. |
| `CreateProductRequest.Builder` | Builder **mutable** para construirlo paso a paso. `Request.newBuilder().setTitle(...).build()`. | Cuando armás un request en el cliente o en un mapper. |
| `CreateProductRequestOrBuilder` | Interface que **ambos** implementan. Útil cuando una API acepta "construido o en construcción". | Raramente — en mappers genéricos a veces. |

Mismo trío para `CreateProductResponse`. Y para cada `message` nuevo que agregues.

**Patrón de construcción** (igual en cualquier mensaje proto):

```java
CreateProductRequest req = CreateProductRequest.newBuilder()
    .setIdempotencyKey(UUID.randomUUID().toString())
    .setVendorId(vendorId.toString())
    .setTitle("Auriculares")
    .build();
```

**Notas sutiles:**
- Los `set*` en el Builder son fluent (devuelven `Builder this`).
- `build()` produce el mensaje inmutable.
- Si querés modificar un mensaje existente, `request.toBuilder().setTitle("...").build()` — clona.
- `parseFrom(byte[])` recupera el mensaje desde bytes del wire.

### El service container (`CatalogServiceGrpc`)

**No es** "el service" — es una clase **container** con varias clases anidadas. Cada una sirve para un rol distinto:

```
CatalogServiceGrpc                                  ← container, no se instancia
├── CatalogServiceImplBase (abstract class)         ← SERVER extiende ésta
├── CatalogServiceBlockingStub                      ← cliente bloqueante (sync)
├── CatalogServiceStub                              ← cliente async / streaming
├── CatalogServiceFutureStub                        ← cliente con ListenableFuture
├── AsyncService (interface, versiones recientes)   ← alternativa a ImplBase
└── MethodHandlers, *DescriptorSupplier             ← internos, ignoralos
```

#### `CatalogServiceImplBase` — el server

Clase abstracta. **Tu `CatalogGrpcService` la extiende.** Para cada `rpc` del `.proto`, tiene un método con implementación default que responde `UNIMPLEMENTED`:

```java
// Lo que GENERA protoc (NO lo escribís vos):
public abstract static class CatalogServiceImplBase implements BindableService {
    public void createProduct(
            CreateProductRequest request,
            StreamObserver<CreateProductResponse> responseObserver) {
        asyncUnimplementedUnaryCall(getCreateProductMethod(), responseObserver);
    }
}
```

Vos sobreescribís solo los métodos que querés implementar:

```java
@GrpcService
public class CatalogGrpcService extends CatalogServiceGrpc.CatalogServiceImplBase {
    @Override
    public void createProduct(CreateProductRequest req, StreamObserver<CreateProductResponse> obs) {
        // tu lógica acá
    }
    // si no override getProduct, el default responde UNIMPLEMENTED al cliente que lo llame
}
```

#### Los tres tipos de cliente stub

Para HACER llamadas a un server gRPC, instanciás uno de los tres stubs cliente. La diferencia es **el estilo de async**:

**`BlockingStub`** — bloquea el thread hasta la respuesta.

```java
// Cuándo: tests, scripts, llamadas donde "no me importa async, quiero la respuesta y listo"
CatalogServiceBlockingStub stub = CatalogServiceGrpc.newBlockingStub(channel);
CreateProductResponse resp = stub.createProduct(req);   // bloquea el thread acá
```

Limitación: **no soporta client-streaming ni bidi-streaming**. Server-streaming sí (te devuelve un `Iterator<>`).

**`Stub`** (sin sufijo) — async puro con callbacks.

```java
// Cuándo: necesitás streaming, O no querés bloquear el thread del caller
CatalogServiceStub stub = CatalogServiceGrpc.newStub(channel);
stub.createProduct(req, new StreamObserver<CreateProductResponse>() {
    @Override public void onNext(CreateProductResponse resp) { /* ... */ }
    @Override public void onError(Throwable t) { /* ... */ }
    @Override public void onCompleted() { /* ... */ }
});
// retorna inmediato, los callbacks fire después en el thread pool de gRPC
```

Es **el único stub que soporta los 4 tipos de RPC**, incluyendo streaming bidi.

**`FutureStub`** — async con `ListenableFuture` (de Guava).

```java
// Cuándo: querés componer con CompletableFuture, hacer paralelismo, o usar Reactor/RxJava
CatalogServiceFutureStub stub = CatalogServiceGrpc.newFutureStub(channel);
ListenableFuture<CreateProductResponse> future = stub.createProduct(req);
// Convertís a CompletableFuture o esperás con future.get()
```

Limitación: solo **unary**. Sin streaming.

#### Cuál usar — tabla rápida

| Caso | Stub |
|---|---|
| Test síncrono de un endpoint unary | `BlockingStub` |
| Script de migración / herramienta CLI | `BlockingStub` |
| Servicio que llama a otro servicio adentro de un handler | `Stub` o `FutureStub` |
| Componer N llamadas paralelas | `FutureStub` (wrappeado a `CompletableFuture`) |
| Suscribirse a un server-stream | `Stub` |
| Cualquier streaming client / bidi | `Stub` |

Para Melown ahora todos los clientes son `BlockingStub` (tests) o `Stub` (cuando catalog ↔ inventory en Phase 2).

### El outer descriptor (`CatalogProto`)

```java
// CatalogProto.java
public final class CatalogProto {
    public static Descriptors.FileDescriptor getDescriptor() { ... }
    // ...
}
```

Contiene el **FileDescriptor** del `.proto` entero — metadata runtime de los mensajes y services. Lo usás cuando:

- Hacés **reflection** (ej. `ProtoReflectionService` que habilita `grpcurl list`).
- Construís errores estructurados con `google.rpc.error_details` (Phase 2+).
- Necesitás procesar mensajes de forma dinámica sin saber el tipo en compile-time.

En código normal de un handler, **no lo importás**. Existe pero no lo tocás.

### Cuántos `.class` produce el compileJava

Listado real de Melown después de `./gradlew :contracts-catalog:build`:

```
CatalogProto.class
CatalogServiceGrpc.class
CatalogServiceGrpc$1.class                                ← inner anónimas, internas
CatalogServiceGrpc$2.class
CatalogServiceGrpc$3.class
CatalogServiceGrpc$AsyncService.class
CatalogServiceGrpc$CatalogServiceBaseDescriptorSupplier.class
CatalogServiceGrpc$CatalogServiceBlockingStub.class
CatalogServiceGrpc$CatalogServiceFileDescriptorSupplier.class
CatalogServiceGrpc$CatalogServiceFutureStub.class
CatalogServiceGrpc$CatalogServiceImplBase.class           ← el que extiende el server
CatalogServiceGrpc$CatalogServiceMethodDescriptorSupplier.class
CatalogServiceGrpc$CatalogServiceStub.class
CatalogServiceGrpc$MethodHandlers.class
CreateProductRequest.class
CreateProductRequest$1.class
CreateProductRequest$Builder.class
CreateProductRequestOrBuilder.class
CreateProductResponse.class
CreateProductResponse$1.class
CreateProductResponse$Builder.class
CreateProductResponseOrBuilder.class
```

22 archivos para **1 service con 1 RPC y 2 messages**. Crece linealmente con cada `rpc` y `message` que agregues. La buena noticia: no escribís ninguno.

---

## Nivel 4 — Cómo está implementado en Melown

### Por qué `contracts/` está separado del servicio

Si los `.proto` vivieran adentro de `services/catalog/`, otros servicios que necesiten **el cliente de catalog** (ej. `orders` haciendo `GetProduct`) tendrían que depender del módulo `services/catalog` ENTERO — incluyendo su lógica interna. Eso viola la **regla #1 de `CLAUDE.md`** ("ningún servicio lee la implementación de otro").

Separando los `.proto` en `contracts/catalog/`:

- El cliente `orders` hace `implementation(project(":contracts-catalog"))` — depende SOLO del contrato.
- La implementación de catalog queda invisible para el resto.
- Cambiar la lógica interna de catalog NO obliga a recompilar otros servicios.

ADR `0004` lo decidió.

### Por qué el path Gradle es `:contracts-catalog` (single-segment)

**Bug del toolchain**: Gradle 9.5 + Spring Boot 4 generan un ciclo de tareas cuando dos subprojects comparten **simple-name**. Si fuera `:contracts:catalog`, ambos tendrían `project.name = "catalog"` (igual que `:services:catalog`). Single-segment path le da nombre único: `contracts-catalog`.

El directorio físico sigue siendo `contracts/catalog/` — se mapea con `project(":contracts-catalog").projectDir = file("contracts/catalog")` en `settings.gradle.kts`.

### El `build.gradle.kts` de `contracts/catalog` línea por línea

```kotlin
import com.google.protobuf.gradle.id

plugins {
    id("melown.java-base")           // toolchain 21, JUnit, Spotless
    alias(libs.plugins.protobuf)     // com.google.protobuf, vía version catalog
}

dependencies {
    implementation(platform(libs.grpc.bom))    // pin de versiones gRPC coherente
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.protobuf.java)

    // protoc-gen-grpc-java emite @javax.annotation.Generated (retention SOURCE)
    // que Java 21 ya no provee. compileOnly alcanza.
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val protobufVersion = catalog.findVersion("protobuf").get().requiredVersion
val grpcVersion = catalog.findVersion("grpc").get().requiredVersion

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
    plugins { id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion" } }
    generateProtoTasks {
        all().forEach { task -> task.plugins { id("grpc") { } } }
    }
}
```

Comentarios:

- **`import com.google.protobuf.gradle.id`** — la DSL `id("grpc") { ... }` del plugin protobuf necesita este import para que Kotlin DSL resuelva el `id`.
- **`alias(libs.plugins.protobuf)`** — la versión del plugin sale del version catalog (`libs.versions.toml`), no hardcoded.
- **`VersionCatalogsExtension`** — acceso a las versiones desde el script. Lo hacemos así (en vez de `libs.versions.protobuf.get()`) porque Kotlin DSL colisiona con la extensión `protobuf` del propio plugin.
- **`compileOnly` de javax.annotation** — workaround específico de gRPC + Java 21. Sin esto, los stubs generados no compilan.

### El `.proto` del catalog

Path: `contracts/catalog/src/main/proto/melown/catalog/v1/catalog_service.proto`. La ruta importa porque el `package melown.catalog.v1;` adentro del archivo tiene que matchear (convención de protoc).

Bloques obligatorios:

```protobuf
syntax = "proto3";                              // versión del lenguaje
package melown.catalog.v1;                      // namespace en el wire (lleva el v1)
option java_multiple_files = true;              // una clase Java por mensaje (no un mega-file)
option java_package = "com.melown.catalog.v1";  // package Java de las clases generadas
```

**`v1` en el package desde el día 1**. Cuando rompa compatibility, `v2` paralelo (no se toca `v1`).

### Cómo consume el servicio

`services/catalog/build.gradle.kts`:

```kotlin
implementation(project(":contracts-catalog"))
```

Eso le da al servicio acceso a las clases generadas. En código:

```java
import com.melown.catalog.v1.CatalogServiceGrpc;
import com.melown.catalog.v1.CreateProductRequest;
import com.melown.catalog.v1.CreateProductResponse;

@GrpcService  // de net.devh — registra el bean Y el service en el server gRPC
public class CatalogGrpcService extends CatalogServiceGrpc.CatalogServiceImplBase {

    @Override
    public void createProduct(CreateProductRequest req, StreamObserver<CreateProductResponse> obs) {
        // 1. validación sintáctica + mapper.toCommand(req)
        // 2. invocar use case
        // 3. mapper.toProto(resultado)
        // 4. obs.onNext(response); obs.onCompleted();
    }
}
```

Trampas a recordar:

- **NO** poner `@Service` además del `@GrpcService` — doble registro.
- **NO** poner `try/catch` de `DomainException` — sube libre al interceptor.
- **NO** llamar `onNext` después de `onError` — el observer es one-shot.

---

## Ejemplo: implementación del `DomainExceptionInterceptor`

Este es el "global exception handler" del lado gRPC — el equivalente al `@RestControllerAdvice` de REST. Tu adapter (`CatalogGrpcService`) deja que las excepciones del dominio **suban libres** sin try/catch; el interceptor las captura en el límite y las traduce a `Status` de gRPC.

### Pre-requisito crítico

Tu `DomainException` **tiene que ser `sealed`** y declarar `permits`. Sin esto, el switch del interceptor no es exhaustivo y perdés el compile-time check (el premio mayor del patrón). Esqueleto en el dominio:

```java
// services/catalog/src/main/java/com/melown/catalog/domain/exception/DomainException.java
public abstract sealed class DomainException extends RuntimeException
    permits ValidationException, NotFoundException, ConflictException, BusinessRuleException {

    protected DomainException(String message) { super(message); }
    protected DomainException(String message, Throwable cause) { super(message, cause); }
}
```

Cada subclase es `final` (o también `sealed` si querés ramas, pero usualmente `final`):

```java
public final class ValidationException extends DomainException {
    public ValidationException(String message) { super(message); }
}
// ídem NotFoundException, ConflictException, BusinessRuleException
```

### El interceptor — código completo

Path: `services/catalog/src/main/java/com/melown/catalog/infrastructure/adapter/in/grpc/interceptors/DomainExceptionInterceptor.java`.

```java
package com.melown.catalog.infrastructure.adapter.in.grpc.interceptors;

import com.melown.catalog.domain.exception.*;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captura DomainException de cualquier handler gRPC y mapea a Status según ADR 0005.
 *
 * Pattern: Interceptor (GoF) + Chain of Responsibility.
 * @GrpcGlobalServerInterceptor aplica este interceptor a TODOS los @GrpcService del módulo.
 */
@GrpcGlobalServerInterceptor
public class DomainExceptionInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DomainExceptionInterceptor.class);

    @Override
    public <Req, Resp> ServerCall.Listener<Req> interceptCall(
            ServerCall<Req, Resp> call,
            Metadata headers,
            ServerCallHandler<Req, Resp> next) {

        ServerCall.Listener<Req> delegate = next.startCall(call, headers);

        // Wrappeamos el listener para interceptar excepciones del handler downstream.
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onHalfClose() {
                try {
                    super.onHalfClose();
                } catch (DomainException e) {
                    handleDomain(e, call);
                } catch (RuntimeException e) {
                    handleUnknown(e, call);
                }
            }
        };
    }

    private <Resp> void handleDomain(DomainException e, ServerCall<?, Resp> call) {
        // Pattern matching exhaustivo sobre el sealed.
        // SIN `default` — el compilador exige cubrir todas las variantes.
        // Si mañana agregás una RateLimitException al sealed, este switch DEJA DE COMPILAR
        // hasta que la mapees acá. Ese es el premio del patrón.
        Status status = switch (e) {
            case ValidationException v   -> Status.INVALID_ARGUMENT.withDescription(safe(v));
            case NotFoundException n     -> Status.NOT_FOUND.withDescription(safe(n));
            case ConflictException c     -> Status.ABORTED.withDescription(safe(c));
            case BusinessRuleException b -> Status.FAILED_PRECONDITION.withDescription(safe(b));
        };

        log.warn("domain error: type={}, message={}", e.getClass().getSimpleName(), e.getMessage());
        call.close(status, new Metadata());
    }

    private <Resp> void handleUnknown(RuntimeException e, ServerCall<?, Resp> call) {
        // Excepciones no clasificadas = bug del server. Status genérico al cliente,
        // stack trace COMPLETO al log. Nunca filtrar internals al wire.
        log.error("unhandled exception in gRPC handler", e);
        call.close(
            Status.INTERNAL.withDescription("internal server error"),
            new Metadata()
        );
    }

    private String safe(DomainException e) {
        // El mensaje del dominio se asume seguro de mostrar (no contiene PII, no contiene SQL,
        // no contiene rutas internas). Si esa premisa cambia, este método consulta una whitelist
        // o usa un message catalog con código de error.
        return e.getMessage();
    }
}
```

### Anatomía del interceptor — qué hace cada bloque

**1. `interceptCall(call, headers, next)`** — Se invoca **una vez por cada RPC entrante**. Gradle/gRPC te pasa:
- `call`: el ServerCall (lo usás para cerrar con un Status).
- `headers`: metadata del request (auth, trace IDs, idempotency keys, etc.).
- `next`: el siguiente eslabón de la cadena (puede ser otro interceptor o el handler final).

Vos llamás `next.startCall(call, headers)` y obtenés un `Listener` — los eventos que el cliente envía van llegando a ese listener (`onMessage`, `onHalfClose`, `onCancel`, `onComplete`, `onReady`). **Lo envolvés** para interceptar el momento en que sale una excepción.

**2. `onHalfClose()`** — Es el momento clave. Sucede **cuando el cliente terminó de mandar el request** y el server arranca a procesar (= ejecuta tu handler `createProduct(...)`). Si el handler tira excepción, la atrapás acá.

**Trampa sutil:** si la operación es **async** (el handler dispara trabajo en otro thread y retorna), la excepción ocurre **después** de `onHalfClose()` y este interceptor no la ve. Para el walking skeleton todo es sync — sin problema. Para async, el handler tiene que capturar la excepción ÉL y pasarla por `responseObserver.onError(...)`, o vivir con que el cliente vea `INTERNAL`.

**3. El `switch` exhaustivo** — sin `default`. Java 21 exige cubrir todas las variantes de un sealed. Beneficio:

```java
// Hoy: 4 casos
case ValidationException, NotFoundException, ConflictException, BusinessRuleException -> ...
// Mañana: agregás RateLimitException al sealed.
// ESTE archivo deja de compilar hasta que agregues el case.
// El compilador te obliga a tomar una decisión consciente. Cero excepciones perdidas.
```

**4. `handleUnknown` para Throwable no-DomainException** — bug del server, `Status.INTERNAL`, log con stack. **Nunca** `Status.INTERNAL.withDescription(e.getMessage())` con el mensaje técnico al cliente — filtrarías internals (paths, queries SQL, etc.).

### Registración — cómo se conecta al server

Con `@GrpcGlobalServerInterceptor` de `net.devh:grpc-spring-boot-starter`, el interceptor se aplica **automáticamente** a todos los `@GrpcService` del módulo. No tenés que registrarlo a mano, ni añadirlo a un `ServerBuilder`, ni hacer wiring en `infrastructure/config/`.

Alternativas que existen pero no usamos para esto:
- **`@GrpcAdvice`** + `@GrpcExceptionHandler` por method — más fino (por service, por exception type). Útil si querés diferentes mappeos en diferentes services. Para una jerarquía global de excepciones del dominio, **global interceptor es lo correcto**.
- **Manual via `ServerInterceptors.intercept(service, interceptor)`** — más control pero más boilerplate. No vale la pena acá.

### Orden de ejecución (si hay varios interceptors)

Spring + net.devh ejecutan los interceptors en orden de `@Order` (o el orden en que se registran si no hay anotación). Patrón típico de orden:

```
Request entra
    ↓
[AuthInterceptor]         ← valida JWT, setea SecurityContext
    ↓
[TracingInterceptor]      ← arranca span OTel
    ↓
[LoggingInterceptor]      ← MDC, logs structured
    ↓
[DomainExceptionInterceptor]   ← captura DomainException
    ↓
[CatalogGrpcService.createProduct(...)]   ← tu handler
```

Si una excepción ocurre en el handler, sube por la cadena. El `DomainExceptionInterceptor` la atrapa. Si fuera más arriba (ej. auth falla), nunca llega al handler — el interceptor de auth la maneja antes.

Para Melown ahora solo tenés el `DomainExceptionInterceptor`. Los otros vienen en Phase 1+ (auth ADR `0008`, tracing/logging ADR `0006`).

### Lo que el interceptor **no** debería hacer

- **Lógica de negocio** — pertenece al use case.
- **Decidir niveles de log per-exception** — eso lo hace el logger config (Logback policy).
- **Mapear request/response a otros DTOs** — eso es del mapper.
- **Setear headers de response específicos del dominio** — eso lo hace el handler antes de tirar.
- **Hacer side-effects** (escribir a DB, mandar emails, publicar eventos) — pertenecen al use case que tira la exception.

### Variantes que vas a querer eventualmente

**1. Logging estructurado con MDC (ADR `0006`):**

```java
log.warn("domain error type={} message={} traceId={} spanId={}",
    e.getClass().getSimpleName(),
    e.getMessage(),
    MDC.get("traceId"),
    MDC.get("spanId"));
```

`grpc-spring-boot-starter` + Micrometer Tracing setean el MDC solo si están en el classpath. Verificalo con un log de prueba que `traceId` aparezca no-null.

**2. Métricas por tipo de exception:**

```java
Counter.builder("grpc_domain_errors_total")
    .tag("type", e.getClass().getSimpleName())
    .tag("rpc", call.getMethodDescriptor().getFullMethodName())
    .register(meterRegistry)
    .increment();
```

Útil para alertas Prometheus (`rate(grpc_domain_errors_total{type="validation"}[5m]) > N`).

**3. Errores estructurados con `google.rpc.Status` y `error_details`:**

En vez de `Status.INVALID_ARGUMENT.withDescription("title is blank")`, devolver un `BadRequest` proto con violaciones por field:

```java
BadRequest violations = BadRequest.newBuilder()
    .addFieldViolations(FieldViolation.newBuilder()
        .setField("title")
        .setDescription("must not be blank, max 200 chars"))
    .build();
StatusProto statusProto = StatusProto.newBuilder()
    .setCode(Status.INVALID_ARGUMENT.getCode().value())
    .setMessage("validation failed")
    .addDetails(Any.pack(violations))
    .build();
call.close(StatusProto.toStatus(statusProto), trailerWithStatus(statusProto));
```

Más rico para clientes que muestran UX detallada. Diferí para Phase 2 — agrega complejidad.

### Testeando el interceptor

Para tests unitarios sin levantar red, gRPC tiene **InProcessServer**:

```java
// Esqueleto del test (vos lo completás)
@Test void traducingValidationToInvalidArgument() {
    String serverName = InProcessServerBuilder.generateName();

    // Service stub que tira la exception que querés probar
    var service = new CatalogServiceGrpc.CatalogServiceImplBase() {
        @Override public void createProduct(CreateProductRequest req, StreamObserver<CreateProductResponse> obs) {
            throw new ValidationException("title is blank");
        }
    };

    Server server = InProcessServerBuilder.forName(serverName)
        .addService(ServerInterceptors.intercept(service, new DomainExceptionInterceptor()))
        .build().start();

    ManagedChannel channel = InProcessChannelBuilder.forName(serverName).build();
    var stub = CatalogServiceGrpc.newBlockingStub(channel);

    var ex = assertThrows(StatusRuntimeException.class,
        () -> stub.createProduct(CreateProductRequest.getDefaultInstance()));
    assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
    // cleanup: channel.shutdown(), server.shutdown()
}
```

Sin red real, sin Spring context, milisegundos. Patrón a repetir para cada tipo de exception del sealed.

---

## Nivel 5 — Comandos críticos

### Los 3 que vas a usar todos los días

**1. Generar stubs desde el `.proto`:**

```powershell
./gradlew :contracts-catalog:generateProto
```

Corre `protoc` + `protoc-gen-grpc-java`. Output en `contracts/catalog/build/generated/source/proto/main/{java,grpc}/`. **Idempotente** — si no cambió el `.proto`, no hace nada.

**2. Compilar el servicio entero (transitivo):**

```powershell
./gradlew :services:catalog:build
```

Si los `.proto` no cambiaron, salta directo a la compilación Java. Si cambiaron, regenera primero.

**3. Build limpio del módulo de contratos:**

```powershell
./gradlew :contracts-catalog:build
```

Útil cuando agregás un `.proto` nuevo y querés confirmar que compila aislado, sin que arrastre el servicio.

### Para inspeccionar lo generado

```powershell
ls contracts/catalog/build/generated/source/proto/main/grpc/com/melown/catalog/v1/
# CatalogServiceGrpc.java

ls contracts/catalog/build/generated/source/proto/main/java/com/melown/catalog/v1/
# CreateProductRequest.java, CreateProductResponse.java, CatalogProto.java, *OrBuilder.java
```

Si los archivos no están, el `generateProto` no corrió o el `.proto` no tenía `service` definido.

### Para probar el servidor (cuando esté corriendo)

`grpcurl` es el `curl` de gRPC. Instalación: `choco install grpcurl` o `brew install grpcurl`. Después:

```powershell
# Lista services disponibles (requiere reflection habilitada en el server)
grpcurl -plaintext localhost:9090 list

# Describe un service específico
grpcurl -plaintext localhost:9090 describe melown.catalog.v1.CatalogService

# Invoca un RPC
grpcurl -plaintext -d '{"idempotency_key":"x","vendor_id":"...","title":"Test"}' \
  localhost:9090 melown.catalog.v1.CatalogService/CreateProduct
```

**Pre-requisito** para que `list` y `describe` funcionen: el server tiene que registrar `ProtoReflectionService`. En Melown, por convención, eso se habilita solo en `@Profile("dev")`. En prod queda apagado.

### Para limpiar cuando algo está raro

```powershell
./gradlew clean                       # borra todo build/
./gradlew :contracts-catalog:clean    # solo contracts
./gradlew --stop                      # mata el daemon
```

Después de `clean`, el próximo `build` re-genera todo.

### En IntelliJ

Cuando regeneraste stubs con un `.proto` nuevo, IntelliJ puede no verlos hasta que hagas **Reload Gradle Project** (el botón refresh del tool window de Gradle).

---

## Lo que hicimos y por qué (workarounds del journey)

Esta sección documenta los problemas reales que aparecieron al armar la base. Vale para no repetirlos.

### 1. Colisión de simple-name (Gradle 9.5 + Spring Boot 4)

**Síntoma:** ciclo de tareas `compileJava → jar → classes → compileJava` al hacer `implementation(project(":contracts:catalog"))` desde `:services:catalog`.

**Causa:** ambos módulos tenían `project.name = "catalog"`. Gradle distingue por path pero Spring Boot 4 confunde la resolución de variants.

**Fix:** path single-segment `:contracts-catalog` (nombre único: `contracts-catalog`).

### 2. Foojay 0.8.0 no entiende vendor `IBM_SEMERU`

**Síntoma:** `BUILD FAILED: IBM_SEMERU` al intentar resolver el toolchain JDK.

**Causa:** Gradle 9.5 introdujo el vendor enum `IBM_SEMERU` y la versión 0.8 del Foojay resolver no lo conoce.

**Fix:** upgrade a `0.10.0`.

### 3. `@javax.annotation.Generated` no existe en Java 21

**Síntoma:** `cannot find symbol class Generated location: package javax.annotation` al compilar el stub generado.

**Causa:** `protoc-gen-grpc-java` emite esa annotation que Java 9+ removió del JDK.

**Fix:** `compileOnly("javax.annotation:javax.annotation-api:1.3.2")` en `contracts/catalog/build.gradle.kts`. `compileOnly` alcanza porque la annotation tiene retention SOURCE.

### 4. Type-safe accessors colisionan con la extensión `protobuf`

**Síntoma:** `libs.versions.protobuf.get()` no resuelve adentro o cerca del bloque `protobuf { }`.

**Causa:** Kotlin DSL confunde `libs.versions.protobuf` con la extensión homónima del plugin protobuf.

**Fix:** acceso explícito vía `extensions.getByType<VersionCatalogsExtension>().named("libs").findVersion(...)`.

### 5. `id("grpc")` adentro del `plugins {}` del protobuf no resuelve

**Síntoma:** `Unresolved reference 'id'` adentro del bloque `protobuf { plugins { ... } }`.

**Causa:** el método `id` de la DSL del plugin protobuf necesita import explícito en Kotlin DSL.

**Fix:** `import com.google.protobuf.gradle.id` al tope del `build.gradle.kts`.

### 6. Spotless 6.25 + Java 21

**Síntoma:** `Could not initialize class com.pinterest.ktlint.ruleset.standard.rules.AnnotationRule`.

**Causa:** ktlint vía Spotless 6.25 hace reflexión que Java 21 bloquea.

**Fix:** upgrade Spotless a `7.0.4`.

---

## Roadmap inmediato

Para que el walking skeleton de `CreateProduct` ande punta a punta:

1. **Expandir el `.proto`** con los oneof de `ProductCondition`, `ProductStatus`, el message `Money`, y los otros RPCs (`GetProduct`, `PublishProduct`, etc.). Regenerar con `./gradlew :contracts-catalog:generateProto`.
2. **Convertir `application/` a interfaces** (ver HANDOFF paso 1): `CreateProductCommand` interface + `CreateProductService implements` + `ProductRepository` (port).
3. **Crear `ProductPersistenceAdapter`** que conecta el port con `ProductJpaRepository`.
4. **Hacer `DomainException` `sealed`** — prerequisito del switch exhaustivo del interceptor.
5. **Implementar `CatalogGrpcService.createProduct(...)`** — adapter fino, sin try/catch, delega al use case.
6. **Implementar `DomainExceptionInterceptor` con `@GrpcGlobalServerInterceptor`** — pattern matching exhaustivo sobre `DomainException`, mapeo a `Status`.
7. **`application.yaml`** con `grpc.server.port=9090`, datasource, Flyway.
8. **Levantar y probar con `grpcurl`**.

---

## Trampas comunes para Jr

| Error | Causa | Cómo lo agarrás |
|---|---|---|
| `IllegalStateException: call already closed` | `onNext` después de `onError` | Pattern: dejá que el interceptor cierre el stream, no manejes errores en el adapter |
| Clientes viejos leen basura | Reusaste un tag number borrado | Marcá `reserved <tag>;` siempre que borres un field |
| Mensaje técnico expuesto al cliente | `Status.withDescription(e.getMessage())` con detalle interno | En el interceptor, mapeá a mensaje genérico + log con MDC el detalle |
| Pérdida de precisión en `Money` | Usaste `double` para dinero | `int64 amount + string currency` SIEMPRE |
| Bean Validation no anda | `@Valid` en el adapter gRPC | No funciona — validación a mano o en el compact constructor del input record |
| Optional confuso | `Optional<T>` en proto3 | Usar la keyword `optional` (proto3 ≥ 3.15) o `wrappers.proto` |
| Cliente no se entera del error | `default` en el switch sobre `sealed` | Borrá el `default` — el compile-time check del sealed es el premio |
| `KIND_NOT_SET` runtime | Olvidaste el caso vacío del `oneof` | El switch sobre `oneof` siempre tiene un caso "ninguno seteado" — manejalo |
| Stack trace al cliente | `Throwable` genérico no mapeado | Default = `Status.INTERNAL` + log con stack, mensaje genérico al cliente |
| `compileJava` no ve stubs | IntelliJ no reloadeó | Refresh Gradle del tool window |

---

## Patrones con nombre (para entrevistas)

- **Anti-Corruption Layer (Evans)** — el mapper proto ↔ domain. Proto es el modelo externo, domain el interno; el mapper traduce sin que se contaminen.
- **Stub Pattern / RPC Stub** — las clases generadas por protoc. Cliente "habla" con un stub local que internamente serializa y manda al wire.
- **Interceptor + Chain of Responsibility (GoF)** — `ServerInterceptor` wrapea cada call. Cadena de interceptors = chain.
- **Idempotency Key Pattern** — clave en el request que el server usa para deduplicar. Acompaña Outbox (ADR `0007`).
- **Memento Pattern** — `ProductSnapshot`. Lo que el mapper consume; aísla el agregado de exponer estado.
- **State Pattern + Sealed Types** — `ProductStatus` sealed con `isVisibleInCatalog` polimórfico. Aplicado en el dominio, no en el wire.

---

## Referencias

- ADR `0001` — Protobuf para gRPC y Avro para Kafka.
- ADR `0002` — Usar gRPC para RPC interno.
- ADR `0005` — Estructura interna hexagonal (define naming de adapter/interceptor y mapeo de Status).
- ADR `0007` — Outbox + CDC (donde encaja `idempotency_key`).
- [gRPC Core Concepts: deadlines](https://grpc.io/docs/guides/deadlines/)
- [gRPC Status Codes](https://grpc.io/docs/guides/status-codes/)
- [Protobuf Language Guide (proto3)](https://protobuf.dev/programming-guides/proto3/)
- [protobuf-gradle-plugin README](https://github.com/google/protobuf-gradle-plugin)
- [grpc-spring-boot-starter (net.devh)](https://github.com/yidongnan/grpc-spring-boot-starter)
- [grpcurl](https://github.com/fullstorydev/grpcurl)
