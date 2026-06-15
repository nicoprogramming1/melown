# contracts/catalog

Contratos públicos del servicio `catalog`. Versionados como ciudadanos de primera
en el monorepo (ADR `0004`).

- `proto/` — IDLs gRPC (`.proto`) — ver ADR `0002`.
- `avro/` — schemas de eventos Kafka (`.avsc`) — ver ADR `0001`.

Aún vacío. Se completa cuando el primer slice vertical de Fase 1 defina el
contrato de `CreateProduct` (gRPC) y `ProductCreated` (Avro).
