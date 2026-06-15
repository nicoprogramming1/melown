# Catalog service — handoff 2026-06-15

Estado tras review iterativo #4 (sesión actual). Próxima sesión retoma desde acá.

## Veredicto del último review

- `domain/`: **SSr sólido**.
- `application/` y `infrastructure/`: **Jr-Avanzado**, esqueleto sin ciclo cerrado.

Tres frentes abiertos sin cerrar ninguno. Próximo objetivo: walking skeleton — `CreateProduct` funcionando punta a punta (**gRPC** → use case → port → adapter → JPA → DB). REST público vive en `api-gateway`, no en catalog (ADR `0002`).

---

## Lo que está bien (no tocar)

- `ProductStatus` y `ProductCondition` sealed con métodos polimórficos (`isVisibleInCatalog`, `allowsPurchase`, `isExpired`).
- Records con compact constructors + `Objects.requireNonNull`.
- `ProductSnapshot` con tipos ricos (no aplanado) — Memento Pattern.
- `ProductId.create()` vs `ProductId.reconstitute(UUID)` — distinción nuevo/rehidratado.
- `ProductMapper` con switch exhaustivo sobre sealed sin `default` — Java 21 cobra el beneficio del compile-time check.
- Layout hexagonal correcto en su forma: `application/port/in|out`, `application/usecase`, `infrastructure/adapter/out/persistence/{entities,mappers}`.

---

## Lo que NO COMPILA / bugs silenciosos (urgente)

1. `ProductEntity` importa `java.awt.*` — borrar.
2. `@RequiredArgsConstructor` con cero fields `final` = no genera constructor útil. Decidir: build vacío + setters o `@AllArgsConstructor`.
3. `snapshot.id()` devuelve `ProductId`; entity espera `UUID` → `snapshot.id().value()`.
4. `snapshot.specifications()` se pasa al constructor pero la entity no tiene field — comentar consistente en ambos lados.
5. `setVersion(snapshot.version())` en el mapper pisa el `@Version` de Hibernate → sacar.
6. `String version` debería ser `@Version Long version`. Sin esto, cero locking optimista.
7. Falta `@Enumerated(EnumType.STRING)` en los 3 enums (`ConditionGrade`, `ProductStatus` enum, `ConditionKind`) — JPA persiste por ordinal por default; reordenar el enum corrompe la DB silenciosamente.
8. No hay `@EnableJpaAuditing` ni `@EntityListeners(AuditingEntityListener.class)` → `@CreatedDate`/`@LastModifiedDate` no hacen nada, quedan `null` para siempre.
9. `ProductMapper` con `@Component` + métodos `static` = contradicción. Resolver: bean (sin `static`) o utility (sin `@Component`). Idiomático Spring = bean.

---

## Regresiones pendientes de reviews previos

- Enum `ProductStatus` no se renombró a `ProductStatusKind` — sigue conflicto naming con el sealed homónimo. IDE auto-importa el equivocado tarde o temprano.
- `Product` sigue **anémico** — sin `publish()`, `pause()`, `archive()`, `changeSpecs()`. La sealed `ProductStatus` está esperando que el agregado la use.
- `Specifications` vacío (record sin contenido).
- `RehydrateProductSpec` es código muerto — `Product.rehydrate(...)` toma `ProductSnapshot` directo. Borrar.
- `NewCondition.guarantee()` redeclarado (el record ya genera el accessor) — borrar.
- `Product.rehydrate` hace `ProductId.reconstitute(snapshot.id().value())` — destruye y reconstituye VO que ya es VO. Pasar directo.

---

## Layout fuera de ADR `0005`

- `infrastructure/http/GlobalExceptionHandler.java` — el inbound de catalog es gRPC, no REST. El equivalente al `@RestControllerAdvice` en gRPC es un `ServerInterceptor` que captura `DomainException` y traduce a `StatusRuntimeException` con el `Status` correspondiente. Va en `infrastructure/adapter/in/grpc/interceptors/` (o `config/`). Borrar `http/`.
- Falta `infrastructure/adapter/in/grpc/` — `CatalogGrpcService extends CatalogServiceGrpc.CatalogServiceImplBase` (anotado `@GrpcService` de `net.devh:grpc-spring-boot-starter`).
- Falta `contracts/catalog/proto/` (definición `.proto` del `CatalogService` con sus RPCs: `CreateProduct`, `GetProduct`, etc.).
- Falta `infrastructure/config/`.
- Falta `application/port/out/ProductRepository` (interface).
- Falta `ProductJpaRepository extends JpaRepository<ProductEntity, UUID>`.
- Falta `ProductPersistenceAdapter implements ProductRepository` — sin esto, **nadie llama al mapper**.
- `CreateProductCommand` es `class`, debería ser `interface` (ADR `0005` línea 76-77).
- `ProductEventPublisher` es `class`, debería ser `interface` (ADR `0005` línea 82).
- `CreateProductUseCase` debería ser `CreateProductService implements CreateProductCommand` con `@Service @Transactional`.

---

## Roadmap próxima sesión (en orden de impacto)

### 1. Walking skeleton — cerrar el ciclo hexagonal

Hasta que `CreateProduct` no funcione punta a punta (**gRPC → use case → port → JPA → DB**), el resto es decoración.

- Definir `.proto` en `contracts/catalog/` con `service CatalogService { rpc CreateProduct(...) returns (...); }` (puede ser un solo RPC para el walking skeleton).
- Configurar Gradle para generar stubs Java desde el `.proto` (plugin `com.google.protobuf`).
- Agregar dependencia `net.devh:grpc-spring-boot-starter` en `services/catalog/build.gradle.kts`.
- Crear `CatalogGrpcService extends CatalogServiceGrpc.CatalogServiceImplBase` en `infrastructure/adapter/in/grpc/` con `@GrpcService`. Adapter fino: recibe proto → mapea a input del use case → invoca port → mapea resultado a proto.
- Convertir `CreateProductCommand` y `ProductEventPublisher` en `interface`.
- Crear `ProductRepository` (interface) en `application/port/out/`.
- Crear `ProductJpaRepository`.
- Crear `ProductPersistenceAdapter implements ProductRepository`.
- Renombrar `CreateProductUseCase` → `CreateProductService implements CreateProductCommand`, anotar `@Service @Transactional`.

### 2. Bugs silenciosos de persistencia (mismo sprint que el 1)

- `@Enumerated(EnumType.STRING)` × 3 en `ProductEntity`.
- Crear `infrastructure/config/JpaConfig.java` con `@EnableJpaAuditing`.
- `@EntityListeners(AuditingEntityListener.class)` en `ProductEntity`.
- Sacar `setVersion(...)` del mapper.
- Cambiar `String version` → `@Version Long version`.
- Borrar `import java.awt.*`.
- Resolver `ProductMapper`: bean (sin `static`).

### 3. Flyway + datasource + Testcontainers

- `src/main/resources/db/migration/V1__create_product.sql` con columnas + constraints (`nullable=false`, `length=200` para `name`, etc.).
- `application.yaml` con datasource + `spring.jpa.hibernate.ddl-auto=validate`.
- Primer test Testcontainers: crear product nuevo, recuperar, verificar `condition_kind='NEW'`, `guarantee` persistido.

### 4. Renombre `ProductStatus` enum → `ProductStatusKind` + `kind()` polimórfico

- Agregar `Kind kind()` al sealed `ProductStatus` y `ProductCondition`.
- Cada variante implementa `kind()`.
- En el mapper, sacar `setStatusKind(DRAFTED)` del switch → `entity.setStatusKind(snapshot.productStatus().kind())` afuera. Reduce ruido del mapper a la mitad.

### 5. Romper la anemia del agregado

- `Product.publish()`, `Product.pause(UUID actor)`, `Product.archive(String reason)` con pattern matching exhaustivo sobre `ProductStatus`.
- Transiciones polimórficas: `ProductStatus.transitionToPublished()` etc. (cada estado decide si la transición es legal; ilegal tira `BusinessRuleException`).
- Validaciones en factories: `title` no null/blank, `vendorId` no null, `basePrice >= 0` (cuando exista `Money`).
- `ProductCreatedEvent` como `record` con campos (no `class`).
- Tests JUnit del agregado (sin Spring).

### 6. Exception handling real + jerarquía completa

- Crear `ValidationException`, `ConflictException`, `BusinessRuleException` (ADR `0005` línea 91-99).
- Implementar `ServerInterceptor` que capture `DomainException` y traduzca a `StatusRuntimeException` con la tabla de mapeo de ADR `0005`:
  - `ValidationException` → `Status.INVALID_ARGUMENT`
  - `NotFoundException` → `Status.NOT_FOUND`
  - `ConflictException` → `Status.ABORTED`
  - `BusinessRuleException` → `Status.FAILED_PRECONDITION`
- Ubicación: `infrastructure/adapter/in/grpc/interceptors/DomainExceptionInterceptor.java`.
- Borrar `infrastructure/http/`.
- (Si más adelante exponés `/actuator` REST para ops, ahí sí va un `@RestControllerAdvice` aparte — pero es scope diferido).

---

## Decisiones consolidadas en esta sesión

- **Catalog expone gRPC, no REST** (ADR `0002`). REST público vive en `api-gateway`. Catalog puede tener REST solo para ops (`/actuator`).
- **Patrón Snapshot adoptado** (Memento) para que mappers JPA/Avro/proto accedan al estado sin getters en el agregado.
- **Discriminadores `conditionKind`, `statusKind`** en `ProductEntity` para persistir sum types (Single Table Inheritance — la estrategia correcta para 2/4 variantes con pocos campos).
- **Switch exhaustivo sin `default`** sobre sealed en el mapper — el premio de Java 21.
- **Lombok aceptado en infraestructura (entity, mappers)**, prohibido en el dominio (espíritu de ADR `0005`).

---

## Decisiones pendientes

- **MapStruct vs mapper a mano:** ADR `0005` prescribe MapStruct, hoy el mapper es a mano. Migrar a MapStruct o escribir ADR de revisión que justifique apartarse.
- **Reconciliar `bounded-contexts.md` con el modelo:** 3 vs 4 estados, `title` vs `name`, faltan `categoryId`, `basePrice` (con `Money` VO), `version`, `createdAt`, `updatedAt`. Actualizar el doc canónico o alinear el modelo.
- **`Specifications`:** ¿`Map<String,String>` inmutable o estructura tipada con invariantes (claves no duplicadas, longitudes)?
- **`Clock` inyectable al dominio:** hoy `DraftedStatus.isExpired()` y `createNew()` no son testeables sin viajar en el tiempo. `Instant.now()` hardcoded.

---

## Lecturas pendientes

- Vaughn Vernon, *Implementing DDD* — capítulo de aggregates (collecting domain events, pull pattern).
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* — snapshot pattern + persistence adapter.
- [grpc-spring-boot-starter README](https://github.com/yidongnan/grpc-spring-boot-starter) — antes de armar el GrpcService.
