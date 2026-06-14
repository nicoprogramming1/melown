# 0001. Protobuf para gRPC y Avro para Kafka

- **Status:** Accepted
- **Date:** 2026-06-12
- **Deciders:** @nwnorowsky

## Contexto

El proyecto tiene dos canales de comunicación entre servicios que requieren formato de serialización y gobierno de esquemas:

1. **RPC interno síncrono** entre microservicios (request/response, baja latencia, contratos vivos entre equipos cercanos).
2. **Eventos asíncronos** publicados en Kafka (contratos de larga vida, consumidos por muchos servicios y pipelines analíticos heterogéneos a lo largo del tiempo).

Hasta este ADR ambas elecciones quedaron sin formalizar y con notas contradictorias entre el `docs/README.md` (Avro) y el scratchpad `docs/adrs.md` (Protobuf). El stack ya definido incluye **Confluent Schema Registry**, que condiciona la elección hacia formatos con integración nativa con esa pieza.

Las fuerzas en juego son distintas para cada canal:

- En RPC interno lo que importa es **code-gen tipado, baja latencia, ergonomía multi-lenguaje** y un IDL que defina además el contrato de servicio (no solo el payload). Los consumidores son pocos, conocidos, desplegados en sincronía con el productor.
- En eventos de Kafka lo que importa es **evolución segura a lo largo de años**, schemas auto-descriptivos, compatibilidad con el ecosistema analítico (Kafka Connect, Debezium para CDC, ksqlDB, sinks a Elasticsearch / data lake) y enforcement explícito de reglas de compatibilidad. Los consumidores son muchos, desconocidos a futuro y no se redespliegan a la par del productor.

Forzar el mismo formato en ambos canales obliga a uno de los dos a usar un formato que su ecosistema no bendice.

## Decisión

Vamos a usar **Protobuf** como formato de serialización para los contratos gRPC internos y **Avro** como formato para los eventos publicados en Kafka, con **Schema Registry** gobernando los esquemas Avro y modo de compatibilidad `BACKWARD` por default.

Esta decisión asume que el RPC interno será gRPC. La elección gRPC vs REST se formaliza en el ADR `0002`; este ADR queda subordinado a aquel — si gRPC fuera desplazado, la parte Protobuf de este ADR se revisa.

## Consecuencias

### Positivas

- **Cada canal usa el formato que su ecosistema bendice.** gRPC fue diseñado alrededor de Protobuf (su IDL nativo define servicios + mensajes en un solo archivo). Avro es el formato de primera clase de Schema Registry y del ecosistema Kafka Connect / Debezium / ksqlDB — cualquier herramienta downstream lo entiende sin adaptadores.
- **Schema Registry con `BACKWARD` compatibility** convierte la evolución de eventos en una regla *enforced en CI*, no en un acuerdo social. Romper un consumer viejo deja de ser posible accidentalmente.
- **Avro lleva el writer schema referenciado por ID**, así que los eventos son auto-descriptivos para consumers que no comparten código con el productor (analítica, CDC, otro lenguaje). Protobuf depende de que el consumer tenga el `.proto` correcto fuera de banda.
- **Logical types de Avro** (timestamp-millis, decimal, uuid) son bien soportados por el ecosistema Kafka; útil para campos de dominio sin reinventar convenciones.
- **Code-gen tipado en Java** en ambos canales (protoc para gRPC, Avro Maven/Gradle plugin para eventos). Errores de contrato se detectan en compile-time.
- **Si más adelante introducimos CDC con Debezium** (regla del proyecto: índice de búsqueda downstream de CDC), Debezium emite Avro de fábrica contra Schema Registry → cero fricción.

### Negativas

- **Doble cadena de codegen en el build Gradle.** Hay que configurar `protoc` y el plugin Avro en paralelo, mantener convenciones plugin (resolvible, pero suma complejidad al monorepo).
- **Costo cognitivo doble.** El equipo (y yo como Jr) tiene que aprender dos formalismos: tags numéricos y reglas de evolución de Protobuf, vs evolución por nombre y reglas de Avro/Schema Registry. Equivocarse en cuál aplica dónde es un riesgo real al principio.
- **Definiciones duplicadas para conceptos que viven en ambos canales.** Si una entidad de dominio se publica como evento *y* aparece en un endpoint gRPC, hay un `.proto` y un `.avsc` que pueden divergir. Mitigación: convención de carpetas (`contracts/proto/`, `contracts/avro/`) revisada en PR, y mappers explícitos en el borde de cada servicio. No usar generación automática cruzada — esconde la divergencia.
- **Conversores Protobuf↔Avro en el borde** cuando un servicio recibe un comando gRPC y publica un evento como resultado. Boilerplate, aunque honesto: deja claro que son dos contratos distintos.

## Alternativas consideradas

### Alternativa A: Protobuf en ambos canales (gRPC y Kafka)

Usar Protobuf también para los eventos de Kafka, con Schema Registry en modo Protobuf (soportado por Confluent desde 2020).

**Por qué perdió:**
- El ecosistema analítico de Kafka todavía gira alrededor de Avro. Debezium, Kafka Connect sinks (JDBC, Elasticsearch, S3) y ksqlDB tienen soporte Avro maduro y soporte Protobuf de "segunda clase" con limitaciones (ej. tipos anidados, oneOf).
- Las reglas de evolución de Protobuf son por **tag numérico**: tienden a "compilar y correr" hasta que un consumer viejo recibe un campo nuevo en un tag que reutilizó alguien. Avro evoluciona por **nombre** y las reglas las chequea Schema Registry antes de aceptar el nuevo esquema. Para contratos de larga vida entre equipos que no se conocen, el modo Avro falla más explícito y antes.
- Protobuf no fue pensado para auto-descripción en el wire; necesita que el consumer ya tenga el `.proto`. Eso funciona bien para gRPC (productor y consumer coordinados) pero es una limitación para eventos consumidos por sistemas analíticos.

### Alternativa B: Avro en ambos canales

Usar Avro RPC (o Avro sobre HTTP/2) para el RPC interno, unificando un único formato y eliminando la duplicación de definiciones.

**Por qué perdió:**
- **Avro RPC está prácticamente muerto** fuera del mundo Hadoop. No hay tooling Java mantenido al nivel de gRPC, no hay streaming, no hay interceptores, no hay generación de stubs cliente con la calidad de los de Protobuf.
- gRPC sin Protobuf pierde casi todo su valor — es básicamente HTTP/2 con streaming bidireccional. No hay razón para elegir gRPC y después no usar su IDL nativo.
- Avro no define servicios al nivel que lo hace Protobuf en gRPC (rpc methods, streaming, deadlines, codes); habría que reinventar esa capa.

### Alternativa C: JSON / JSON Schema en ambos canales

Serialización JSON con JSON Schema como contrato, sin codegen.

**Por qué perdió:**
- **Sin codegen tipado** → errores que en Protobuf/Avro son compile-time pasan a runtime. Para un proyecto cuyo objetivo es enseñar prácticas de microservicios serios, regalar tipado es ir hacia atrás.
- **3–10× más grande en el wire.** Para Kafka esto se traduce en costo de throughput y retención significativo, no marginal.
- **"Evolución" en JSON es un acuerdo social.** Schema Registry soporta JSON Schema pero el ecosistema (Connect, Debezium, ksqlDB) lo trata como ciudadano de tercera. Incompatible con el objetivo de tener eventos confiables como pegamento entre servicios.
- gRPC con JSON no es una combinación estándar (gRPC-Web hace JSON↔Protobuf solo en el browser edge).

### Alternativa D: Diferente — usar solo eventos, sin RPC interno

Mencionada para descartarla explícitamente: hacer todo async vía Kafka y eliminar el RPC interno como canal.

**Por qué perdió:**
- Hay operaciones inherentemente síncronas en el dominio (validaciones de stock pre-checkout, consultas de catálogo en el path de búsqueda) donde forzar async agrega latencia y complejidad sin valor.
- Choice arquitectural distinto, no una alternativa a este ADR. Si se quisiera ir por ese camino, sería otro ADR upstream.

## Referencias

- `docs/README.md` — sección de stack objetivo (incluye Schema Registry).
- `docs/adrs.md` — scratchpad con la nota cruda que motiva este ADR (Protobuf vs Avro abierto).
- ADR `0002` — Usar gRPC para RPC interno (decisión upstream de la que esta depende).
- Confluent docs — Schema Registry compatibility types (`BACKWARD`, `FORWARD`, `FULL`).
- Martin Kleppmann, *Designing Data-Intensive Applications*, cap. 4 ("Encoding and Evolution") — compara Protobuf, Avro y Thrift en profundidad.
