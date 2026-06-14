# 0005. Usar Hexagonal (Ports & Adapters) con dominio puro y CQRS lightweight como estructura interna de cada servicio

- **Status:** Accepted
- **Date:** 2026-06-12
- **Deciders:** @nwnorowsky

## Contexto

El ADR `0004` definió cómo está estructurado el monorepo (módulos top-level, plugins de convención, contratos separados). Este ADR define **cómo está estructurado el código adentro de cada `services/<name>/`**: paquetes, dependencias entre capas, convenciones de naming, modelo de errores y testing.

Las fuerzas en juego:

- **Múltiples adapters por servicio.** Cada uno puede tener REST + gRPC + Kafka como entradas y persistence + Kafka outbox + cliente gRPC hacia otros servicios como salidas. La estructura tiene que acomodar N entradas y N salidas sin mezclarlas.
- **Regla #1 de `CLAUDE.md`** — ningún servicio lee la implementación de otro. Esto se enforcea a nivel de módulos (`0004`) y se complementa con disciplina interna: el dominio nunca debe filtrarse al wire.
- **Una persona aprendiendo.** El proyecto existe para subir de Jr-Avanzado a Sr/Staff. La estructura interna tiene que enseñar el patrón que se usa en compañías serias.
- **Java 21** ofrece records (value objects gratis), sealed types (jerarquías controladas), pattern matching. La estructura debe sacarles provecho.
- **`melown.java-base` (definido en `0004`) ya incluye ArchUnit.** Las reglas concretas se definen acá.
- **Cuatro frameworks/anotaciones que pueden contaminar el dominio:** JPA/Hibernate, Spring, Jackson, Avro/Protobuf generated code. Si todos quieren anotar las mismas clases, el dominio deja de ser puro.

## Decisión

Adoptar **Hexagonal (Ports & Adapters)** con tres paquetes raíz por servicio: `domain`, `application`, `infrastructure`. Dependencias apuntan hacia adentro (`domain ← application ← infrastructure`, nunca al revés). **Dominio puro** sin anotaciones de framework, separado de las entidades JPA por mappers. **Use cases con naming Command/Query** (CQRS lightweight, sin event sourcing). **Errores como jerarquía de excepciones tipadas**. **MapStruct** como herramienta de mapeo entre capas.

### Layout de paquetes

```
services/catalog/src/main/java/com/melown/catalog/
├── domain/                          # núcleo puro — sin Spring, sin JPA, sin Avro, sin Jackson
│   ├── model/                       # aggregates, entities, value objects (records)
│   │   ├── Product.java             # aggregate root (class, mutable)
│   │   ├── ProductId.java           # record ProductId(UUID value)
│   │   └── Money.java               # record value object
│   ├── event/                       # domain events (Java puro, NO Avro todavía)
│   │   └── ProductCreated.java      # record con datos del evento
│   └── exception/                   # jerarquía de excepciones de dominio
│       ├── DomainException.java
│       ├── ValidationException.java
│       ├── NotFoundException.java
│       ├── ConflictException.java
│       └── BusinessRuleException.java
├── application/                     # casos de uso + puertos
│   ├── port/
│   │   ├── in/                      # input ports (use case interfaces)
│   │   │   ├── CreateProductCommand.java     # interface + input record
│   │   │   └── GetProductByIdQuery.java      # interface + input record
│   │   └── out/                     # output ports (lo que la infra implementa)
│   │       ├── ProductRepository.java
│   │       ├── ProductEventPublisher.java
│   │       └── InventoryClient.java          # cliente gRPC hacia inventory
│   └── usecase/                     # implementación de los use cases
│       ├── CreateProductService.java         # implements CreateProductCommand
│       └── GetProductByIdService.java        # implements GetProductByIdQuery
└── infrastructure/                  # adapters (todos los frameworks viven acá)
    ├── adapter/
    │   ├── in/
    │   │   ├── rest/                # REST controllers (sufijo RestController)
    │   │   ├── grpc/                # gRPC service impl (sufijo GrpcService)
    │   │   └── messaging/           # Kafka event consumers (sufijo EventListener)
    │   └── out/
    │       ├── persistence/         # JPA entities + repos + Flyway
    │       │   ├── ProductEntity.java                # @Entity
    │       │   ├── ProductJpaRepository.java         # Spring Data
    │       │   ├── ProductPersistenceAdapter.java    # implements ProductRepository (port)
    │       │   └── ProductPersistenceMapper.java     # @Mapper MapStruct
    │       ├── messaging/           # Kafka producers + outbox
    │       └── grpc/                # clientes gRPC hacia otros servicios
    └── config/                      # @Configuration de Spring, wiring de beans
```

### Naming conventions

- **Aggregate root:** clase mutable con identidad (`Product`). Encapsula invariantes en sus métodos.
- **Value objects:** `record` (Java 21+). Inmutables por construcción.
- **IDs:** `record ProductId(UUID value)`. Tipados — nunca un `UUID` desnudo cruzando capas, así el compilador detecta cuando mezclamos `ProductId` con `OrderId`.
- **Domain events:** `record` en `domain.event.*`. Datos puros, sin anotaciones.
- **Use cases (Command):**
  - Input port: interface `CreateProductCommand` + record `CreateProductInput`.
  - Implementación: clase `CreateProductService` con `@Service` y `@Transactional`.
- **Use cases (Query):**
  - Input port: interface `GetProductByIdQuery` + record `GetProductByIdInput` (o argumento directo si es un solo campo).
  - Implementación: clase `GetProductByIdService` con `@Service` y `@Transactional(readOnly = true)`.
- **Output ports:** interfaces puras sin anotaciones en `application.port.out`. Ej: `ProductRepository`.
- **Adapters out:** sufijo `Adapter`, implementan el output port. Ej: `ProductPersistenceAdapter implements ProductRepository`.
- **JPA entities:** sufijo `Entity`. Solo en `infrastructure.adapter.out.persistence`. Nunca expuestos fuera del adapter.
- **Mappers:** `@Mapper` de MapStruct, sufijo `Mapper`. Ej: `ProductPersistenceMapper`.
- **Controllers/Listeners:** sufijos `RestController`, `GrpcService`, `EventListener`.
- **Config:** vive en `infrastructure.config`, sufijo `Config`.

### Modelo de errores

Jerarquía sealed bajo `DomainException` (abstract, extends `RuntimeException`):

```
DomainException
├── ValidationException      // input no cumple invariantes del dominio
├── NotFoundException        // entidad no existe
├── ConflictException        // estado actual no permite la operación (optimistic lock, retryable)
└── BusinessRuleException    // regla de negocio violada (no retryable)
```

**Mapeo al wire** (en interceptors / `@RestControllerAdvice`, no en cada controller):

| Excepción | gRPC status | HTTP status |
|---|---|---|
| `ValidationException` | `INVALID_ARGUMENT` | 400 |
| `NotFoundException` | `NOT_FOUND` | 404 |
| `ConflictException` | `ABORTED` | 409 |
| `BusinessRuleException` | `FAILED_PRECONDITION` | 422 |

Las excepciones cruzan libremente desde el dominio hasta el interceptor — no se atrapan ni traducen en capas intermedias. La traducción al wire es responsabilidad del adapter de entrada.

### Reglas ArchUnit (en `melown.java-base`)

Se aplican a todos los módulos `:services:*` automáticamente:

1. Classes en `..domain..` **no pueden** estar anotadas con `jakarta.persistence.*`, `org.springframework.*`, `com.fasterxml.jackson.*`, `io.grpc.*`, `org.apache.avro.*`.
2. Classes en `..domain..` **no pueden** depender de classes en `..application..` ni `..infrastructure..`.
3. Classes en `..application..` **no pueden** depender de classes en `..infrastructure..`.
4. Classes en `..application.port..` **no pueden** depender de classes en `..application.usecase..` (ports son interfaces puras).
5. Classes en `..infrastructure.adapter.in..` **no pueden** depender de classes en `..infrastructure.adapter.out..` ni viceversa.
6. Classes anotadas con `@Entity` solo pueden existir en `..infrastructure.adapter.out.persistence..`.
7. Classes en `..domain..` no usan `java.util.Date`, `java.util.Calendar`, ni `java.sql.Timestamp` — solo `java.time.*`.

Estas reglas se ejecutan como parte del test suite (`./gradlew test`). Romperlas falla el build.

### Testing

- **Domain:** unit tests puros con JUnit 5. Sin Spring, sin Mockito, sin DB. Tests de invariantes del agregado.
- **Application:** unit tests con Mockito sobre los output ports. Tests de orquestación del use case.
- **Infrastructure adapters:** integration tests con Testcontainers (Postgres real para persistence, Kafka real para messaging).
- **End-to-end:** suite separada (a partir de Fase 1, vía `docker compose up` + tests contra el stack levantado).
- **Source set:** tests al lado del código que testean — `src/test/java/com/melown/<service>/{domain,application,infrastructure}/...`. Test fixtures compartidos en `src/testFixtures/` cuando aparezcan (vía el plugin `java-test-fixtures`).

## Consecuencias

### Positivas

- **Dominio puro testeable sin frameworks.** Tests de invariantes corren en milisegundos, sin levantar contexto Spring, sin DB.
- **Disciplina hexagonal enforced en CI.** Las 7 reglas ArchUnit fallan el build, no esperan a la review del PR.
- **Domain events independientes del wire.** El agregado emite `ProductCreated` (Java puro); el outbox/publisher lo traduce a Avro al borde. Esto preserva la posibilidad de cambiar el wire format (ADR `0001` puede revisarse) sin tocar dominio.
- **IDs tipados** previenen bugs clásicos de "le pasé el OrderId al método que espera ProductId" — el compilador lo agarra.
- **CQRS lightweight** separa intención (mutación vs lectura). `@Transactional(readOnly = true)` en queries permite optimizaciones de JPA. Use cases focalizados son fáciles de testear.
- **Excepciones tipadas + mapeo centralizado** evita los `try/catch` en cada controller. Idiomático Spring.
- **MapStruct compile-time** — el mapper se genera en build, runtime no paga overhead. Errores de mapeo aparecen al compilar.
- **`@Entity` solo en persistence** garantiza que el dominio no arrastra el lifecycle de Hibernate (detached/managed/lazy loading).

### Negativas

- **Boilerplate de mapeo.** Cada `Product` tiene un `ProductEntity` + un `ProductPersistenceMapper`. Para entidades simples se siente over-engineered. MapStruct lo mitiga pero no lo elimina.
- **Curva conceptual para Jr-Avanzado.** Pensar en términos de ports/adapters vs `controller → service → repository` tradicional es nuevo. Los primeros use cases van a sentirse desproporcionados.
- **Sin god-services.** Si venís de tutoriales Spring con `@Service ProductService { ... }` con 15 métodos, esta estructura te obliga a separarlos en use cases. Más archivos, menos código por archivo.
- **`infrastructure.config` se vuelve el lugar de wiring.** No es boilerplate excesivo pero es el único lugar donde hay que mirar para entender cómo se atornilla todo.
- **CQRS naming requiere consistencia.** "¿Esto es Command o Query?" tiene que ser decisión consciente al crear cada use case. Un use case ambiguo (que muta *y* devuelve algo no trivial) es un olor — separarlo.

### Riesgos a vigilar

- **Tentación de "service como god-class."** En N-tier el `ProductService` termina con 20 métodos. Acá, cada Command/Query es un use case separado con sus propios ports inyectados. Disciplinarse a no crear `ProductService` con 5 métodos diferentes.
- **Mappers traduciendo "lo mismo a lo mismo."** Si `Product` (domain) y `ProductEntity` (JPA) son 99% iguales, el mapper se siente tonto. Es deuda buena: cuando un día el dominio diverja (ej. nuevo field calculado), el mapper ya está. MapStruct lo hace gratis.
- **Domain events confundidos con Kafka events.** Los domain events (`domain.event.*`) son Java puro emitido al cambiar el estado del agregado. Los Kafka events son Avro al wire (`:contracts:catalog:avro`). El outbox traduce de uno a otro. No mezclar; no exponer el Avro generado adentro del dominio.
- **CQRS confundido con event sourcing.** Acá CQRS es solo **naming** (Command/Query). No hay event store, no hay proyecciones materializadas por replay. Event sourcing es Phase 7 stretch (`docs/README.md`).
- **`ConflictException` vs `BusinessRuleException`** — la distinción retryable/no-retryable es sutil. Documentar con ejemplos en el javadoc de cada una para no confundirlos.

## Alternativas consideradas

### Alternativa A: N-tier clásico (`controller` / `service` / `repository`)

Estructura típica de tutoriales Spring: tres capas, sin dominio puro.

**Por qué perdió:**
- Sin disciplina de dependencias invertidas, la infraestructura sangra al dominio (controllers conocen entidades JPA, repos usan tipos de Spring Data en signatures).
- "Service" termina como god-class con N métodos heterogéneos.
- Testar el dominio en aislamiento requiere Spring context, lentitud y flakiness.
- Pierde el valor educativo central del proyecto: aprender el patrón que se discute en entrevistas Sr/Staff.

### Alternativa B: Onion Architecture

Capas concéntricas: Domain Model → Domain Services → Application Services → Outer Ring.

**Por qué perdió:**
- Misma forma que hexagonal con más anillos. Visualmente más complejo sin ventaja práctica.
- "Domain Services" como capa aparte de "Domain Model" agrega fricción que pocos casos justifican.
- Onion brilla cuando hay lógica de dominio rica con múltiples agregados colaborando; los microservicios tienden a tener agregados chicos donde esa capa extra no paga.

### Alternativa C: Clean Architecture (Uncle Bob)

Entities → Use Cases → Interface Adapters → Frameworks & Drivers. Vocabulario propio (Interactors, Boundaries, Presenters).

**Por qué perdió:**
- En la práctica termina pareciéndose a hexagonal con nombres más confusos.
- El canon de Bob es más útil para apps desktop/CLI que para microservicios distribuidos.
- Hexagonal tiene más tracción en docs Spring/Java industry. Referencias más fáciles de encontrar.

### Alternativa D: Vertical Slice (feature-based)

Organizar por feature/use case (`features/CreateProduct/{Handler, Validator, Mapper}`) en vez de por capa.

**Por qué perdió:**
- Patrón avanzado, valioso cuando el equipo lo conoce y la app tiene muchos endpoints similares.
- Para alguien aprendiendo microservicios y arquitectura al mismo tiempo, agrega complejidad sin pagar todavía.
- Funciona mejor en .NET con MediatR; en Java/Spring requiere más andamiaje manual.
- Migrar de hexagonal a vertical slice más adelante es viable; al revés es más doloroso.

### Alternativa E: JPA-on-domain (entidades de dominio anotadas)

`@Entity` directamente sobre el `Product` de dominio, sin separar.

**Por qué perdió:**
- Es la opción pragmática del 90% de proyectos Spring, sí, pero **el objetivo de Melown es aprender la disciplina pura**. JPA-on-domain te ahorra trabajo y te quita el aprendizaje del *por qué* la separación existe.
- Múltiples anotaciones (JPA, Jackson, Avro generated) compiten por las mismas clases. Cualquiera "gana" pero el dominio se ensucia.
- Lifecycle de Hibernate (managed/detached, lazy loading, dirty checking) se filtra al modelo de dominio. Bugs sutiles donde el dominio actúa diferente "según el contexto de la sesión".
- Una vez aprendida la versión pura, podés decidir cuándo comprometerla. Al revés es más difícil.

### Alternativa F: `Result<T, E>` types en vez de excepciones

Approach funcional (vavr, Arrow, Either<L,R>). Use cases devuelven `Result<Product, DomainError>`.

**Por qué perdió:**
- No es idiomático Java/Spring. La mayoría del ecosistema asume excepciones.
- Mezcla mal con Spring AOP (`@Transactional` rollback hace match con excepciones; con `Result` hay que configurar rollback manual).
- Interceptors gRPC y `@RestControllerAdvice` están diseñados alrededor de excepciones.
- Costo de aprender un wrapper monádico extra para un beneficio marginal en este contexto.
- En Kotlin sería otra historia; en Java 21 sin lenguaje monádico, pesa más.

### Alternativa G: Mappers a mano sin MapStruct

Escribir cada `ProductMapper` como clase Java normal.

**Por qué perdió:**
- MapStruct es compile-time → cero overhead runtime, código generado legible.
- A 5+ entidades por servicio, mantener mappers a mano se vuelve carga real (cambio un field, hay que tocar 3 lugares).
- MapStruct atrapa errores de mapeo (campos no mapeados, types incompatibles) en compile-time del propio servicio.
- La complaint común de "MapStruct es magia" se diluye cuando ves que genera código Java normal y debuggeable.

### Alternativa H: Un service por agregado (sin separar Command/Query)

`ProductService` con `createProduct`, `getProduct`, `updateInventory`, etc.

**Por qué perdió:**
- Tiende al god-class. Métodos diferentes requieren ports diferentes; el service termina con 5 ports inyectados de los cuales cada método usa solo 1-2.
- Sin distinción Command/Query, perdés el matiz semántico (mutación vs lectura). Eso también afecta `@Transactional(readOnly = true)` y otras optimizaciones.
- Use cases focalizados son más fáciles de testear (cada uno tiene pocos mocks).
- El "ahorro" de archivos es ilusorio: en CQRS lightweight cada use case es 2 archivos (interface + service) y casi nunca tenés que abrir más de uno a la vez.

## Referencias

- ADR `0001` — Avro para Kafka (los domain events se traducen a Avro en el outbox).
- ADR `0002` — gRPC para RPC interno (los adapters in/grpc reciben llamadas, los out/grpc las hacen).
- ADR `0004` — Layout del monorepo Gradle (ArchUnit en `melown.java-base`).
- Alistair Cockburn — [Hexagonal Architecture (artículo original)](https://alistair.cockburn.us/hexagonal-architecture/).
- Tom Hombergs — *Get Your Hands Dirty on Clean Architecture* — referencia práctica más útil para hexagonal en Java/Spring.
- [MapStruct reference guide](https://mapstruct.org/documentation/stable/reference/html/).
- [ArchUnit user guide](https://www.archunit.org/userguide/html/000_Index.html).
- ADR pendiente: convención para `domain events` cuando se traduzcan a Avro (cómo se decide qué fields del domain event van al wire, cómo se versionan).
