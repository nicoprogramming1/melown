# 0004. Estructurar el monorepo Gradle con multi-proyecto, build-logic y contratos top-level

- **Status:** Accepted
- **Date:** 2026-06-12
- **Deciders:** @nwnorowsky

## Contexto

El proyecto ya decidió monorepo Gradle multi-módulo en `docs/README.md` (descartando polirepo). Lo que este ADR define es **cómo** está estructurado ese monorepo: dónde viven los módulos, cómo se comparte el build logic, dónde están los contratos de servicios y qué reglas de dependencia existen entre módulos.

Las fuerzas en juego:

- **Múltiples servicios** (10+ en el roadmap completo). Cada uno necesita configuración Gradle similar pero no idéntica: la mayoría Spring Boot, uno Quarkus (`notifications`), unos pocos son librerías compartidas sin runtime propio.
- **Dos formatos de serialización con codegen:** Protobuf para gRPC (ADR `0002`) y Avro para Kafka (ADR `0001`). Los `.proto` y `.avsc` son artefactos versionables y compartibles entre servicios.
- **Regla dura #1 de `CLAUDE.md`:** ningún servicio lee la implementación de otro. El grafo de dependencias del monorepo tiene que enforcear esto — no alcanza con la convención social, debe ser estructural.
- **Una persona aprendiendo.** El boilerplate de un build setup serio tiene costo cognitivo, así que tiene que pagar lo que cuesta.
- **Mezcla Spring / Quarkus.** Hay convenciones compartibles (toolchain, JUnit 5, formatting, ArchUnit) y otras específicas (Spring Boot vs Quarkus build plugin).
- **Solo dev, sin equipo.** No hay necesidad de aislamiento total entre servicios; refactor-atomicity vale más que aislamiento.

## Decisión

Adoptar un **monorepo Gradle multi-proyecto plano** con la siguiente estructura y herramientas.

### Layout

```
melown/
├── settings.gradle.kts              # includeBuild("build-logic") + include() de cada módulo
├── build.gradle.kts                 # config raíz mínima
├── gradle/
│   └── libs.versions.toml           # version catalog (única fuente de versiones)
├── build-logic/                     # included build con plugins de convención
│   ├── settings.gradle.kts
│   └── src/main/kotlin/
│       ├── melown.java-base.gradle.kts        # toolchain, JUnit 5, Spotless, ArchUnit
│       ├── melown.spring-service.gradle.kts   # extiende java-base + Spring Boot plugin
│       ├── melown.quarkus-service.gradle.kts  # extiende java-base + Quarkus plugin
│       └── melown.contracts.gradle.kts        # extiende java-base + protoc + Avro plugins
├── services/                        # módulos ejecutables (un Dockerfile por carpeta)
│   ├── catalog/
│   ├── identity/
│   ├── inventory/
│   ├── orders/
│   └── notifications/               # Quarkus
├── libs/                            # librerías compartidas (no ejecutables)
│   ├── common/                      # tipos / errores / utilidades cross-service
│   └── observability/               # setup OTel reusable
└── contracts/                       # IDLs versionados como ciudadanos de primera
    ├── catalog/                     # .proto + .avsc owned by catalog
    ├── orders/
    └── shared/                      # tipos cross-cutting (Money, Address, IDs)
```

### Herramientas

- **Kotlin DSL** en todos los build scripts.
- **`build-logic/` como included build** (no `buildSrc/`).
- **Version catalog** en `gradle/libs.versions.toml` como única fuente de versiones de dependencias y plugins.
- **Spotless** como única herramienta de quality/formatting en Fase 1. SpotBugs, Error Prone y Checkstyle se evalúan en Fase 4 (production-ready).
- **ArchUnit** integrado en `melown.java-base` desde el primer servicio, con un set base de reglas: el package `domain` no importa de `infrastructure`, no usar `java.util.Date`, etc.
- **Build cache local** activo (`org.gradle.caching=true`).
- **Configuration cache** activo (`org.gradle.configuration-cache=true`).

### Reglas de dependencia entre módulos

Estas reglas se documentan en `CLAUDE.md` y se enforcean tanto por convención de PR como por validación en `build-logic` (allowed-dependencies plugin o equivalente cuando aparezca):

- Cualquier `:services:X` **puede** depender de `:contracts-*` (su propio contrato o el de otro servicio).
- Cualquier `:services:X` **puede** depender de `:libs:*`.
- Ningún `:services:X` **puede** depender de `:services:Y`. Si A necesita hablar con B, lo hace por gRPC contra `:contracts-B`.
- `:contracts-shared` no depende de nadie. `:contracts-<servicio>` puede depender de `:contracts-shared`.
- `:libs:*` no depende de `:services:*` ni de `:contracts-*` (las librerías son infraestructura cross-cutting, no dominio).

### Naming

- Plugin de convención: `melown.<tipo>` (ej: `melown.spring-service`).
- Module path: `:services:<nombre>`, `:libs:<nombre>`, `:contracts-<nombre>`.
- Group ID de artefactos: `com.melown.<modulo>` (ej: `com.melown.catalog`).

## Consecuencias

### Positivas

- **Multi-project plano es el estándar.** Referencias abundantes en docs oficiales de Spring y Gradle. Cualquier dev que entre al repo lo reconoce.
- **`build-logic/` como included build evita el problema de `buildSrc/`**, que recompila todo en cada cambio del build script. La diferencia se nota a partir del tercer módulo.
- **Kotlin DSL típado.** Errores de build script aparecen en compile-time del propio build, no en runtime. IntelliJ autocompleta.
- **Version catalog elimina drift de versiones** entre módulos. Cambiar la versión de Spring se hace en un solo lugar.
- **Plugins de convención reducen el `build.gradle.kts` de cada servicio a 5–10 líneas.** Sin copy-paste; el día que haya que actualizar Spring Boot se cambia un solo plugin.
- **Contratos top-level enforcean visual y estructuralmente** que la API pública de un servicio es un artefacto separado de su implementación. Imposible "accidentalmente" depender de la implementación de otro servicio.
- **ArchUnit en `java-base`** se ejecuta como parte del test suite de cualquier módulo. La disciplina hexagonal queda enforced en CI, no en discusión de PR.
- **Configuration cache** evita re-evaluar el grafo de configuración cuando no cambia — el grueso de los builds locales repetidos.
- **Build cache** evita recompilar tareas cuyos inputs no cambiaron. Funciona automáticamente.

### Negativas

- **Boilerplate inicial alto.** Para un único servicio en Fase 1, todo este setup se siente over-engineered. Empieza a pagar a partir del segundo servicio (Fase 2). Aceptarlo desde el principio evita refactorearlo después con código ya escrito encima.
- **Curva Kotlin DSL.** Los primeros días explotan errores de tipo en build scripts si nunca lo usaste. Transitorio.
- **Configuration cache puede romper plugins legacy mal escritos.** Los plugins mainstream (Spring Boot, protobuf, Avro, Quarkus) lo soportan. Si aparece uno que no, se desactiva para ese módulo puntualmente.
- **Falsa sensación de "refactor cross-service fácil".** Cambiar `contracts/catalog/order.proto` puede romper a `services/orders` sin que el IDE lo grite. Política: cualquier cambio en `contracts/` requiere revisar consumers explícitamente. Disciplina, no tooling.
- **ArchUnit es una herramienta más para aprender en Fase 1.** El costo cognitivo se paga con la garantía de que la estructura hexagonal queda enforced desde día uno.
- **Build times crecen con N módulos.** Mitigación: build cache, configuration cache, `--parallel`.

### Riesgos a vigilar

- **Plugins de convención mal diseñados se vuelven imposibles de cambiar.** Empezar simple: `java-base` con lo mínimo, sumar al `spring-service` solo lo que aparezca repetido en 2+ servicios. No anticipar abstracciones que todavía no se manifestaron.
- **`:contracts-shared` se vuelve zona caliente.** Si todos los servicios dependen de él, cambiarlo rompe a todos. Mitigación: mantenerlo chiquito (solo tipos primitivos cross-cutting como `Money`, `Address`, IDs), y resistir la tentación de meter ahí eventos de dominio.

## Alternativas consideradas

### Alternativa A: Composite builds (cada servicio su propio build, conectados por `includeBuild`)

Cada servicio sería un build Gradle independiente, incluido en el monorepo vía `includeBuild()`.

**Por qué perdió:**
- Compartir conventions plugins entre composite builds es más complejo — no podés simplemente `apply` un plugin de otro build, hay que publicarlo (al menos a maven local).
- Pierde refactor-atomicity: cambiar una API en `contracts/catalog` y el consumer en `services/orders` requeriría dos commits o un script que apunte a ambos builds.
- Aislamiento total no es lo que necesitamos para una persona — necesitamos refactor barato cross-cutting.
- Composite builds son ideales para librerías genuinamente independientes (ej: una organización con muchos productos compartiendo un SDK). No es el caso del monorepo de un único producto.

### Alternativa B: Estructura plana sin `services/` / `libs/` / `contracts/`

Todos los módulos al primer nivel del repo (`catalog/`, `identity/`, `catalog-contracts/`, `common/`, etc.).

**Por qué perdió:**
- A 5+ módulos ya no encontrás nada navegando. A 10+ es directamente caótico.
- Las tres categorías (servicio ejecutable, librería compartida, contrato) tienen ciclos de vida y políticas de dependencia distintas. Mezclarlas visualmente borra una distinción importante.

### Alternativa C: `buildSrc/` en vez de included build

Usar el directorio especial `buildSrc/` de Gradle para los plugins de convención.

**Por qué perdió:**
- `buildSrc/` recompila completamente con cada cambio en el build script. Included build solo recompila lo cambiado.
- Es la recomendación oficial de Gradle migrar de `buildSrc/` a `build-logic/` desde Gradle 7.
- Sin ventaja real comparado con included build.

### Alternativa D: Contracts per-service (`services/<name>/contracts/`)

Cada servicio contiene su submódulo de contratos adentro.

**Por qué perdió:**
- **Enforcement visual más débil.** Con los contratos viviendo dentro de `services/`, es fácil que un dev (o yo en una sesión futura) se "tiente" con `import com.melown.catalog.domain.Product` directamente. Separar `contracts/` arriba elimina la tentación: el path mismo grita "esto es API pública".
- En la práctica laboral (compañías tipo MercadoLibre) los contratos suelen vivir top-level o en un repo separado, justamente por esta razón.
- Trade-off real perdido: contrato y dominio quedan visualmente alejados. Mitigación: convención clara `:services:catalog` ↔ `:contracts-catalog` con el mismo nombre de servicio.

### Alternativa E: ArchUnit diferido a Fase 4 (production-ready)

Introducir ArchUnit recién cuando se trabaje resilencia/calidad.

**Por qué perdió:**
- La disciplina hexagonal **es más barata de enseñar y mantener si está enforced desde día uno**. Una vez que hay código que la viola, retrofittear ArchUnit es un refactor doloroso.
- El costo de ArchUnit en Fase 1 es chico (5–10 reglas básicas en `java-base`) y el beneficio es continuo.

### Alternativa F: Sumar SpotBugs / Error Prone / Checkstyle ahora

Múltiples herramientas de análisis estático desde Fase 1.

**Por qué perdió:**
- Costo cognitivo desproporcionado para Fase 1. Spotless cubre el 80% del valor (formateo consistente, import order, end-of-line).
- SpotBugs y Error Prone son valiosos cuando hay código en producción real, no walking skeleton. Fase 4 es su lugar.
- Múltiples herramientas reportando warnings desde día uno entrenan a ignorarlos (ruido).

### Alternativa G: Maven en vez de Gradle

**Por qué perdió:** ya decidido en `docs/README.md`, no reabrimos.

### Alternativa H: Polirepo

**Por qué perdió:** ya descartado en `docs/README.md` trade-offs: monorepo es más simple para un solo dev y permite refactors atómicos.

## Referencias

- `docs/README.md` — sección de stack target (monorepo Gradle).
- ADR `0001` — Avro para Kafka (los `.avsc` viven en `contracts/<servicio>/avro/`).
- ADR `0002` — gRPC para RPC interno (los `.proto` viven en `contracts/<servicio>/proto/`).
- ADR `0003` — K8s diferido (este ADR no asume nada de Helm/manifests; todo el setup es Compose-compatible).
- [Gradle docs — Sharing build logic between subprojects](https://docs.gradle.org/current/userguide/sharing_build_logic_between_subprojects.html)
- [Gradle docs — Version catalogs](https://docs.gradle.org/current/userguide/platforms.html)
- [ArchUnit user guide](https://www.archunit.org/userguide/html/000_Index.html)
- ADR pendiente: estructura interna por servicio (hexagonal vs onion, convenciones de paquetes Java). Este ADR asume que la estructura interna existirá pero no la define todavía.
