# 0002. Usar gRPC para RPC interno entre servicios

- **Status:** Accepted
- **Date:** 2026-06-12
- **Deciders:** @nwnorowsky

## Contexto

Los microservicios del proyecto se comunican por dos caminos: **asíncrono** vía Kafka (ya decidido en ADR `0001`: Avro + Schema Registry) y **síncrono** entre servicios — el camino que este ADR define. La pregunta es qué tecnología usar para ese camino síncrono interno.

Las fuerzas en juego:

- **Latencia y throughput.** El RPC interno está en el camino crítico de requests del usuario (ej. `checkout` consulta `catalog` e `inventory` antes de confirmar). Cada hop síncrono suma a la latencia p99 de la operación pública.
- **Tipado y code-gen.** Equipo chico aprendiendo: los contratos típados con generación de stubs reducen errores de integración y hacen visible un cambio de contrato como un cambio de código.
- **Multi-lenguaje a futuro.** El stack default es Java/Spring, pero un servicio (`notifications`) es Quarkus y eventualmente puede haber piezas en Go o Python (sidecars, herramientas). El mecanismo elegido debe ser cómodo en al menos JVM y otros lenguajes mainstream.
- **Streaming y cancelación.** Algunos casos del dominio se benefician de streaming (ej. sincronización de cambios, paginación push, telemetría de larga duración). La cancelación de requests propagada río abajo es importante para no desperdiciar trabajo cuando el cliente cuelga.
- **Observabilidad.** Tracing (W3C / OpenTelemetry), métricas RED, correlation IDs — todo debe propagar por defecto, sin reinventar la rueda en cada servicio.
- **Frontera interna vs frontera pública.** Lo que hablen los servicios *entre sí* es distinto de lo que expongan al exterior (browser, mobile, terceros). Mezclar las dos fronteras complica seguridad, versionado y debug.

Restricciones del proyecto:

- Kubernetes como target (Helm + kind en local), donde el load balancing L7 y los service meshes son ciudadanos de primera.
- OpenTelemetry para tracing/métricas/logs.
- Objetivo educativo: el mecanismo elegido debe enseñar prácticas que se ven en compañías serias, aunque tenga curva.

## Decisión

Vamos a usar **gRPC sobre HTTP/2 con Protobuf** como mecanismo de RPC síncrono entre servicios internos.

**REST sigue siendo el formato de la frontera pública** (BFF / API gateway hacia browser, mobile, terceros). Esa frontera no es alcance de este ADR — solo se aclara para dejar explícito que la elección de gRPC es *interna*.

Se permite REST interno como excepción puntual cuando un servicio expone un endpoint para herramientas de operaciones (`/actuator`, health, debug) o cuando integra con un sistema legacy que no habla gRPC. Esas excepciones no requieren ADR individual, pero sí justificación en el PR.

## Consecuencias

### Positivas

- **HTTP/2 multiplexing.** Varias llamadas concurrentes comparten una conexión TCP sin head-of-line blocking. En patrones tipo fan-out (un request del usuario que pega a tres servicios en paralelo) hay ganancia real de latencia y de uso de sockets vs HTTP/1.1.
- **Wire binario (Protobuf) + framing eficiente.** Payloads más chicos que JSON y (de)serialización ~5–10× más rápida. A volumen alto, esto se nota en CPU y en latencia p99.
- **IDL único para servicios y mensajes.** El `.proto` define `rpc methods` además de los tipos. El contrato del *servicio* (qué llamadas existen, qué devuelven, qué excepciones) es código revisado, no un PDF.
- **Code-gen tipado en Java** (y otros lenguajes) con stubs cliente y servidor. Cambiar un campo del contrato rompe el build de los consumers, no un test de integración tardío.
- **Streaming nativo** (server, client, bidi). Cuando aparezca el caso, no hay que reinventar SSE o long-polling.
- **Deadlines y cancelación propagados.** `Context.deadline` viaja con la llamada; si el cliente se va o se vence, todos los servicios río abajo cortan. Evita "trabajo zombie".
- **Interceptores nativos** para auth, tracing OpenTelemetry, métricas, retries, logging — están en el ecosistema gRPC-Java y se conectan sin código custom.
- **Status codes específicos del RPC** (`NOT_FOUND`, `INVALID_ARGUMENT`, `DEADLINE_EXCEEDED`, `UNAVAILABLE`, `RESOURCE_EXHAUSTED`) que mapean mejor a fallos de servicio que los status HTTP. `RESOURCE_EXHAUSTED` es lo que querés disparar para Resilience4j rate limiter; "429 Too Many Requests" se parece pero no es exactamente lo mismo semánticamente.

### Negativas

- **Curva de aprendizaje.** El modelo de errores (status codes de gRPC ≠ HTTP), el lifecycle de los interceptores, los deadlines, la diferencia entre `blocking stub`, `async stub` y `future stub` — todo se aprende. Como Jr, hay que aceptar que las primeras semanas habrá fricción.
- **Debugging ad-hoc peor que REST.** No hay `curl` ni Postman como primer recurso. Hay alternativas (`grpcurl`, BloomRPC, Postman ya soporta gRPC), pero la barrera para "probar rápido un endpoint" es más alta. Mitigación: configurar `grpcurl` en el `Makefile`/`Justfile` del repo desde Fase 1.
- **Load balancing L7 obligatorio.** HTTP/2 reusa una conexión TCP larga, así que un L4 round-robin clásico de Kubernetes Services *no balancea* las llamadas gRPC — todas terminan en el mismo pod. Hay que usar headless services + client-side LB (`grpc-java` lo soporta) o un service mesh (Linkerd/Istio). Esto agrega complejidad operativa real en Fase 2/3.
- **Reglas de evolución específicas de Protobuf** que el equipo tiene que aprender: nunca reutilizar tag numbers, todos los campos opcionales por convención, usar `reserved` cuando se borra un campo. Errores acá se manifiestan como bugs sutiles en consumers viejos.
- **Compatibilidad con browsers requiere gRPC-Web** (un proxy de traducción). Por eso REST se queda en la frontera pública — pero esto es una limitación a recordar si alguna vez aparece la tentación de servir gRPC al browser.
- **Schema central para Protobuf.** A diferencia de Avro con Schema Registry, no hay un registry runtime de `.proto`s — los contratos se distribuyen como artefactos (módulo Gradle compartido o paquete publicado). Hay que diseñar el monorepo para que el cambio de un `.proto` rompa el build de los consumers; eso es alcance del ADR pendiente sobre layout del monorepo.

## Alternativas consideradas

### Alternativa A: REST + OpenAPI también para RPC interno

Usar REST/JSON con contratos definidos en OpenAPI y generación de clientes vía `openapi-generator`.

**Por qué perdió:**
- **Generators inconsistentes.** El ecosistema OpenAPI tiene N generators Java con calidad muy variable; el output suele necesitar fixes manuales o templates custom para ser idiomático con Spring. gRPC-Java es el generator único oficial y mantenido por Google.
- **Sin streaming real.** SSE y WebSockets existen pero no están integrados al modelo de contrato OpenAPI ni a los stubs generados.
- **JSON overhead** en el path crítico interno (CPU + ancho de banda) no aporta nada cuando los dos extremos son servicios nuestros que no necesitan legibilidad humana del wire.
- **Sin deadlines/cancelación nativos.** Hay que reimplementarlos vía headers convencionales y middleware.
- **HTTP/1.1 head-of-line blocking** o HTTP/2 sin las ventajas del framing binario.
- El argumento fuerte de REST — *cualquier herramienta lo entiende* — solo importa en la frontera pública, donde sí lo vamos a usar.

### Alternativa B: REST + gRPC mezclados según el caso por servicio

Permitir que cada servicio elija REST o gRPC según preferencia / caso de uso.

**Por qué perdió:**
- **Doble stack en cada cliente.** Cada servicio que llama a otros tiene que soportar las dos cadenas de interceptors, tracing, retries, auth — duplicación operativa real.
- **Sin default fuerte, la decisión se toma N veces** y la consistencia se erosiona. Para un proyecto educativo donde el objetivo es aprender prácticas comunes, "depende" enseña mal.
- La salida correcta es **un default claro (gRPC) + excepciones justificadas** (ops endpoints, integración legacy), que es lo que adopta esta decisión.

### Alternativa C: GraphQL para RPC interno

Usar GraphQL como protocolo síncrono entre servicios.

**Por qué perdió:**
- GraphQL resuelve un problema distinto: **shape de query dictado por el cliente** y federación de schemas para clientes con necesidades de datos variadas. Eso es valioso en una **BFF/edge** (browser que quiere armar una vista con datos de varios servicios), no entre servicios donde la operación es de granularidad fija.
- Performance peor que gRPC para llamadas service-to-service (resolvers anidados, N+1, parsing de query strings en cada hop).
- Sin contrato de operación tan rígido como `rpc method (Request) returns (Response)` — más libertad significa más superficie para que un cliente abuse de un servicio.
- Si en el futuro aparece un caso BFF para mobile que se beneficie de GraphQL, se evalúa **en esa capa**, no en el RPC interno.

### Alternativa D: Request/reply sobre Kafka

Usar Kafka como transporte síncrono también, con topics de reply correlacionados por header.

**Por qué perdió:**
- **Latencia.** Kafka está optimizado para throughput, no para round-trip de pocos milisegundos. Un request/reply atraviesa producer → broker → consumer → producer → broker → consumer; típicamente decenas a cientos de ms en el mejor caso.
- **Operacionalmente raro.** Tracing, correlación, deadlines, manejo de respuestas perdidas — todo se reinventa. Kafka es excelente para eventos; forzarlo a sync mezcla dos modelos mentales.
- Kafka se queda donde brilla: el camino asíncrono entre servicios (ADR `0001`).

### Alternativa E: Apache Thrift

Usar Thrift (Facebook) como IDL + RPC binario.

**Por qué perdió:**
- **Ecosistema más chico** y menos activo que gRPC. Menos integraciones con OpenTelemetry, Resilience4j, Spring Boot starters, service meshes.
- **Sin HTTP/2 nativo** en el transporte default (Thrift tiene varios transportes, ninguno tan estándar como HTTP/2). Pierde las ventajas de multiplexing modernas.
- Si lo elegimos, todo el tooling alrededor (load balancers, debugging, generators de docs) es de segunda clase comparado con gRPC. No hay ganancia técnica que compense.

## Referencias

- `docs/README.md` — sección de stack objetivo (gRPC + Protobuf interno).
- ADR `0001` — Protobuf para gRPC y Avro para Kafka (decisión downstream de esta).
- gRPC docs — [Core concepts: deadlines](https://grpc.io/docs/guides/deadlines/), [status codes](https://grpc.io/docs/guides/status-codes/).
- Sam Newman, *Building Microservices* (2nd ed.), cap. 5 — comparación de protocolos de comunicación inter-servicio.
- ADR pendiente: layout del monorepo Gradle (cómo se distribuyen los `.proto` compartidos entre módulos).
