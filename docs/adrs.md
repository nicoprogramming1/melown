# Scratchpad de decisiones

Notas crudas pendientes de promover a ADR formal. Cuando se promueva un ítem, se quita de acá y aparece en `docs/adrs/`.

- MDC (Mapped Diagnostic Context) → correlation id propagado entre servicios.
- Logging: slf4j + Logback con salida JSON en stdout, recolectado por Loki, visualizado en Grafana.
- CDC → Debezium → Kafka (regla dura ya en `CLAUDE.md`, falta ADR formal que la justifique en profundidad).
