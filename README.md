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

### Fase 5 — Nube y deployment *(2 semanas)* — *eje: que llegue a destino*
- Kubernetes (`kind` en local) + Helm charts.
- **LocalStack** para emular AWS gratis (S3, SQS, Secrets Manager) — pensar AWS como una "DLL".
- Módulos de Terraform.
- CI/CD con GitHub Actions.
- Opcional: deploy a **Oracle Cloud Free Tier** (4 cores ARM + 24 GB RAM gratis para siempre — mejor que el free tier de AWS si querés un cluster k8s de verdad).

### Fase 6 — Stretch
- Service mesh (Istio), mTLS en todo el cluster.
- Pensar multi-región, CDN, edge caching.
- CQRS con event sourcing completo sobre `orders`.

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
- **Kubernetes desde la Fase 5, no desde la Fase 1.** Primero Compose; k8s recién cuando entendamos qué necesitan realmente los contenedores. Adoptar k8s antes de tiempo mata el aprendizaje.
- **Monorepo con Gradle multi-módulo** — más simple para aprender en solitario, un solo CI, refactors atómicos. (Polirepo solo si/cuando los equipos se separen.)

---

## Estado del roadmap

- [ ] Fase 0 — Arquitectura en papel
- [ ] Fase 1 — Walking skeleton
- [ ] Fase 2 — Camino de lectura y búsqueda
- [ ] Fase 3 — Camino de escritura y sagas
- [ ] Fase 4 — Listo para producción
- [ ] Fase 5 — Nube y deployment
- [ ] Fase 6 — Stretch

---

## Próximo movimiento

Arrancar la **Fase 0**:

1. Escribir los primeros tres ADRs: layout del repo, baseline de Java/Spring, comunicación entre servicios (gRPC + Kafka).
2. Bosquejar los bounded contexts y el primer diagrama C4 de contenedores en Structurizr DSL.
3. Redactar el `CLAUDE.md` para que cada sesión futura de código quede alineada con la arquitectura.

Nada de código todavía — solo los cimientos que dejaría un senior.
