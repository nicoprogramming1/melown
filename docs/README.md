# Melown — Proyecto de aprendizaje en microservicios e-commerce

Un marketplace al estilo MercadoLibre construido como un sistema de microservicios con persistencia políglota y orientado a eventos. El objetivo no es solamente que el código funcione, sino **entender y justificar cada decisión arquitectónica** del camino — pasando de un perfil Jr-Avanzado hacia un razonamiento Sr/Staff.

## Filosofía

**Primero pensar como arquitecto, después escribir código.** Diagramas, decisiones y contratos en papel antes que la implementación. Cada fase termina con la capacidad de explicar *por qué* se tomó una decisión y *qué alternativa se descartó*.

Stack: **Java 21, Spring Boot, Kafka, Elasticsearch, gRPC, PostgreSQL, Redis, Docker, Kubernetes, OpenTelemetry**.

---

## El sistema de un vistazo

### Servicios de dominio
Cada servicio es dueño de su base de datos y sus eventos. Ningún servicio lee la DB de otro.

| Servicio | De qué es dueño | Patrones clave |
|---|---|---|
| `identity` | usuarios, vendedores, auth | OAuth2/OIDC, JWT, refresh tokens |
| `catalog` | productos, categorías, atributos | Diseño de agregados, locking optimista |
| `inventory` | stock por SKU/depósito | Reservas, consistencia eventual |
| `search` | proyección read-only sobre ES | CDC + indexer en Kafka (la API no escribe) |
| `pricing` | precio base, promociones | Desacoplado del catálogo |
| `cart` | carritos por sesión/usuario | Respaldado en Redis, operaciones idempotentes |
| `orders` | ciclo de vida de la orden | **Orquestador de Saga** (la joya de la corona) |
| `payments` | pagos tokenizados | Provider mockeado, claves de idempotencia |
| `shipping` | cotizaciones, tracking | Carrier mockeado |
| `reviews` | ratings, comentarios | Pipeline de moderación |
| `notifications` | mail / push | Consumidor puro, sin API pública — **construido en Quarkus** (ver trade-offs) |

### Componentes transversales
- `api-gateway` — Spring Cloud Gateway. REST hacia afuera, rate-limit y auth en el borde.
- `bff-web` — Backend For Frontend (servicio backend, sin UI). Expone **un endpoint GraphQL** para la PDP (product detail page), donde aplana el fan-out de `catalog` + `pricing` + `inventory` + `reviews`. Sin frontend: se prueba contra el playground (GraphiQL / Apollo Sandbox) que viene de fábrica.
- **Comunicación entre servicios:** gRPC + Protobuf para lo sincrónico, Kafka + Avro (Schema Registry) para lo asincrónico.
- **Patrón Outbox** con Debezium — elimina el problema de la doble escritura.
- **Observabilidad:** OpenTelemetry → Tempo (trazas) + Prometheus (métricas) + Loki (logs) + Grafana.
- **Resiliencia:** Resilience4j (circuit breaker, retry, bulkhead, timeout).
- **Secretos:** Vault (local) → AWS Secrets Manager (en la nube).

---

## Plan por fases

Cada fase tiene un eje de aprendizaje. El orden importa.

### Fase 0 — Arquitectura en papel *(1 semana, cero código)*
- Diagramas C4 (Context, Container, Component) en Structurizr DSL (texto → diagramas, versionable).
- Event Storming: eventos de dominio, comandos, agregados → bounded contexts.
- ADRs (Architecture Decision Records) para: monorepo vs polirepo, gRPC vs REST puertas adentro, Avro vs Protobuf, Saga vs 2PC, etc.
- **Entregable:** una carpeta `docs/` que un extraño pueda leer y entender el sistema.

### Fase 1 — Walking skeleton *(1–2 semanas)* — *eje: probar el cableado*
- Un solo servicio (`catalog`) + `api-gateway` + `identity`.
- Una llamada sincrónica (REST en el gateway → gRPC contra `catalog`), un evento asincrónico (Kafka), una traza end-to-end visible en Tempo.
- Postgres por servicio, migraciones con Flyway.
- Docker Compose para todo; Testcontainers en los tests.
- **Demo:** crear un producto, verlo en los logs, ver la traza, ver la métrica.

### Fase 2 — Camino de lectura y búsqueda *(2 semanas)* — *eje: CQRS y consistencia eventual*
- `inventory`, `search` (Elasticsearch).
- Debezium → outbox → Kafka → indexer hacia ES.
- Aprender: por qué no hacemos doble escritura, y qué significa la consistencia eventual desde la UX del usuario final.

### Fase 3 — Camino de escritura y sagas *(2–3 semanas)* — *eje: transacciones distribuidas*
- `cart`, `orders`, `payments` (mock), `shipping` (mock).
- Orquestador de Saga con compensaciones.
- Claves de idempotencia, reintentos con backoff exponencial.
- Aprender: por qué los microservicios son difíciles, y cuándo en realidad un monolito habría alcanzado.

### Fase 4 — Listo para producción *(2 semanas)* — *eje: que no se rompa*
- Resilience4j en todos lados, con defaults sensatos documentados.
- Tests de contrato (Pact) entre consumidores y productores.
- Pruebas de carga (k6), chaos testing (toxiproxy inyectando latencia y fallos).
- SLOs documentados por servicio, dashboards en Grafana.

### Fase 5 — Cloud & deployment sobre Compose *(2 semanas)* — *eje: que llegue a destino*
- **LocalStack** para emular AWS gratis (S3, SQS, Secrets Manager) — pensar AWS como una "DLL".
- Módulos de Terraform.
- CI/CD con GitHub Actions.
- Deploy del stack con `docker compose` a un host en **Oracle Cloud Free Tier** (4 cores ARM + 24 GB RAM gratis para siempre).

### Fase 6 — Multi-instancia con Kubernetes *(opcional — opt-in según ADR `0003`)* — *eje: orquestación y escalado*
- `kind` + Helm charts para el stack completo.
- **Multi-instancia real:** múltiples réplicas por servicio, load balancing L7 para gRPC (headless service + client-side LB, o service mesh). El costo que ADR `0002` señalaba como "diferido" se cobra acá.
- Service mesh (Linkerd o Istio), mTLS en todo el cluster.
- Rolling deploys, network policies, autoscaling.

### Fase 7 — Stretch *(opcional)*
- CQRS con event sourcing completo sobre `orders`.
- Pensar multi-región, CDN, edge caching.

---

## Andamiaje con Claude Code

Este repo se construye con Claude Code en el loop. Lo siguiente va a vivir en `.claude/`:

- **`CLAUDE.md`** — convenciones del repo que se cargan en cada sesión (patrones Java 21, hexagonal, ubicación de los ADRs, naming).
- **Subagentes custom** en `.claude/agents/`:
  - `architect` — propone diseños y ADRs, no escribe implementación.
  - `service-scaffolder` — genera un servicio nuevo desde el template.
  - `event-reviewer` — verifica que los cambios de schema en Kafka sean retrocompatibles.
  - `security-reviewer` — corre antes de cada merge.
- **Slash commands** en `.claude/commands/`:
  - `/new-service <nombre>` — scaffolding desde el template.
  - `/new-adr <título>` — crea un ADR con la numeración correcta.
  - `/new-event <nombre>` — registra el schema Avro + stubs de producer y consumer.
- **`settings.json`** — pre-aprueba Bash seguro (mvn, docker compose, kubectl get), bloquea por defecto las operaciones destructivas.

---

## Trade-offs asumidos de entrada

- **Spring como default, Quarkus en un servicio para contrastar.** Spring es lo que pide el mercado, así que es la base del stack. Pero `notifications` se construye en Quarkus — es un servicio chico, aislado y sin API pública, ideal para medir en serio diferencias de cold-start, footprint de memoria y DX (live reload, dev mode, build nativo con GraalVM) sin arriesgar el camino crítico. Aprender los dos frameworks vale más que casarse con uno por inercia.
- **gRPC interno + REST externo** suma complejidad. Vale la pena hacerlo una vez para entender los dos lados.
- **GraphQL acotado a un endpoint en el BFF**, no como API general. La PDP es el caso de uso canónico (fan-out a múltiples servicios, cliente que pide solo los campos que usa, problema clásico del N+1 que se resuelve con DataLoader). Generalizar GraphQL a todo el sistema agrega schema federation, autorización por campo y caching no-trivial — fuera de scope para el aprendizaje inicial.
- **Saga en lugar de 2PC** es la única opción real en cloud-native; 2PC está prácticamente muerto. El ADR va a explicar por qué.
- **Kubernetes en Phase 6, opcional.** Primero Compose con una réplica por servicio en todas las fases anteriores; K8s entra solo si elegís entrar a la Phase 6, cuyo objetivo es aprender multi-instancia y load balancing L7 para gRPC. Ver ADR `0003`.
- **Monorepo con Gradle multi-módulo** — más simple para aprender en solitario, un solo CI, refactors atómicos. (Polirepo solo si/cuando los equipos se separen.)

---

## Estado del roadmap

- [x] Fase 0 — Arquitectura en papel
- [ ] Fase 1 — Walking skeleton
- [ ] Fase 2 — Camino de lectura y búsqueda
- [ ] Fase 3 — Camino de escritura y sagas
- [ ] Fase 4 — Listo para producción
- [ ] Fase 5 — Cloud & deployment sobre Compose
- [ ] Fase 6 — Multi-instancia con Kubernetes *(opcional)*
- [ ] Fase 7 — Stretch *(opcional)*

---

## Hasta dónde llegamos

**Fase 0 cerrada.** Los cimientos arquitectónicos que pedía la fase están firmes en `docs/`. Lo que vino antes de cualquier código:

### ADRs aceptados (`docs/adrs/`)

| Nº | Decisión | Fija |
|---|---|---|
| `0001` | Protobuf para gRPC, Avro + Schema Registry para Kafka | Formato de contratos sync y async |
| `0002` | gRPC para todo RPC interno entre servicios | Comunicación sincrónica puertas adentro |
| `0003` | Diferir Kubernetes a la fase final opcional | Compose-first, una réplica por servicio hasta Phase 6 |
| `0004` | Monorepo Gradle multi-proyecto con `build-logic` y contratos top-level | Layout físico del repo |
| `0005` | Hexagonal (Ports & Adapters) con dominio puro + CQRS lightweight | Estructura interna de cada servicio |
| `0006` | slf4j + Logback JSON + OTel Java Agent + Collector central | Logging, métricas y trazas baseline |
| `0007` | Patrón Outbox + CDC con Debezium (INSERT-then-DELETE en misma TX) | Mecanismo único de publicación de eventos |
| `0008` | Spring Authorization Server embebido en `identity` + JWT ES256 + bearer pass-through | Auth, formato de token y propagación al downstream |

### Modelado de dominio

- `docs/architecture/bounded-contexts.md` — primer pase: 4 bounded contexts + 8 eventos canónicos.
- `c4-diagram.drawio` — diagrama de containers inicial. **Pendiente migrar a Structurizr DSL** (no bloqueante para Fase 1).

### Reglas duras del proyecto (en `CLAUDE.md`)

1. Una base de datos por servicio. Ningún servicio lee la DB de otro.
2. Patrón Outbox para toda escritura que produce evento. Sin doble escritura.
3. Consistencia eventual en el read path. Índices downstream del CDC.
4. Claves de idempotencia en toda operación con side-effect externo.
5. Arquitectura antes que código. Patrones nuevos requieren ADR primero.
6. Docker Compose es el default; Kubernetes diferido a Phase 6.

## Pendientes conocidos

- **ADR de Saga vs 2PC para `orders`** — Phase 3. No se redacta todavía: comprometerse a la mecánica sin tener el agregado `Order` modelado en detalle es ADR-por-las-dudas. Entra cuando arranque el Event Storming serio del ciclo de vida de la orden.
- **Migrar `c4-diagram.drawio` → Structurizr DSL** — tarea de docs, no ADR. Alinea el diagrama con el resto del flujo (texto versionable).

## Próximo movimiento

Arrancar **Fase 1 — Walking skeleton** (catalog + api-gateway + identity, REST→gRPC sync, evento async vía outbox, traza end-to-end en Tempo, Postgres por servicio con Flyway, Compose). Es donde los ADRs dejan de ser papel y empiezan a doler/validarse.
