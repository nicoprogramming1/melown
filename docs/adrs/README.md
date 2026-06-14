# Architecture Decision Records

Las decisiones que dan forma a la arquitectura de Melown viven acá, una por archivo, en [formato Nygard](https://www.cognitect.com/blog/2011/11/15/documenting-architecture-decisions).

## Cómo crear uno

Usá el slash command: `/new-adr <título>`. Elige el próximo número, siembra la plantilla y agrega la entrada al índice de abajo automáticamente.

## Numeración

Cuatro dígitos con padding: `0001-...`, `0002-...`, etc. `0000-template.md` está reservado.

## Ciclo de status

- **Proposed** — bajo discusión.
- **Accepted** — ley vigente del proyecto.
- **Superseded by NNNN** — reemplazado; el archivo queda por historia.
- **Deprecated** — ya no relevante, sin reemplazo.

Una vez que un ADR está Accepted, el archivo es inmutable. Para cambiar la decisión escribí un ADR nuevo que lo supersede.

## Índice

- [0001 — Protobuf para gRPC y Avro para Kafka](0001-protobuf-para-grpc-y-avro-para-kafka.md) — Accepted
- [0002 — Usar gRPC para RPC interno entre servicios](0002-usar-grpc-para-rpc-interno.md) — Accepted
- [0003 — Diferir Kubernetes a la fase final, con foco en multi-instancia](0003-diferir-kubernetes-a-fase-final.md) — Accepted
- [0004 — Estructurar el monorepo Gradle con multi-proyecto, build-logic y contratos top-level](0004-estructurar-monorepo-gradle.md) — Accepted
- [0005 — Usar Hexagonal (Ports & Adapters) con dominio puro y CQRS lightweight como estructura interna de cada servicio](0005-estructura-interna-hexagonal.md) — Accepted
- [0006 — Adoptar slf4j + Logback JSON, OpenTelemetry Java Agent y Collector central como baseline de observabilidad](0006-logging-tracing-baseline.md) — Accepted
- [0007 — Adoptar Patrón Outbox + CDC con Debezium como mecanismo único de publicación de eventos](0007-patron-outbox-y-cdc-con-debezium.md) — Accepted
- [0008 — Adoptar Spring Authorization Server embebido en `identity` con JWT firmados ES256 y propagación bearer hacia downstream](0008-estrategia-de-auth-para-identity.md) — Accepted
