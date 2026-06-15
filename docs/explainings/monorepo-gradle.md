# Refactor a monorepo Gradle — explicación detallada

**Fecha:** 2026-06-14
**Disparador:** Fase 1 estaba arrancando con `catalog-service/` como proyecto
Gradle suelto (su propio `settings.gradle.kts`, su propio wrapper, package
`com.melown.catalog_service`). Eso contradecía el ADR `0004`
("Estructurar el monorepo Gradle con multi-proyecto, build-logic y contratos
top-level"). Antes de seguir codeando se reestructuró todo al layout del ADR.

Este documento explica **cada archivo creado, cada decisión, y cada concepto
previo** que hay que tener claro para leer ese código sin huecos.

---

## 0. Antes vs después

### Antes

```
melown/
├── docs/
├── c4-diagram.drawio
├── CLAUDE.md
└── catalog-service/                          ← proyecto Gradle SUELTO
    ├── settings.gradle.kts                   ← su propio settings
    ├── build.gradle.kts                      ← Spring Boot plugin aplicado acá
    ├── gradlew, gradlew.bat                  ← su propio wrapper
    ├── gradle/wrapper/
    ├── .gitignore, .gitattributes
    └── src/main/java/com/melown/catalog_service/
        ├── CatalogServiceApplication.java
        └── domain/model/Product.java
        └── infrastructure/adapter/out/persistence/ProductEntity.java
```

Problema: para sumar `identity` o `api-gateway` había que duplicar TODO el
build setup (toolchain, plugins, version manifest). Y además ningún módulo
podía depender de otro porque no había un grafo Gradle común — eran proyectos
independientes con `gradlew` hermanos pero desconectados.

### Después

```
melown/
├── .gitattributes                            ← line endings para wrapper
├── settings.gradle.kts                       ← ÚNICO settings, incluye los 3 servicios
├── build.gradle.kts                          ← root, intencionalmente vacío
├── gradlew, gradlew.bat                      ← ÚNICO wrapper
├── gradle/
│   ├── libs.versions.toml                    ← version catalog (única fuente de versiones)
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties         ← Gradle 9.5.1
├── build-logic/                              ← included build con convention plugins
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       ├── melown.java-base.gradle.kts       ← toolchain, JUnit, ArchUnit, Spotless
│       └── melown.spring-service.gradle.kts  ← extiende java-base + Spring Boot
├── services/
│   ├── catalog/
│   │   ├── build.gradle.kts                  ← 5 líneas, aplica melown.spring-service
│   │   └── src/main/java/com/melown/catalog/...
│   ├── identity/
│   │   ├── build.gradle.kts
│   │   └── src/main/java/com/melown/identity/...
│   └── api-gateway/
│       ├── build.gradle.kts
│       └── src/main/java/com/melown/apigateway/...
└── contracts/
    └── catalog/
        └── README.md                          ← placeholder, ningún IDL todavía
```

Si mañana sumás `inventory`, son ~5 líneas en `services/inventory/build.gradle.kts`,
una entrada en `settings.gradle.kts`, y listo. Toda la convención compartida vive
en `build-logic/`.

---

## 1. Conceptos previos (cosas que conviene tener claras antes de leer los archivos)

### 1.1 Monorepo Gradle multi-proyecto

Gradle tiene una jerarquía nativa de **proyectos** dentro de un build:

- Hay **un build raíz** (root project). El `settings.gradle.kts` define qué
  subproyectos lo componen vía `include(":a", ":b:c")`.
- Cada subproyecto tiene su propio `build.gradle.kts` con sus plugins y deps.
- Hay un `gradlew` único: `./gradlew :services:catalog:build` invoca la task
  `build` del subproyecto `:services:catalog`.

Esto es distinto a tener N repos o N proyectos Gradle independientes. La
diferencia clave: en un monorepo Gradle, los subproyectos **pueden depender
entre sí** (`implementation(project(":libs:common"))`) y refactors atómicos
(cambiás una API y a sus consumers en el mismo commit) son posibles.

### 1.2 Included build vs `buildSrc/`

Para compartir build logic (plugins de convención, tasks custom) entre
subproyectos, Gradle ofrece dos mecanismos:

- **`buildSrc/`**: directorio especial. Cualquier código que pongas ahí se
  compila ANTES que el build raíz y queda disponible para todos. Problema:
  cualquier cambio en `buildSrc/` invalida el grafo entero — todo se
  reconfigura desde cero. Para builds chicos eso es invisible, para 10+
  módulos cuesta.
- **`build-logic/` como included build** (vía `includeBuild("build-logic")`):
  es un build Gradle separado, completo, que el raíz "incluye". Se compila
  como pieza independiente con su propio caché. Cambiar algo en `build-logic/`
  solo recompila lo afectado, no invalida todo.

El ADR `0004` eligió **included build** explícitamente. La elección está
documentada como alternativa rechazada C del ADR.

### 1.3 Convention plugin (a.k.a. precompiled script plugin)

Un **convention plugin** es un plugin de Gradle escrito en formato `.gradle.kts`
(Kotlin DSL) que vive dentro de `build-logic/src/main/kotlin/`. Gradle los
llama "**precompiled script plugins**" porque los compila a clases JVM antes
de exponerlos al build raíz.

La idea: si todos tus servicios Spring necesitan toolchain 21 + JUnit 5 +
Spotless, en vez de copy-pastear esa configuración 10 veces, la escribís UNA
vez en un convention plugin y cada servicio lo aplica:

```kotlin
// services/catalog/build.gradle.kts
plugins {
    id("melown.spring-service")
}
```

¿De dónde sale ese id `melown.spring-service`? **Del nombre del archivo**.
Si el archivo se llama `melown.spring-service.gradle.kts`, su plugin id es
`melown.spring-service`. Eso es convención de Gradle, no hay que registrar nada
en ningún lado.

### 1.4 Version catalog

Un **version catalog** es un archivo TOML (`gradle/libs.versions.toml`) que
declara versiones, libraries y plugins en un único lugar. Después en
cualquier `build.gradle.kts` los referenciás como `libs.postgresql`,
`libs.archunit.junit5`, etc.

¿Por qué? Sin version catalog, cada `build.gradle.kts` tiene strings como
`"org.postgresql:postgresql:42.7.4"`. Cuando hay que actualizar Postgres,
buscás y reemplazás. Con catálogo, cambiás el número en un solo archivo y
todos los módulos se actualizan.

Sintaxis del archivo:
```toml
[versions]
postgresql = "42.7.4"

[libraries]
postgresql = { module = "org.postgresql:postgresql", version.ref = "postgresql" }
```

En el `build.gradle.kts` del servicio: `runtimeOnly(libs.postgresql)`.

### 1.5 Plugin marker artifact

Cuando un convention plugin hace `id("org.springframework.boot")`, Gradle
necesita encontrar el código de ese plugin en algún lugar. Por default lo
busca en el Gradle Plugin Portal por su id. Pero si querés controlar la
versión, tenés que declarar la **dependencia** que contiene el plugin.

El truco: cada plugin tiene un **plugin marker artifact** publicado en Maven
Central, cuyo nombre es `<plugin-id>:<plugin-id>.gradle.plugin:<version>`. Es
un POM vacío que apunta a la coordenada real del plugin. Si declarás esa
dependencia en tu `build-logic/build.gradle.kts`, la clase del plugin queda
en el classpath y `id("...")` la resuelve.

En nuestro caso, declaramos los plugin markers como **libraries** en el version
catalog (no como `[plugins]`), porque `build-logic` los consume con
`implementation(libs.plugin.spring.boot)` — necesita la clase en classpath
para que los convention plugins puedan aplicarla.

### 1.6 Java toolchain

Java **toolchain** = el JDK que Gradle usa para compilar y correr. Al declarar:

```kotlin
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
```

Gradle automáticamente:
1. Busca un JDK 21 ya instalado (en `~/.gradle/jdks/`, `JAVA_HOME`, IDEs).
2. Si no encuentra, lo descarga (de Adoptium por default).
3. Lo usa para compilar el módulo, independientemente del JDK con el que se
   invocó `gradlew`.

Esto significa: **no necesitás configurar `JAVA_HOME` para el proyecto**.
Cualquier dev clona, corre `./gradlew build`, y Gradle resuelve el JDK 21
automáticamente. Reproducibilidad.

### 1.7 Spotless

Herramienta de formatting/linting de código. Le decís "este código se formatea
con la regla X" y se encarga. Para Java usamos `googleJavaFormat()` — la
convención de formato de Google, opinionada y sin discusión.

`./gradlew spotlessApply` → formatea automáticamente.
`./gradlew spotlessCheck` → solo verifica, falla si algo no está bien
formateado.

### 1.8 ArchUnit

Librería que permite escribir tests en JUnit que **verifican reglas
arquitectónicas** del código. Ejemplos de reglas: "el package `domain` no
puede importar clases de `infrastructure`", "no usar `java.util.Date`", etc.

Esas reglas se enforcean en CI. Si alguien (o vos en 6 meses) viola la
estructura hexagonal, el build se rompe. No depende de discusión en PR.

El ADR `0004` exige ArchUnit desde el primer servicio. Acá agregamos la
dependencia pero todavía no escribimos las reglas (porque hay código actual
que las violaría — `java.util.Date` está en `Auditory` y `Product`).

### 1.9 Annotation processor (caso Lombok)

Un **annotation processor** es código que corre durante la compilación de
Java, lee anotaciones del source, y genera código adicional. Lombok hace
exactamente eso: ves `@Getter` en una clase y Lombok genera los métodos
`getX()` durante `javac`.

Para que Lombok funcione, hace falta declararlo en **dos scopes**:

- `compileOnly(libs.lombok)` → las anotaciones (`@Getter`, etc.) tienen que
  estar disponibles cuando javac compila los `.java`. Pero no se necesitan en
  runtime (los métodos ya están generados como bytecode), por eso `compileOnly`
  en vez de `implementation`. Resultado: Lombok no aparece en el JAR final.
- `annotationProcessor(libs.lombok)` → el procesador en sí, que javac invoca.
  Sin este, las anotaciones se ignoran.

Si solo declarás `compileOnly`, las anotaciones existen como markers pero
nadie genera código → `getX()` no existe → compile error en cualquier
`product.getName()`.

### 1.10 Configuration scopes en Gradle

Gradle tiene varios "buckets" donde podés meter dependencias. Los relevantes:

| Scope | Cuándo está disponible | Aparece en JAR final | Caso típico |
|---|---|---|---|
| `implementation` | compilación + runtime | sí | librerías de uso interno (Spring, lib propia) |
| `api` | compilación + runtime, **expuesta a consumers** | sí | librerías cuyo tipo aparece en signatures públicas |
| `compileOnly` | solo compilación | no | Lombok, anotaciones que se procesan |
| `runtimeOnly` | solo runtime | sí | driver JDBC (Postgres), backend de logging |
| `annotationProcessor` | javac classpath | no | Lombok processor, MapStruct |
| `developmentOnly` | dev mode, no en bootJar | no | spring-boot-devtools |
| `testImplementation` | compilación + run de tests | n/a | JUnit, Mockito |
| `testRuntimeOnly` | solo runtime de tests | n/a | junit-platform-launcher |

**Por qué `runtimeOnly(libs.postgresql)`:** el driver JDBC se carga via SPI en
runtime (`DriverManager`); ningún `.java` lo importa. Si lo declararas como
`implementation`, estaría disponible en compileJava sin necesidad — más
overhead de classpath.

**Por qué `developmentOnly(devtools)`:** Spring Boot DevTools reinicia la app
en cada cambio durante desarrollo. En el JAR de producción no se quiere ese
comportamiento. `developmentOnly` lo excluye automáticamente del `bootJar`.

### 1.11 Spring Boot Gradle plugin y `bootJar`

El plugin `org.springframework.boot` modifica el build:

- **Reemplaza la task `jar`** por `bootJar` que produce un *fat JAR ejecutable*
  con todas las dependencias adentro (`java -jar app.jar` arranca el servicio).
- Necesita un main class con `@SpringBootApplication` para auto-detectarlo.
- Provee la task `bootRun` para arrancar la app en dev.

El plugin **dependency-management** (`io.spring.dependency-management`) maneja
versiones de starters Spring: vos escribís
`implementation("org.springframework.boot:spring-boot-starter-web")` sin
versión, y el plugin la resuelve usando el BOM de Spring Boot. Eso evita
mismatch de versiones entre starters.

### 1.12 Spring Boot starters

Un "starter" es un POM que agrupa dependencias relacionadas. Los relevantes:

- `spring-boot-starter` — núcleo, contexto, logging, autoconfig.
- `spring-boot-starter-web` — Servlet/Tomcat + Spring MVC (síncrono).
- `spring-boot-starter-webflux` — Netty + Spring WebFlux (reactivo).
- `spring-boot-starter-data-jpa` — Hibernate, JPA, transactions.
- `spring-boot-starter-test` — JUnit, AssertJ, Mockito, Spring Test.
- `spring-boot-starter-actuator` — endpoints `/health`, `/metrics`.

En `melown.spring-service` el convention plugin SOLO incluye `starter` (núcleo
puro) + `starter-test`. Cada servicio decide su flavor de transporte.

### 1.13 Gradle daemon, build cache, configuration cache

- **Daemon:** proceso JVM de larga duración que Gradle deja vivo entre invocaciones
  para evitar startup time. `./gradlew build` la primera vez tarda ~30s; la
  segunda ~5s gracias al daemon.
- **Build cache:** caché de outputs de tasks. Si compilaste con los mismos
  inputs, Gradle reusa el output. Funciona automáticamente.
- **Configuration cache:** caché del grafo de configuración. Evita re-evaluar
  los `build.gradle.kts` cuando no cambiaron. Acelera builds repetidos.

El ADR `0004` activa configuration cache + build cache. Por ahora no lo
habilité explícitamente en este refactor para no agregar fricción (el log
del `gradlew projects` sugiere activarlo — lo hacemos cuando estabilicemos el
setup).

---

## 2. Archivo por archivo

### 2.1 `.gitattributes` (root)

```
/gradlew text eol=lf
*.bat text eol=crlf
*.jar binary
```

**Para qué sirve:** controla cómo Git maneja line endings.

- `/gradlew text eol=lf` — el script Bash del wrapper SIEMPRE tiene line
  endings Unix (`\n`). Si Git lo convirtiera a CRLF en Windows, no correría
  en Mac/Linux ni en containers Linux (CI).
- `*.bat text eol=crlf` — los `.bat` de Windows necesitan `\r\n`.
- `*.jar binary` — Git no intenta detectar text, ni hacer diffs ni line ending
  conversion. JARs son binarios.

Sin esto, Windows haría checkout con CRLF y `./gradlew` fallaría en Linux con
"bad interpreter: /bin/sh^M".

---

### 2.2 `settings.gradle.kts` (root)

```kotlin
pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "melown"

include(
    ":services:catalog",
    ":services:identity",
    ":services:api-gateway",
)
```

**Línea por línea:**

- **`pluginManagement { ... }`** — bloque que configura DE DÓNDE se resuelven
  los plugins (los que aparecen en bloques `plugins {}` de cualquier
  `build.gradle.kts`).
- **`includeBuild("build-logic")`** — declara que `build-logic/` es un
  included build. Su efecto: cuando un módulo hace `id("melown.spring-service")`,
  Gradle busca ese plugin en `build-logic` antes de ir a repos remotos. Esto
  es lo que permite que `:services:catalog/build.gradle.kts` aplique nuestros
  convention plugins por id.
- **`repositories { gradlePluginPortal(); mavenCentral() }`** — para los
  plugins de terceros (Spring Boot, Spotless), Gradle los busca primero en el
  Plugin Portal, después en Maven Central.

- **`dependencyResolutionManagement { ... }`** — bloque que configura DE DÓNDE
  se resuelven las **dependencias** (no plugins).
- **`repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)`** — si un
  módulo declara su propio `repositories {}` en su `build.gradle.kts`, Gradle
  aborta el build. Fuerza centralización: todos los módulos resuelven del
  mismo set de repos. Sin esto, alguien puede agregar un repo en un módulo
  individual y los builds dejan de ser reproducibles (porque otros devs no
  tienen ese repo configurado).
- **`repositories { mavenCentral() }`** — único repo de dependencias. Si
  necesitamos otro (ej. Confluent para Schema Registry libs), se agrega ACÁ,
  no en módulos individuales.

- **`rootProject.name = "melown"`** — nombre del root project. Aparece en
  reportes y como artifact id si publicáramos el root.

- **`include(":services:catalog", ...)`** — declara los subproyectos.
  La sintaxis `":services:catalog"` significa "el subproyecto en el path
  `services/catalog/`". Gradle resuelve el path por convención: dos puntos →
  separador de directorio.

**`:contracts-catalog`** se incluye como path single-segment mapeado al
directorio `contracts/catalog/` vía `project(":contracts-catalog").projectDir`.
El directorio físico mantiene la agrupación bajo `contracts/`.

---

### 2.3 `build.gradle.kts` (root)

```kotlin
// Root build script intentionally minimal.
// Toda configuración compartida vive en convention plugins bajo `build-logic/`.
// Cada módulo declara sus propios plugins/deps en `services/<nombre>/build.gradle.kts`.
```

**Solo comentarios.** Decisión consciente.

Muchos tutoriales muestran:
```kotlin
allprojects {
    group = "com.melown"
    repositories { mavenCentral() }
}
subprojects {
    apply(plugin = "java")
    java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}
```

Eso funciona pero **acopla implícitamente** todos los subproyectos al root. Si
leés `services/catalog/build.gradle.kts` no sabés que se le está aplicando
java + toolchain — eso vive en el root. Hay que saltar entre dos archivos.

Con convention plugins explícitos, cada `build.gradle.kts` del servicio
declara EXPLÍCITAMENTE qué se le aplica (`id("melown.spring-service")`). La
config está completamente derivable desde el archivo del módulo. Mejor para
mantener mental model.

---

### 2.4 `gradle/libs.versions.toml`

```toml
[versions]
spring-boot = "4.1.0"
spring-dependency-management = "1.1.7"
lombok = "1.18.36"
postgresql = "42.7.4"
grpc = "1.68.1"
protobuf = "3.25.5"
avro = "1.12.0"
junit = "5.11.3"
archunit = "1.3.0"
spotless = "6.25.0"

[libraries]
lombok = { module = "org.projectlombok:lombok", version.ref = "lombok" }
postgresql = { module = "org.postgresql:postgresql", version.ref = "postgresql" }

grpc-bom = { module = "io.grpc:grpc-bom", version.ref = "grpc" }
grpc-netty-shaded = { module = "io.grpc:grpc-netty-shaded" }
grpc-protobuf = { module = "io.grpc:grpc-protobuf" }
grpc-stub = { module = "io.grpc:grpc-stub" }
protobuf-java = { module = "com.google.protobuf:protobuf-java", version.ref = "protobuf" }

avro = { module = "org.apache.avro:avro", version.ref = "avro" }

junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
archunit-junit5 = { module = "com.tngtech.archunit:archunit-junit5", version.ref = "archunit" }

plugin-spring-boot = { module = "org.springframework.boot:spring-boot-gradle-plugin", version.ref = "spring-boot" }
plugin-spring-dependency-management = { module = "io.spring.gradle:dependency-management-plugin", version.ref = "spring-dependency-management" }
plugin-spotless = { module = "com.diffplug.spotless:spotless-plugin-gradle", version.ref = "spotless" }

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
spring-dependency-management = { id = "io.spring.dependency-management", version.ref = "spring-dependency-management" }
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
```

**Tres secciones:**

- `[versions]` — strings de versión. La sintaxis `version.ref = "spring-boot"`
  en `[libraries]` y `[plugins]` apunta acá.

- `[libraries]` — coordenadas Maven (`group:artifact:version`). En código se
  accede como `libs.postgresql`, `libs.archunit.junit5` (los guiones del TOML
  se convierten en navegación de propiedades en Kotlin: `archunit-junit5` →
  `archunit.junit5`).

- `[plugins]` — para usar dentro de bloques `plugins { alias(libs.plugins.X) }`
  en `build.gradle.kts` normales.

**Por qué los plugin markers están como `[libraries]` y no `[plugins]`:**

Esto es sutil. Hay dos formas de aplicar un plugin a un módulo:

1. **Bloque `plugins {}`** con `id("...")` o `alias(libs.plugins.X)`. Esto es
   lo que hacés en un `build.gradle.kts` normal de un servicio.
2. **Dependencia** en `build-logic/build.gradle.kts`. Necesario para que los
   convention plugins puedan aplicar otros plugins por id. El convention plugin
   hace `id("org.springframework.boot")`, pero para que esa línea compile
   necesita la clase del plugin en el classpath de compilación de los
   precompiled scripts. Eso se logra agregando el "plugin marker artifact"
   como `implementation(libs.plugin.spring.boot)` en `build-logic/build.gradle.kts`.

Por eso `plugin-spring-boot` aparece en `[libraries]` (consumido como dep) en
vez de en `[plugins]` (consumido como id).

**`grpc-bom`, `protobuf-java`, `avro`:** ya están declarados aunque ningún
servicio los use todavía. Cuando llegue el primer `.proto`/`.avsc`, son
referencia inmediata. Costo de tenerlos declarados pero sin consumir: cero.

---

### 2.5 `build-logic/settings.gradle.kts`

```kotlin
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
```

**Para qué:** `build-logic` es un build Gradle **independiente** del raíz. Su
propio `settings.gradle.kts` configura cómo resuelve sus deps.

- `repositories { gradlePluginPortal(); mavenCentral() }` — los plugin marker
  artifacts vienen de ambos.

- **`versionCatalogs.create("libs").from(files("../gradle/libs.versions.toml"))`**
  — re-registra el version catalog del raíz dentro de build-logic. **Sin esto,
  `libs.*` no existe acá**. El `..` es importante: build-logic es un build
  separado, su working dir es `build-logic/`, así que sube un nivel para
  llegar al catálogo del root.

- `rootProject.name = "build-logic"` — nombre del build.

---

### 2.6 `build-logic/build.gradle.kts`

```kotlin
import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    `kotlin-dsl`
}

val libs = the<LibrariesForLibs>()

dependencies {
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))

    implementation(libs.plugin.spring.boot)
    implementation(libs.plugin.spring.dependency.management)
    implementation(libs.plugin.spotless)
}
```

**Plugin `kotlin-dsl`:**
- Habilita escribir convention plugins como `.gradle.kts` (Kotlin script).
- Activa la task `generatePrecompiledScriptPluginAccessors` que compila los
  scripts de `src/main/kotlin/` a clases JVM con plugin ids derivados del
  filename.
- Sin este plugin, los archivos `melown.*.gradle.kts` no harían nada.

**`val libs = the<LibrariesForLibs>()`:**
- `the<T>()` es una función de Gradle que devuelve la extensión registrada de
  tipo `T` en el proyecto actual. Es equivalente a
  `project.extensions.getByType(T::class.java)`.
- `LibrariesForLibs` es una clase **generada por Gradle** a partir del version
  catalog. Tiene una propiedad por library del TOML.
- Esto hace que en este archivo podamos escribir `libs.plugin.spring.boot` y
  Kotlin lo type-checke en tiempo de compilación del build script.

**`implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))`:**

Este es el **workaround feo pero estándar** que el ADR `0004` no podía
anticipar y que apareció apenas corrimos el primer build. Lo desempacamos a
fondo en la sección 3 (sobre el error).

Resumen: el accessor `libs.*` está disponible en este archivo (gracias al
import + `the<>()`), pero NO en los precompiled scripts de
`src/main/kotlin/melown.*.gradle.kts` por default. La línea agrega al
classpath el JAR donde Gradle generó la clase `LibrariesForLibs`, exponiéndola
a los precompiled scripts.

**`implementation(libs.plugin.spring.boot)` etc:**
- Plugin marker artifacts como dependencias normales.
- Sin esto, los convention plugins no podrían hacer `id("org.springframework.boot")`
  porque la clase del plugin no estaría en su classpath de compilación.

---

### 2.7 `build-logic/src/main/kotlin/melown.java-base.gradle.kts`

```kotlin
import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    java
    id("com.diffplug.spotless")
}

group = "com.melown"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val libs = the<LibrariesForLibs>()

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        ktlint()
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn("spotlessCheck")
}
```

**`plugins { java; id("com.diffplug.spotless") }`:**
- Aplica el plugin core `java` (compilación Java).
- Aplica Spotless. La sintaxis `id("...")` (no `id("...") version "X"`) es
  porque la versión ya está fijada por la dependencia del plugin marker en
  `build-logic/build.gradle.kts`. Si pusieras `version "X"` acá, Gradle
  tiraría conflicto.

**`group = "com.melown"; version = "0.0.1-SNAPSHOT"`:**
- Toda subproyecto que aplique este convention plugin obtiene este `group` y
  `version`. Si publicáramos el JAR, esas son las coordenadas Maven.
- `-SNAPSHOT` indica "versión en desarrollo, mutable".

**`java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }`:**
- Ya explicado en 1.6. Java 21 garantizado para todo módulo que aplique este
  plugin.

**`dependencies { testImplementation(...) ... }`:**
- `platform(libs.junit.bom)` — el JUnit BOM (Bill of Materials) define
  versiones consistentes de todos los artifacts JUnit. `platform(...)` lo
  importa sin agregar deps reales. Después podés escribir
  `testImplementation("org.junit.jupiter:junit-jupiter")` sin versión y el
  BOM la resuelve.
- `testImplementation("org.junit.jupiter:junit-jupiter")` — JUnit 5 runtime.
- `testImplementation(libs.archunit.junit5)` — ArchUnit con integración JUnit 5.
- `testRuntimeOnly("org.junit.platform:junit-platform-launcher")` — el
  "launcher" que JUnit Platform usa para descubrir y correr tests. Spring Boot
  4.x requiere declararlo explícitamente.

**`spotless { java { ... } kotlinGradle { ... } }`:**
- `target("src/**/*.java")` — qué archivos formatear.
- `googleJavaFormat()` — convención de Google. Opinionado.
- `removeUnusedImports()`, `trimTrailingWhitespace()`, `endWithNewline()` —
  reglas adicionales.
- `kotlinGradle { target("**/*.gradle.kts") ktlint() }` — formatea también los
  scripts Kotlin del build.

**`tasks.withType<Test>().configureEach { useJUnitPlatform() }`:**
- Sin esto, Gradle usa JUnit 4 por default. Habilita JUnit 5.

**`tasks.named("check") { dependsOn("spotlessCheck") }`:**
- La task `check` es la "all checks" que CI corre típicamente
  (`./gradlew check`). Por default verifica tests. Con esta línea también
  verifica formato. Si Spotless detecta código mal formateado, `check` falla.

---

### 2.8 `build-logic/src/main/kotlin/melown.spring-service.gradle.kts`

```kotlin
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
```

**`id("melown.java-base")`** — **composición de plugins**. Cuando un módulo
aplica `melown.spring-service`, automáticamente se le aplica `melown.java-base`
también. Toolchain, Spotless, JUnit, ArchUnit vienen "gratis" sin volver a
declararlos.

**`id("org.springframework.boot")` + `id("io.spring.dependency-management")`:**
Los dos plugins que necesita un servicio Spring. El primero crea `bootJar`,
`bootRun`. El segundo importa el BOM de Spring Boot que define versiones
consistentes de todos los starters.

**`implementation("org.springframework.boot:spring-boot-starter")`:**
Starter núcleo: Spring context, logging (Logback por default), autoconfig.
Sin esto, las anotaciones `@SpringBootApplication` no significarían nada.

**Sin starter de transporte.** El convention plugin NO incluye `starter-web`
ni `starter-webflux`. Cada servicio decide su flavor (síncrono vs reactivo).

**Lombok en 4 scopes:**
- `compileOnly` + `annotationProcessor` — para el código de `src/main`.
- `testCompileOnly` + `testAnnotationProcessor` — para el código de `src/test`.

Necesario duplicarlo porque los scopes de test son independientes de los de
main. Si solo declarás `compileOnly(libs.lombok)`, los tests no ven las
anotaciones.

**`testImplementation("...spring-boot-starter-test")`:**
Incluye JUnit Jupiter, Spring Test, AssertJ, Mockito, JsonAssert. Todo el
toolkit típico de tests de Spring.

---

### 2.9 `services/catalog/build.gradle.kts`

```kotlin
plugins {
    id("melown.spring-service")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly(libs.postgresql)
    developmentOnly("org.springframework.boot:spring-boot-devtools")
}
```

**5 líneas funcionales.** Comparalo con el `catalog-service/build.gradle.kts`
original (35+ líneas). Toda la repetición vive en el convention plugin.

- `id("melown.spring-service")` — aplica toda la cadena: java-base + Spring
  Boot + dependency-management + Spotless + Lombok + ArchUnit + JUnit.
- `starter-data-jpa` — Hibernate, JPA, Spring Data JPA, transactions.
- `runtimeOnly(libs.postgresql)` — el driver JDBC, solo en runtime.
- `developmentOnly(devtools)` — restart automático en dev.

---

### 2.10 `services/identity/build.gradle.kts`

```kotlin
plugins {
    id("melown.spring-service")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
}
```

Web tradicional (servlet/Tomcat). El ADR `0008` define identity como
Spring Authorization Server embebido — esas deps se sumarán cuando se
implemente.

---

### 2.11 `services/api-gateway/build.gradle.kts`

```kotlin
plugins {
    id("melown.spring-service")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
}
```

**Por qué webflux:** Spring Cloud Gateway está construido sobre WebFlux
(stack reactivo, Netty). No es opcional. Más adelante, cuando agreguemos
`spring-cloud-starter-gateway`, ya hereda el contexto reactivo.

---

### 2.12 Application classes y application.yaml de cada servicio

#### `services/catalog/src/main/java/com/melown/catalog/CatalogApplication.java`

```java
package com.melown.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CatalogApplication {
    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
```

**`@SpringBootApplication`:** meta-anotación que combina:
- `@SpringBootConfiguration` — esta clase es fuente de bean definitions.
- `@EnableAutoConfiguration` — Spring escanea el classpath y configura
  automáticamente lo que encuentra (si hay JPA + Postgres → setup DataSource).
- `@ComponentScan` — busca beans (`@Service`, `@Repository`, `@Controller`)
  en este package y sub-packages.

**Por qué la clase está en `com.melown.catalog`** y los demás packages
(`com.melown.catalog.domain`, `com.melown.catalog.infrastructure`) son
sub-packages: el `@ComponentScan` default escanea desde el package de la
clase con `@SpringBootApplication` hacia abajo. Si pusieras `CatalogApplication`
en `com.melown.catalog.bootstrap`, los beans de
`com.melown.catalog.infrastructure` no se descubrirían automáticamente.

#### `services/catalog/src/main/resources/application.yaml`

```yaml
spring:
  application:
    name: catalog
```

`spring.application.name` se usa para:
- Logs (aparece como `[catalog]` en cada entrada).
- Discovery, tracing (OTel lo usa como `service.name`).

Antes decía `catalog-service`. Lo cambié a `catalog` para alinear con el path
del módulo `:services:catalog` y el package `com.melown.catalog`.

#### Equivalentes para `identity` y `api-gateway`

Ambos tienen la misma forma con `name: identity` / `name: api-gateway` y un
`server.port` distinto para evitar conflicto si los corremos juntos en local
(catalog: 8080 default, identity: 8081, api-gateway: 8080 — vamos a ajustar
en Fase 1 con la decisión final de puertos).

---

### 2.13 `contracts/catalog/README.md`

```markdown
# contracts/catalog

Contratos públicos del servicio `catalog`. Versionados como ciudadanos de primera
en el monorepo (ADR `0004`).

- `proto/` — IDLs gRPC (`.proto`) — ver ADR `0002`.
- `avro/` — schemas de eventos Kafka (`.avsc`) — ver ADR `0001`.

Aún vacío. Se completa cuando el primer slice vertical de Fase 1 defina el
contrato de `CreateProduct` (gRPC) y `ProductCreated` (Avro).
```

**Por qué solo un README:** el ADR `0004` define que `contracts/` es un
ciudadano de primera, pero sin IDLs no hay nada que construir todavía. El
README marca el lugar y referencia los ADRs que lo gobiernan. Cuando llegue
el primer `.proto`, se suma `contracts/catalog/build.gradle.kts` aplicando un
convention plugin futuro `melown.contracts` (con protoc + avro-tools) y se
agrega al `include()` de `settings.gradle.kts`.

---

## 3. El error que apareció (workaround `LibrariesForLibs`)

Cuando corrí `./gradlew projects` por primera vez, falló con:

```
e: melown.java-base.gradle.kts:6:19 Unresolved reference 'accessors'.
e: melown.java-base.gradle.kts:22:12 Unresolved reference. None of the following candidates is applicable...
e: melown.java-base.gradle.kts:22:16 Unresolved reference 'LibrariesForLibs'.
e: melown.java-base.gradle.kts:25:38 Unresolved reference 'junit'.
e: melown.java-base.gradle.kts:27:29 Unresolved reference 'archunit'.
```

**El problema:** `LibrariesForLibs` es una clase generada por Gradle a partir
del version catalog. Está disponible cuando compilás un `build.gradle.kts`
normal (porque Gradle se ocupa de agregarla al classpath). **Pero NO está
disponible cuando se compilan los precompiled script plugins** (los
`.gradle.kts` dentro de `build-logic/src/main/kotlin/`).

¿Por qué? Los precompiled scripts se compilan con un classpath construido a
partir de `build-logic/build.gradle.kts → dependencies`. Si la dependencia
`LibrariesForLibs` no está ahí, los scripts no la ven.

**El fix:**

```kotlin
// En build-logic/build.gradle.kts
dependencies {
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
    // ... resto
}
```

**Qué hace, paso a paso:**

1. `libs` es la instancia de `LibrariesForLibs` que Gradle inyecta en el
   `build.gradle.kts` de build-logic (porque registramos el catálogo en su
   settings).
2. `libs.javaClass` → la clase de esa instancia (`LibrariesForLibs`).
3. `.superclass` → su superclase (es importante; el accessor real lo expone la
   superclase generada).
4. `.protectionDomain.codeSource.location` → la URL del JAR donde esa clase
   vive (en alguna carpeta dentro de `~/.gradle/caches/`).
5. `files(...)` → convierte esa URL en un `FileCollection` que Gradle entiende
   como dependencia.
6. `implementation(...)` → suma ese JAR al classpath de compilación.

Resultado: los precompiled scripts ven `LibrariesForLibs` y pueden hacer
`the<LibrariesForLibs>()` para acceder al catálogo.

Es feo. Está documentado en
[issue #15383 de Gradle](https://github.com/gradle/gradle/issues/15383). La
propuesta es hacer esto nativo, pero todavía no salió en Gradle 9.5.1.

**Alternativas que descarté:**

- Hardcodear coordenadas en los convention plugins (`"com.tngtech.archunit:archunit-junit5:1.3.0"`).
  Funcionaría sin el workaround, pero **rompe la regla de "única fuente de
  versiones"** del ADR `0004`. Drift garantizado.
- Usar la API `project.extensions.getByType<VersionCatalogsExtension>().named("libs")`.
  Es más portable pero pierde type-safety: `libs.findLibrary("archunit-junit5").get()`
  vs `libs.archunit.junit5`. Más string-magic, menos error en compile-time.

---

## 4. Validación

### `./gradlew projects`

```
Root project 'melown'
\--- Project ':services'
     +--- Project ':services:api-gateway'
     +--- Project ':services:catalog'
     \--- Project ':services:identity'

Included builds:
\--- Included build ':build-logic'

BUILD SUCCESSFUL in 58s
```

Confirma que:
- El monorepo cablea bien.
- Los tres subproyectos se descubren desde `settings.gradle.kts`.
- `build-logic` se ejecuta como included build.
- `:services` aparece como proyecto "contenedor" vacío (Gradle infiere su
  existencia al ver paths nested).

### `./gradlew :services:catalog:compileJava`

```
> Task :services:catalog:compileJava

BUILD SUCCESSFUL in 46s
```

Esto valida bastante más:
- El convention plugin `melown.spring-service` se aplicó correctamente.
- La cadena `melown.spring-service` → `melown.java-base` funcionó (Java 21
  toolchain disponible).
- Spring Boot 4.1.0 + dependency-management resolvieron desde Maven Central.
- `starter-data-jpa` (`jakarta.persistence.*`) está en classpath → `ProductEntity`
  compila.
- Lombok como `compileOnly + annotationProcessor` no rompe (aunque ningún
  archivo lo usa todavía).
- El código de `Product`, `Auditory`, `ProductId`, `Specifications` compila
  bajo el nuevo package `com.melown.catalog.*`.

---

## 5. Trazabilidad: cada cosa del ADR `0004` ↔ archivo que la implementa

| Decisión del ADR `0004` | Dónde se implementó |
|---|---|
| Layout `services/`, `libs/`, `contracts/`, `build-logic/` top-level | Estructura de carpetas |
| `settings.gradle.kts` con `includeBuild("build-logic") + include()` | `settings.gradle.kts` (root) |
| `gradle/libs.versions.toml` como única fuente de versiones | `gradle/libs.versions.toml` |
| `build-logic/` como included build (NO `buildSrc/`) | `build-logic/settings.gradle.kts`, `pluginManagement` del root |
| Convention plugin `melown.java-base` (toolchain, JUnit, ArchUnit, Spotless) | `build-logic/src/main/kotlin/melown.java-base.gradle.kts` |
| Convention plugin `melown.spring-service` (extiende java-base + Spring) | `build-logic/src/main/kotlin/melown.spring-service.gradle.kts` |
| Naming: plugins `melown.<tipo>`, módulos `:services:<nombre>` | Filenames + `settings.gradle.kts` |
| Group ID `com.melown.<modulo>` | `group = "com.melown"` en `melown.java-base` |
| Kotlin DSL en todos los build scripts | Sí, todos `.kts` |
| ArchUnit integrado en `java-base` desde el primer servicio | Dependencia agregada. Reglas concretas pendientes (ver Deuda) |
| Spotless como única herramienta de quality en Fase 1 | Aplicado en `melown.java-base` |
| Build cache + configuration cache activos | Pendiente (logs sugieren activar configuration cache; lo hacemos cuando estabilicemos) |

| Decisión NO implementada todavía | Por qué |
|---|---|
| `libs/` (common, observability) | YAGNI hasta que aparezca el primer caso de reuso real |
| `melown.quarkus-service` plugin | YAGNI hasta Fase 2+ (cuando se sume `notifications`) |
| `melown.contracts` plugin (con protoc + Avro) | Hasta que `contracts/catalog/` tenga IDLs reales |
| Plugin `allowed-dependencies` que enforcee reglas entre módulos | Pendiente; se evalúa cuando haya 3+ módulos con deps cruzadas |

---

## 6. Renames de package y clase

| Antes | Después | Razón |
|---|---|---|
| `com.melown.catalog_service` | `com.melown.catalog` | Java packages no usan underscores. ADR `0004` define group ID como `com.melown.<modulo>`. El módulo es `catalog`. |
| `CatalogServiceApplication.java` | `CatalogApplication.java` | "CatalogService Service" es redundante. |
| `CatalogServiceApplicationTests.java` | `CatalogApplicationTests.java` | Idem. |
| `application.yaml` valor `name: catalog-service` | `name: catalog` | Alineado con `:services:catalog` y el package. |
| `com.melown.api_gateway` (no existía) | `com.melown.apigateway` | El módulo se llama `api-gateway` pero Java packages no aceptan guiones. Convención Java: minúsculas sin separadores. |

---

## 7. Wrapper único en root — qué implica

Gradle wrapper son **4 archivos**:
- `gradlew` (script Bash, ejecutable, LF).
- `gradlew.bat` (script Windows, CRLF).
- `gradle/wrapper/gradle-wrapper.jar` (descarga la distribución Gradle si no
  está en `~/.gradle/wrapper/dists/`).
- `gradle/wrapper/gradle-wrapper.properties` (qué versión bajar — acá Gradle
  9.5.1).

En un build multi-proyecto **debe haber UN wrapper en el root**. Subproyectos
no necesitan el suyo: `./gradlew :services:catalog:build` desde el root
funciona perfecto.

Movimos los archivos de `catalog-service/` al root y borramos los del
subproyecto.

---

## 8. Deuda consciente — qué quedó sin tocar

Vos pediste "voy de a poco". Lo que NO toqué:

1. **`Auditory` se llama así** (typo del inglés: "auditory" = "auditivo,
   relativo al oído"). Lo correcto es `Auditable`.
2. **`java.util.Date`** sigue en `Product` y `Auditory`. En Java 21 el tipo
   correcto es `Instant` (sin zona) o `OffsetDateTime` (con zona). `Date` es
   mutable, no thread-safe y semánticamente confuso ("es una fecha o un
   timestamp?"). El ADR `0004` define una regla ArchUnit "no usar `java.util.Date`"
   — la dependencia ArchUnit está agregada en `melown.java-base`, pero la
   regla NO está escrita todavía porque el código actual la violaría y rompería
   el build.
3. **`Specifications` record vacío.** No tiene fields. Se completa cuando
   modelemos qué atributos diferencia a un producto de otro (categoría, marca,
   tags, atributos arbitrarios).
4. **`Product` sin método factory.** Constructor es privado pero no hay
   `Product.create(...)` público. No se puede instanciar. Eso es bueno
   temporalmente (fuerza decidir el contrato) pero tiene que resolverse antes
   del primer use case.
5. **`Product` sin invariantes ni getters.** No valida nada al crearse, no
   expone su estado. En su forma actual es un struct ilegible.
6. **`ProductEntity` desconectado del dominio.** No hay puerto (`ProductRepository`)
   en `domain/port/out/`, no hay mapper. El entity JPA cuelga sin conectarse a
   nada.
7. **No hay `ArchitectureTest.java`** con las reglas de ArchUnit corriendo
   contra el código. Cuando refactores los puntos 1-6 (dominio limpio),
   escribimos las reglas y entran como gate de CI.

Cuando quieras encarar el dominio, el orden razonable es:

1. Renombrar `Auditory` → `Auditable`, cambiar `Date` → `Instant`.
2. Definir `Specifications` (qué fields tiene).
3. Métodos factory + invariantes en `Product`.
4. Definir el puerto `ProductRepository` en `com.melown.catalog.domain.port.out`.
5. Implementar el adapter JPA que ya existe (`ProductEntity`) + un
   `JpaProductRepository` que implementa el puerto.
6. Escribir `ArchitectureTest.java` con las reglas base.
7. Activar la regla "no Date" del ADR (ahora sí compatible con el código).

---

## 9. Glosario rápido (referencia)

| Término | Definición corta |
|---|---|
| Monorepo | Un solo repo con múltiples módulos/servicios. |
| Multi-proyecto Gradle | Build raíz que `include()` subproyectos. |
| Included build | Build Gradle separado, "incluido" por el raíz vía `includeBuild()`. |
| Convention plugin | Plugin Gradle escrito como `.gradle.kts` que comparte config entre módulos. |
| Precompiled script plugin | Sinónimo de convention plugin escrito en Kotlin DSL. |
| Version catalog | Archivo TOML (`libs.versions.toml`) con versiones de deps centralizadas. |
| Plugin marker artifact | Artifact Maven cuyo único propósito es declarar la coordenada real de un plugin. Nombre: `<plugin-id>:<plugin-id>.gradle.plugin:<version>`. |
| Toolchain (Java) | El JDK que Gradle usa para compilar/correr. Se gestiona automáticamente. |
| BOM (Bill of Materials) | POM Maven que declara versiones consistentes de un set de artifacts. |
| Annotation processor | Código que corre durante `javac` para procesar anotaciones y generar código. |
| Configuration scope | El "bucket" donde declarás una dep: `implementation`, `runtimeOnly`, etc. |
| `bootJar` | Task del plugin Spring Boot que crea un fat JAR ejecutable. |
| Auto-configuration | Mecanismo de Spring Boot que setea beans automáticamente basado en classpath. |
| Component scan | Spring escanea packages para encontrar beans (`@Service`, etc.). |
| ArchUnit | Librería para tests de reglas arquitectónicas en código Java. |
| Spotless | Plugin para formateo automático de código (Java, Kotlin, etc.). |
| `googleJavaFormat` | Convención de formato de Google para Java (Spotless la implementa). |
| ktlint | Linter de formato para Kotlin. |
| Gradle daemon | Proceso JVM persistente entre builds para evitar startup time. |
| Configuration cache | Caché del grafo de configuración de Gradle. |
| Build cache | Caché de outputs de tasks de Gradle. |

---

## 10. Próximos pasos

De la secuencia propuesta para Fase 1, los pasos 1 y 2 están cerrados.
Lo que sigue:

3. `docker-compose.yml` baseline (Postgres ×3, Redpanda + Schema Registry,
   OTel Collector, Tempo, Prometheus, Loki, Grafana).
4. **Refactor del dominio `Product`** (Auditable + Instant + factory + port +
   ArchUnit activado). Recomendado antes del paso 3 para que las reglas
   ArchUnit puedan activarse.
5. Slice vertical sin outbox: REST en gateway → gRPC contra catalog → INSERT
   en Postgres → respuesta + traza visible en Tempo.
6. Outbox + custom polling publisher + consumidor dummy (ADR `0007` definió
   esto).
7. `identity` con `client_credentials` ES256 (ADR `0008`).

Tres decisiones abiertas siguen en pie:
- Alcance del slice vertical de Fase 1 (qué endpoints, qué evento).
- Alcance de `identity` (recomendación: client_credentials únicamente).
- Kafka vs Redpanda en Compose (recomendación: Redpanda).
