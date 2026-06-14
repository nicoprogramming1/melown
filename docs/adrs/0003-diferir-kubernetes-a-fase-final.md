# 0003. Diferir Kubernetes a la fase final, con foco en multi-instancia

- **Status:** Accepted
- **Date:** 2026-06-12
- **Deciders:** @nwnorowsky

## Contexto

El `docs/README.md` plantea el stack objetivo como "Docker Compose → Kubernetes (Helm, kind)", lo que dejaba implícito que K8s entra en algún momento sin precisar cuándo ni con qué objetivo de aprendizaje. El ADR `0002` (gRPC) refuerza el sesgo mencionando service mesh y load balancing L7 como restricciones del proyecto.

Sin un punto explícito que limite ese sesgo, cada decisión futura puede arrastrar costos "porque vamos a K8s" que en realidad no se cobran hasta mucho después — o nunca, si la fase final no llega.

Las fuerzas en juego:

- **El proyecto es de aprendizaje, no de operaciones.** El objetivo del usuario en esta etapa, dicho explícitamente, es *entender el flujo end-to-end de procesos y hacerlo andar* — event flow con Kafka, outbox, CDC, índice eventual, observabilidad, resiliencia. **No** orquestación multi-instancia.
- **Una persona, una máquina.** No hay equipo, no hay producción, no hay multi-nodo. El stack se ejecuta local en Windows 11.
- **Velocidad del loop dev.** `docker compose up` con bind mounts y hot reload tiene loop de segundos. `build → tag → kubectl apply → wait` tiene loop de minutos. En fase de iteración rápida sobre lógica de dominio, la diferencia es enorme.
- **K8s tiene un objetivo de aprendizaje legítimo pero distinto.** Rolling deploys, network policies, mesh-based gRPC LB, autoscaling, RBAC, operators (Strimzi, CloudNativePG) son conocimiento valioso para roles Sr/Staff — pero son una *materia aparte* de microservicios.
- **En `kind` muchos features de K8s son artificiales.** Multi-node, autoscaling efectivo, network policies con un CNI serio, cross-zone failover — no se manifiestan realísticamente en un cluster de una máquina. Aprenderlos a medias en kind enseña mal.

## Decisión

Vamos a usar **Docker Compose como entorno default para todas las fases del proyecto excepto la última**, con **una sola réplica por servicio**.

**Kubernetes (kind + Helm)** entra **solo en una fase final, opcional**, cuyo objetivo de aprendizaje declarado es:

1. Orquestación multi-instancia (varias réplicas del mismo servicio coordinadas).
2. Load balancing L7 real para gRPC (headless service + client-side LB, o service mesh tipo Linkerd).
3. Rolling deploys y health-based traffic shifting.
4. Network policies y autoscaling — si el tiempo da.

Esa fase final **se activa si el usuario lo decide explícitamente**; no es un requisito del roadmap. Si nunca se activa, el proyecto se considera completo igual.

Hasta esa fase, **ninguna decisión arquitectural se justifica con "porque vamos a K8s"**. Si una pieza solo tiene sentido bajo K8s (service mesh, operators, cert-manager, OPA-gatekeeper), no se introduce en fases anteriores.

## Consecuencias

### Positivas

- **Loop dev rápido** durante todo el proyecto principal. Cambiar código → ver efecto en segundos, no en minutos.
- **Foco preservado.** Cada fase se concentra en una lección clara (event flow, outbox, CDC, búsqueda eventual, observabilidad, resiliencia) sin contaminación operativa de K8s.
- **Una cosa a la vez.** El usuario no aprende microservicios *y* K8s en paralelo; los separa para que cada uno aterrice.
- **Cuando K8s llegue, llega con el problema en la mano.** Al introducir multi-instancia, el dolor del load balancing gRPC se siente concreto y la solución (mesh o client-side LB) deja de ser abstracta. La lección "pega" más fuerte que si se hubiera tragado entera en Fase 1.
- **Reduce sobre-ingeniería temprana.** Patterns como service mesh, sidecars, operators, multi-namespace RBAC se evalúan en su fase, no se cuelan en decisiones de Fase 1 que no los necesitan.
- **Si la fase final no se hace, el proyecto sigue siendo valioso.** Las lecciones críticas (event-driven, CDC, idempotencia, resiliencia, observabilidad) están todas antes de K8s.

### Negativas

- **Sin exposición a operativa K8s hasta el final.** Helm, manifests, RBAC, network policies, rolling deploys — todo eso se queda pendiente. Si una entrevista los pregunta antes de llegar a esa fase, el usuario los discute desde teoría, no desde experiencia.
- **gRPC load balancing real no se prueba en las fases intermedias.** El problema del ADR `0002` ("HTTP/2 reusa conexión, L4 no balancea") no se manifiesta con una sola réplica. Cualquier bug específico de N pods aparece solo al final.
- **Algunas piezas del stack quedan fuera por mucho tiempo:** service mesh (Linkerd/Istio), cert-manager, OPA, operators como Strimzi (Kafka) o CloudNativePG. Son piezas comunes en producción "tipo MercadoLibre" y se postergan.
- **Riesgo de "fase final que nunca llega".** Es honesto reconocerlo: si el usuario pierde interés en el proyecto antes de llegar, K8s queda sin tocar. La mitigación es que las lecciones más importantes están *antes* de esa fase, no en ella.
- **Compose tiene límites operativos.** A medida que el stack crece (gateway, 4-5 servicios, Kafka, Postgres, Redis, ES, Tempo, Prometheus, Loki, Grafana, Schema Registry), el `docker-compose.yml` se vuelve un archivo grande y la máquina sufre. Hay que diseñar perfiles (`profiles:` de Compose) para no levantar todo siempre.

## Alternativas consideradas

### Alternativa A: Kubernetes desde Fase 1 (lectura implícita del README original)

Empezar con kind + Helm desde el primer servicio, traducir todo a manifests, usar Helm charts para todas las dependencias (Kafka, Postgres, etc.).

**Por qué perdió:**
- Duplica la carga cognitiva (microservicios *y* K8s al mismo tiempo). Para un Jr-Avanzado, esto significa avanzar a media velocidad en ambos.
- Loop dev mucho más lento; iteración sobre lógica de dominio sufre.
- En kind, los features que K8s realmente enseña (multi-nodo, autoscaling real, network policies con CNI serio, cross-zone failover) no se manifiestan o son artificiales.
- Las primeras fases (un solo servicio, después dos, después el primer evento Kafka) **no necesitan K8s para nada** — DNS, env vars, volúmenes, healthchecks: Compose ya lo da.

### Alternativa B: Kubernetes en una fase intermedia (digamos cuando hay 2-3 servicios funcionando)

Introducir K8s a mitad del proyecto, una vez que el stack tenga un par de servicios estables.

**Por qué perdió:**
- A mitad de proyecto, **todavía no hay un objetivo de aprendizaje específico que K8s resuelva mejor que Compose**. Sumarlo sin esa razón concreta diluye la fase intermedia y agrega complejidad operativa cuando todavía estás iterando rápido sobre lógica.
- Mejor postergarlo hasta que el problema (multi-instancia) sea *explícitamente* la lección. Así K8s tiene un "para qué" claro.

### Alternativa C: No usar K8s nunca, mantener Compose hasta el final

Sacar K8s del roadmap por completo y operar siempre en Compose.

**Por qué perdió:**
- K8s es parte significativa del paisaje "tipo MercadoLibre" que el proyecto quiere espejar.
- Aprenderlo está alineado con el objetivo Sr/Staff del usuario.
- Cerrarse de plano a K8s sería autoinfligirse un gap evitable.
- El acuerdo de este ADR es **posponer**, no **eliminar**. Si en algún momento la fase final no se hace por falta de tiempo o interés, es decisión del usuario en ese punto — no algo que esta decisión cierre hoy.

### Alternativa D: Usar Docker Swarm o HashiCorp Nomad como puente

Adoptar un orquestador intermedio (más simple que K8s) para tener multi-instancia sin la complejidad full.

**Por qué perdió:**
- **Docker Swarm está prácticamente muerto.** Docker desincentiva su uso, las features no avanzan, las compañías target no lo corren.
- **Nomad** es técnicamente sólido pero el ecosistema laboral en Latinoamérica es marginal comparado con K8s. Aprender un orquestador que no se usa en compañías target no entrega la lección correcta para el objetivo Sr/Staff.
- Si el objetivo es multi-instancia + preparación profesional, K8s es la respuesta correcta — no hay valor en aprender un puente que después no se reutiliza.

## Referencias

- `docs/README.md` — sección de stack objetivo (Docker Compose → Kubernetes).
- ADR `0002` — Usar gRPC para RPC interno. El costo de "load balancing L7" que ese ADR menciona se materializa recién en la fase final, no en fases intermedias con una réplica por servicio.
- ADRs futuros que dependen de esta decisión:
  - Estrategia de gRPC LB cuando llegue la fase final (headless service + client-side LB vs service mesh).
  - Estructura de Helm charts / Kustomize (si se elige).
  - Layout de `profiles:` en `docker-compose.yml` para no levantar todo el stack siempre.
