# 0008. Adoptar Spring Authorization Server embebido en `identity` con JWT firmados ES256 y propagación bearer hacia downstream

- **Status:** Accepted
- **Date:** 2026-06-14
- **Deciders:** @nwnorowsky

## Contexto

El servicio `identity` es la pieza más sensible del sistema: define quién es quién, qué puede hacer cada uno y cómo se propaga esa identidad a través de los demás bounded contexts. `docs/architecture/bounded-contexts.md` ya lo declara como Customer-Supplier upstream de `catalog` y de `orders` — ambos validan vendor/user antes de aceptar comandos —, y `docs/README.md` lo nombra como dueño de "OAuth2/OIDC, JWT, refresh tokens". Lo que falta es decidir **cómo se construye eso por dentro** y, sobre todo, **qué piezas de infraestructura entran en escena** para soportarlo.

Las fuerzas en juego:

- **Construir, no instalar.** Una restricción explícita del proyecto: no adoptamos Keycloak, Authentik, Auth0, Okta ni ningún IdP-as-a-service. La decisión es qué bibliotecas combinar dentro del propio servicio `identity` para implementar el rol de Authorization Server. El valor educativo del proyecto exige que esa pieza viva en código nuestro.
- **Aprendizaje vs costo de mantener.** El usuario está en un tramo Jr-Avanzado → Sr/Staff. Escribir un JWT a mano con Nimbus enseña el formato; usar un Authorization Server canónico enseña los flujos OAuth2 completos (descubrimiento, introspection, JWKS, refresh rotation, PKCE). Las dos vías enseñan, pero a costos distintos: una se vuelve carga operativa cuando aparece el segundo cliente; la otra acepta una dependencia más grande a cambio de cubrir el estándar.
- **Cliente real conocido = uno.** El consumidor identificado en Fase 1 es el `api-gateway` (REST hacia afuera, gRPC hacia adentro). El `bff-web` aparece en Fase 2-3 con GraphQL para la PDP. No hay terceros, ni partners B2B, ni móviles nativos planeados. El espacio de clientes es chico y estable, pero **el ejercicio explícito del proyecto es modelar como si lo fuera**: PKCE, refresh rotation, JWKS público, etc. Sin esa restricción el ADR sería trivial — "hacé sesiones server-side y listo".
- **Otros servicios validan, no emiten.** `catalog` y `orders` reciben identidad ya validada y necesitan tomar decisiones de autorización (¿es este vendor el dueño del producto?, ¿es este user el dueño de la orden?). La identidad tiene que viajar de forma que cada servicio downstream pueda re-verificarla sin llamar a `identity` en cada request — eso convierte a `identity` en SPOF y multiplica latencia.
- **gRPC interno (ADR `0002`).** La identidad va a propagarse por metadata gRPC. El formato elegido tiene que entrar limpio en headers de metadata, ser firmable/verificable sin un round-trip de red en el path crítico, y sobrevivir a la propagación de `traceparent` (ADR `0006`) sin colisionar con headers reservados.
- **Hexagonal (ADR `0005`).** El dominio de `identity` (`User`, `Vendor`, `Authenticate`, `RefreshToken`) no debería conocer Spring Authorization Server, ni Nimbus, ni JWK. Esas son piezas de infraestructura. El use case `Authenticate` recibe credenciales y devuelve un par de tokens; el cómo se firman vive en un adapter.
- **Outbox + CDC (ADR `0007`).** Cuando `identity` emite `UserRegistered` o `VendorRegistered`, lo hace por outbox. Nada del flujo de auth puede asumir publicación sincrónica a Kafka. Esto descarta diseños donde el "token revocado" se propague vía Kafka y se asuma global en milisegundos.
- **Compose-first (ADR `0003`).** Una réplica por servicio hasta Phase 6. Las decisiones de rotación de claves y de almacenamiento de refresh tokens tienen que funcionar con un único proceso de `identity` corriendo y migrar limpio a multi-réplica cuando llegue.
- **Operación realista de un sistema "como MercadoLibre".** Aunque el contexto sea de aprendizaje, las prácticas tienen que ser las de producción: JWTs con TTL corto, refresh tokens revocables, JWKS rotable, algoritmos modernos. El ADR debería ser citable en una entrevista de Sr/Staff sin sonrojarse.

## Decisión

Adoptar **Spring Authorization Server (proyecto oficial del equipo Spring Security, sucesor de Spring Security OAuth) embebido como un módulo más del servicio `identity`**, configurado para emitir **JWTs firmados con ES256 (ECDSA P-256)**, exponer un **JWKS público** que el `api-gateway` y los servicios downstream consumen, y soportar dos flujos OAuth2 en Fase 1: **Authorization Code + PKCE** y **Client Credentials**. Refresh tokens con **rotación obligatoria** y revocación por persistencia en `identity_db`. La identidad se propaga **bearer-style** del gateway hacia los servicios downstream por metadata gRPC; cada servicio re-valida la firma contra el JWKS cacheado.

### Resumen de la decisión

| Aspecto | Decisión |
|---|---|
| Authorization Server | Spring Authorization Server embebido en `identity` |
| Flujos OAuth2 soportados (Fase 1) | Authorization Code + PKCE, Client Credentials, Refresh Token |
| Flujos prohibidos | Implicit, Resource Owner Password Credentials (ROPC) |
| OIDC | Activado: `id_token`, `userinfo`, discovery (`/.well-known/openid-configuration`) |
| Token de acceso | JWT firmado (JWS), `ES256` |
| TTL access token | 10 minutos |
| TTL refresh token | 14 días, con rotación obligatoria |
| Algoritmos de firma prohibidos | `none`, `HS256` (cualquier MAC simétrica) |
| Claves de firma | Par EC P-256 generado al startup en Fase 1, persistido en Postgres `identity_db.signing_keys` (encriptado vía Vault transit key) |
| Rotación | Overlap window: clave nueva activa para firmar, clave vieja sigue en JWKS para verificar hasta que expire el último JWT que firmó |
| Endpoint JWKS público | `GET /.well-known/jwks.json` en el `identity` |
| Hashing de password | Argon2id, parámetros `m=64MB, t=3, p=1` |
| Propagación a downstream | El gateway reenvía el access token tal cual en metadata gRPC (`authorization: Bearer <jwt>`); cada servicio re-valida firma + claims contra JWKS cacheado |
| Revocación de access token | No revocable individualmente. TTL corto + posibilidad de kill-switch global por `iat < cutoff` |
| Revocación de refresh token | Sí, persistido en `identity_db.refresh_tokens` con flag `revoked_at` |

### Flujos OAuth2 soportados en Fase 1

**Authorization Code + PKCE**. Es el flujo para clientes que actúan en nombre de un usuario humano: el `api-gateway` cuando media una request del frontend (Fase 1 todavía no hay frontend, pero el flujo se modela igual contra el playground GraphiQL del `bff-web`), o un mobile/SPA hipotético. PKCE no se discute — sin PKCE el flujo es vulnerable a interceptación del authorization code en clientes públicos, y el RFC 7636 lo hace mandatorio en OAuth 2.1.

**Client Credentials**. Es el flujo para clientes que **no actúan en nombre de un usuario**: jobs batch, workers de Saga, callbacks de proveedores externos en Fase 3+. Hoy no hay un caller identificado para Fase 1, pero el flujo se deja habilitado para no tener que tocar el Authorization Server cuando aparezca el primer caso en Fase 2-3.

**Refresh Token con rotación**. El refresh token se entrega junto al access token. Cuando el cliente lo usa, `identity` emite un access token nuevo **y** un refresh token nuevo, e invalida el viejo. Esto detecta token theft: si el atacante usa el refresh viejo y la víctima ya rotó, el atacante recibe la nueva pareja pero la víctima al próximo refresh recibe error y se fuerza re-login — es la señal de detección. Sin rotación, un refresh token robado es válido durante toda su vida útil sin disparar señal.

**Excluidos explícitamente y por qué:**

- **Implicit flow.** Deprecated por OAuth 2.1. Devuelve el access token en el fragmento de la URL, sin code intermedio, sin PKCE posible. No hay razón para soportarlo.
- **Resource Owner Password Credentials (ROPC).** Acepta `username + password` directo del cliente. Es el anti-flow: obliga al cliente a manejar credenciales en claro, hace imposible el MFA y rompe la promesa central de OAuth ("el cliente nunca ve la password"). Está marcado para retiro en OAuth 2.1. Permitirlo "para tests" termina siendo permitirlo en producción.
- **Device Authorization Grant.** Diseñado para TVs / IoT sin teclado. No hay caso de uso.

### Spring Authorization Server vs JWT custom: por qué Spring AS gana

El servicio Auth Server **no es trivial de escribir bien**. Tiene que exponer correctamente, como mínimo:

- `/oauth2/authorize` con manejo de redirect URI, scopes, PKCE challenge, consent.
- `/oauth2/token` con cuatro modos distintos (`authorization_code`, `refresh_token`, `client_credentials`, y el rechazo explícito de `password`).
- `/oauth2/revoke` (RFC 7009).
- `/oauth2/introspect` (RFC 7662) — opcional pero estándar.
- `/.well-known/openid-configuration` (OIDC discovery).
- `/.well-known/jwks.json` (JWKS público).
- `/userinfo` (OIDC).
- Manejo de errores con códigos canónicos (`invalid_grant`, `invalid_client`, `unauthorized_client`, etc.).

Spring Authorization Server cubre todo esto out-of-the-box con configuración declarativa. Está mantenido por el equipo Spring Security oficial — no es un side-project. Es el sucesor explícito de `spring-security-oauth2` (deprecated en 2020). En el ecosistema Java es **la** implementación canónica de "construirte tu propio Auth Server".

El camino "JWT custom con Nimbus JOSE+JWT" sigue siendo el complemento natural: Nimbus es la librería de JOSE/JWT más usada en Java, y de hecho Spring Authorization Server la usa por debajo para firmar/verificar tokens. La diferencia es **dónde paramos**: con Nimbus solo, escribimos todos esos endpoints a mano; con Spring AS encima, los recibimos hechos y configuramos.

El valor educativo se preserva igual:

- **Tipos de tokens, claims, firma asimétrica:** se aprenden leyendo qué emite Spring AS y configurándolo. No se pierde porque lo escriba la librería.
- **Flujos OAuth2:** se aprenden leyendo el RFC y comparando con la configuración. Spring AS te obliga a entender los flujos porque cada cliente que registrás declara explícitamente `authorizationGrantTypes`, `redirectUris`, `tokenSettings`.
- **JWKS y rotación:** el `JWKSource<SecurityContext>` es un bean que escribimos nosotros. Es donde vive la lógica de rotación. Es el punto exacto donde el aprendizaje de "cómo se rotan claves de firma" se materializa.

El camino que **sí descartamos** es escribir los endpoints OAuth2 a mano sobre Nimbus, porque las superficies de error de OAuth2/OIDC son densas (PKCE state, redirect URI exact match, consent flow, error response format) y cualquier divergencia respecto al RFC se transforma en bugs de seguridad sutiles, no en bugs funcionales que un test descubra. La industria aprendió esto a fuerza de CVEs en implementaciones caseras; la mejor práctica Sr/Staff es **no escribirte el Authorization Server a mano**, sino usar una implementación auditada y configurarla con criterio.

### Formato de token: JWT firmado vs token opaco

Tokens opacos (un string aleatorio del lado del Authorization Server, validable solo vía `/introspect`) tienen una ventaja real: **revocación inmediata por construcción**. Borrás el row en la DB, el token deja de servir al próximo check.

El costo: **cada validación es un round-trip de red a `identity`**. Cada llamada gRPC entre servicios necesita validar identidad → cada hop suma una llamada a `identity/introspect` → `identity` se convierte en SPOF del path crítico de toda request del sistema. Con cachés se mitiga pero se reintroduce el problema de invalidación.

Adoptar **JWT firmado** invierte el trade-off: la validación es local (verificar firma con clave pública del JWKS cacheado), zero round-trips. El costo es que el token sigue valiendo hasta que expira — no hay forma de "matarlo" inmediatamente. Esto se compensa con TTL corto (10 minutos) y con la capacidad de revocar el refresh token (que sí persiste en DB).

Para Melown la decisión es JWT firmado:

- El número de validaciones por segundo en operación normal es órdenes de magnitud mayor al número de revocaciones por segundo. Optimizar el path caliente vs el path frío.
- ADR `0007` ya establece consistencia eventual como filosofía del read path. Una ventana de hasta 10 minutos entre "revocar" y "tomar efecto" es coherente.
- El JWKS cacheado en los servicios downstream + JWT autocontenido se llevan bien con el principio de "no depender de `identity` en el path caliente".

**Kill switch global.** Para casos extremos (clave de firma comprometida, brecha masiva) se acepta un mecanismo de invalidación global: un claim `iat` (issued-at) en cada JWT y una variable de configuración `min_iat_accepted` por servicio (servida desde una config dinámica, ej. Consul / Spring Cloud Config en Phase 5+). Cualquier JWT con `iat < min_iat_accepted` se rechaza. Es un nuke; no se usa para revocaciones individuales.

### Claims canónicos del JWT

```json
{
  "iss": "https://identity.melown.local",
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "aud": ["api-gateway", "catalog", "orders", "inventory"],
  "exp": 1718380200,
  "iat": 1718379600,
  "jti": "01HXYZ...",
  "scope": "products:read products:write orders:read",
  "user_role": "VENDOR",
  "vendor_id": "660e8400-e29b-41d4-a716-446655440111"
}
```

- `iss` (issuer): URL canónica de `identity`. Cada servicio downstream rechaza JWTs con `iss` distinto.
- `sub` (subject): el `userId` (UUID) — la identidad estable del principal.
- `aud` (audience): lista de servicios autorizados a aceptar el token. El gateway no se incluye en `aud` (es proxy); los servicios de negocio sí. Cada servicio rechaza tokens que no lo nombren en `aud`.
- `exp`, `iat`, `jti`: estándar JWT.
- `scope`: scopes OAuth2 emitidos para este token. Granularidad por recurso + acción (`products:read`, `orders:write`, etc.).
- `user_role`: rol funcional del principal (`USER` | `VENDOR` | `ADMIN`). Replica una decisión que vive en el dominio de `identity`; otros servicios la usan para decisiones de autorización sin tener que llamar a `identity`.
- `vendor_id`: si `user_role = VENDOR`, el `vendorId` correspondiente. Permite a `catalog` validar "¿es este vendor el dueño del producto?" sin un round-trip a `identity`.

Estos claims son **parte del contrato de `identity`** con el resto del sistema. Cambiar un claim que otros servicios consumen es un cambio breaking — equivalente a cambiar un `.proto` consumido por varios. Documentar en `contracts/identity/jwt-claims.md` (módulo Gradle de contracts ya planeado en ADR `0004`).

### TTL y rotación de tokens

- **Access token: 10 minutos.** Suficientemente corto para que la ventana de un token robado sea acotada; suficientemente largo para no inundar `identity` con refreshes constantes. El número estándar en la industria oscila entre 5 y 15 minutos para JWTs no revocables.
- **Refresh token: 14 días, con rotación obligatoria.** Cada uso emite un par nuevo e invalida el viejo (refresh token rotation, RFC 6749 §10.4 recomendada por OAuth 2.1). Si el cliente intenta usar un refresh token ya rotado, `identity` invalida toda la familia de refresh tokens descendiente — defensa contra token theft.
- **Sliding vs absolute lifetime del refresh token.** Sliding: cada rotación extiende el window 14 días. Absolute: la primera emisión define el deadline, las rotaciones no extienden. Decisión: **absolute** en Fase 1. Sliding requiere repensar el modelo de "sesión" y agrega complejidad sin un caso de uso claro en el corto plazo. Si en Phase 4-5 aparece una necesidad UX real ("usuario no quiere re-loguearse cada 2 semanas"), se evalúa.

### Algoritmo de firma: ES256

Hay dos familias razonables: RSA (`RS256`) o ECDSA (`ES256`). Las dos son firma asimétrica, las dos están en RFC 7518 (JWA). La diferencia práctica:

| Aspecto | RS256 (RSA 2048) | ES256 (EC P-256) |
|---|---|---|
| Tamaño de la firma | ~256 bytes | ~64 bytes |
| Tamaño de la clave pública | ~270 bytes | ~91 bytes |
| Tiempo de firma | ~0.5 ms | ~0.2 ms |
| Tiempo de verificación | ~0.05 ms | ~0.6 ms |
| Soporte | Universal | Universal (todas las libs modernas) |
| Tamaño del JWT resultante | Mayor | Menor (~30% menos) |

**Decisión: ES256.** Razones:

- **JWT más chico.** En metadata gRPC el header `authorization` viaja en cada call. Restar 200 bytes por token, multiplicado por la fan-out típica de la Saga (4-5 servicios), es ahorro real en el path caliente.
- **Firma más rápida.** `identity` firma muchos JWTs (cada login, cada refresh). La verificación es más cara en EC, pero la verificación se hace en los servicios downstream donde la carga está repartida — no se acumula en un solo proceso.
- **Práctica industrial moderna.** ECDSA se considera el default actual para nuevos despliegues; RSA se mantiene por compatibilidad con sistemas legacy. Para un proyecto greenfield, ES256 es la elección Sr/Staff defendible.

El downside de ES256 — verificación más lenta — se mitiga con el caché del JWKS y el hecho de que cada servicio verifica con su propia copia de la clave pública, sin coordinar.

**Prohibidos:** `none` (el "alg none" del histórico CVE 2015 de jsonwebtoken), `HS256` y cualquier algoritmo simétrico (la clave secreta tendría que conocerla cada servicio que valida → con N servicios, el secret está en N lugares → cualquiera puede emitir JWTs válidos → no hay separación entre issuer y validators). El campo `alg` en el header se valida explícitamente en los validators downstream: solo `ES256` se acepta, todo lo demás se rechaza incluso si la firma matcheara.

### Rotación de claves JWK

El par de claves EC tiene un ciclo de vida explícito:

1. **Generación.** Una clave nueva se genera con un `keyId` (UUID corto). Se persiste en `identity_db.signing_keys` (clave privada cifrada con Vault transit key, clave pública en plano).
2. **Activación.** La nueva clave pasa a ser la "current signing key" — todos los JWT emitidos desde ahora la usan. La clave anterior permanece en JWKS pero no firma más.
3. **JWKS público.** El endpoint `/.well-known/jwks.json` expone **todas las claves no expiradas** (la activa + las anteriores que todavía firmaron JWTs no expirados). Cada entrada incluye `kid` (key id), `kty` (`EC`), `crv` (`P-256`), `x`, `y`, `alg` (`ES256`), `use` (`sig`).
4. **Validación downstream.** Los servicios consumen el JWKS y matchean por `kid` del header del JWT. Si el `kid` no está en su caché, refrescan el JWKS desde `identity` y reintentan. Caché TTL: 1 hora; refresh proactivo: cada 15 minutos.
5. **Retiro.** Una clave se retira del JWKS cuando han pasado `max(access_token_ttl, refresh_token_ttl) + grace_period` desde la última vez que firmó. Para los valores actuales: 14 días + 1 día de gracia = clave retirable a los 15 días de no ser la activa.
6. **Rotación programada.** Cada 90 días se genera una clave nueva. Es un cron interno del servicio `identity`, no un comando manual.

**Overlap window.** Durante la rotación, **dos claves son válidas en simultáneo**: la nueva (firma + valida) y la vieja (solo valida). Es el detalle crítico: si el día de la rotación los servicios downstream solo conocieran la clave nueva, los JWTs emitidos minutos antes con la clave vieja se rechazarían. El JWKS publicar ambas resuelve esto. La clave vieja sale del JWKS recién cuando todos los JWT que firmó ya expiraron.

**Rotación de emergencia (clave comprometida).** Si se sospecha que una clave privada está comprometida, la rotación de emergencia retira la clave inmediatamente del JWKS — pero eso invalida todos los JWT firmados con ella, que estaban en circulación. Es traumático para los usuarios pero correcto desde la postura de seguridad. La activación del kill switch (`min_iat_accepted`) puede acompañar para invalidar incluso JWTs emitidos por la clave nueva pero antes del incidente.

### Revocación

**Refresh tokens son revocables.** Persistidos en `identity_db.refresh_tokens` con campos `family_id` (para detectar reuse en la familia rotada), `revoked_at`, `replaced_by`. Cada uso valida que el row exista, no esté revocado y no haya sido ya rotado.

**Access tokens NO son revocables individualmente.** Es la consecuencia aceptada de elegir JWT por encima de tokens opacos. Mitigaciones:

- TTL corto (10 min).
- Revocar el refresh token corta el grifo: el cliente no puede emitir nuevos access tokens.
- Kill switch global por `iat` para incidentes graves.

**Blacklist en Redis** se evaluó y rechazó para Fase 1: introduce una dependencia más en el path de validación de cada JWT (Redis call por cada request downstream), agrega un punto de falla y resuelve un problema que la combinación TTL corto + revocación de refresh token ya resuelve en la práctica. Si Phase 4 trae un requirement explícito de "matar un access token activo en < 1 minuto", se reabre.

### Validación y propagación a downstream

El `api-gateway` es el primer validador. Recibe la request HTTP del cliente externo con `Authorization: Bearer <jwt>`, valida:

1. Firma contra el JWKS cacheado de `identity`.
2. `exp` no expirado.
3. `iat >= min_iat_accepted` (kill switch).
4. `iss == https://identity.melown.local`.
5. `aud` contiene `api-gateway` o el servicio destino (TBD según routing).

Si todo OK, el gateway **reenvía el JWT tal cual** en la metadata gRPC del downstream call:

```
authorization: Bearer <jwt-completo>
traceparent: 00-...-...-01
```

Esto es la **Opción A** mencionada en el espacio de decisión: el JWT del usuario viaja a través de toda la cadena. La **Opción B** (gateway emite un "internal token" firmado por una clave de mesh, los downstreams confían solo en eso) se rechaza:

- **Pierde la identidad del usuario.** El internal token typically carga "yo soy el gateway autorizado" pero no "yo soy el user X actuando como vendor Y". Recuperar eso requiere un campo `on_behalf_of` que es reinventar el JWT del user.
- **Doble emisión.** El gateway tendría que ser otro mini-AS. Más superficie, más rotación, más complejidad.
- **No hay defensa en profundidad.** Si el gateway compromete, todos los servicios downstream confían en él ciegamente. Con bearer pass-through cada servicio valida la firma original de `identity` — son defensas independientes.

El costo del bearer pass-through es que **cada servicio downstream también valida el JWT**. Es defensa en profundidad: si por error un caller interno se saltea el gateway (red mal configurada, port forward en debug), el servicio igual rechaza la request. La validación local es barata (verificación ES256 ~ 0.6ms) y no requiere round-trip.

La metadata gRPC `authorization` no colisiona con `traceparent` (ADR `0006`) ni con otras propagaciones — son keys distintas. El interceptor de autenticación de `grpc-java` (`io.grpc.ServerInterceptor`) corre antes que el handler de negocio y mete el principal en el `Context` del request.

### Hashing de password: Argon2id

**Argon2id**, parámetros iniciales `memoryCost=64MB, timeCost=3, parallelism=1`. Es el ganador del Password Hashing Competition (2015) y la recomendación OWASP actual.

Comparado con bcrypt:

- **bcrypt** tiene 25 años, está bien estudiado, es seguro, pero su parámetro de costo solo escala CPU. Las GPUs y los ASICs modernos rompen bcrypt mucho más rápido que CPUs.
- **Argon2id** está diseñado memory-hard: forzar al atacante a usar memoria RAM cara hace los ataques con GPU/ASIC económicamente inviables. El parámetro `memoryCost=64MB` significa que cada intento de crackeo consume 64MB de RAM — un atacante con una GPU de 8GB puede hacer ~125 intentos en paralelo, vs los miles de millones de bcrypt en la misma GPU.

bcrypt sigue siendo aceptable y NIST todavía no lo desaprueba, pero para un sistema greenfield la elección Sr/Staff es Argon2id. La librería Java de referencia es `de.mkammerer:argon2-jvm` (binding nativo) o el soporte directo en Spring Security 6.

Los parámetros son configurables vía property — si `identity` corre en un host pequeño, bajar `memoryCost`. Si corre en uno generoso, subir. El primer pase usa 64MB porque es el mínimo OWASP-recomendado para servicios autenticando interactivamente.

**Política de password (Fase 1):**

- Mínimo 12 caracteres.
- No se exige clase de caracteres (NIST SP 800-63B dejó atrás los "una mayúscula + un número + un símbolo" — empuja a passwords memorizables peores). En su lugar, validar contra una lista de passwords filtradas (`have-i-been-pwned` API o lista local).

**Diferido a Phase 4 (cuando llegue MFA):**

- TOTP / WebAuthn como segundo factor.
- Rate limiting de intentos de login por IP + por user (mientras tanto, `identity` se beneficia del rate limit genérico del gateway).
- Lockout temporal después de N intentos fallidos.

### OIDC: activado

Activamos OIDC (no solo OAuth2 puro). Esto suma:

- `id_token` (JWT con claims del usuario para que el frontend conozca al usuario sin un call extra).
- `/userinfo` endpoint (acceso a claims completos vía access token).
- `/.well-known/openid-configuration` (discovery — el cliente descubre todos los endpoints leyendo este JSON).

El costo es marginal: Spring Authorization Server soporta OIDC con un flag. El beneficio: el `bff-web` (Fase 2-3) y cualquier herramienta de auth genérica (Postman OAuth2 helper, `oauth2-proxy`) hablan OIDC out of the box. Hacer OAuth2 puro hubiera obligado a documentar todos los endpoints manualmente.

### Almacenamiento de la clave de firma privada

En Fase 1 (Compose local, sin Vault corriendo todavía): la clave privada se serializa al `signing_keys` table de Postgres en plano, marcado con un TODO de "encripta cuando Vault esté arriba". Dado que `identity_db` corre en un container local, esto es aceptable.

En Phase 5 (cuando Vault está en escena por requisito explícito del proyecto): la clave privada se cifra con una transit key de Vault antes de persistir. `identity` necesita un token Vault al startup para descifrarla. La clave pública sigue en plano — no es secreto.

Alternativa considerada: usar Vault como signer (la clave privada nunca sale de Vault, `identity` le pide a Vault que firme cada JWT). Rechazada para Fase 1: agrega un hop de red en el path crítico de emisión de tokens. Vale la pena revisar en Phase 4-5 si la postura de "private key never leaves Vault" se vuelve requirement.

## Consecuencias

### Positivas

- **Implementación canónica.** Spring Authorization Server es la implementación de referencia en el ecosistema Java. Documentación abundante, examples oficiales, comunidad activa. Bugs de seguridad descubiertos en la lib se parchean upstream — no es código nuestro auditándose a sí mismo.
- **El servicio `identity` sale corto en código de boilerplate y largo en código de dominio.** El use case `Authenticate` y `RefreshToken` viven en `application/`, donde realmente importan. El plumbing OAuth2 se configura, no se programa.
- **Aprendizaje preservado.** Configurar Spring Authorization Server obliga a entender qué es un `RegisteredClient`, qué `authorizationGrantTypes` declarar, cómo se generan las claves del `JWKSource`, cómo se serializan los claims. No es "instalá Keycloak y olvidate" — es "construite el AS pero con piezas mantenidas". Sr/Staff defendible: "elegimos no reinventar la rueda en la zona de mayor riesgo de seguridad".
- **JWT autocontenidos = `identity` no es SPOF en el path caliente.** Cada servicio valida con JWKS local. Si `identity` cae, los usuarios ya logueados siguen operativos durante 10 minutos (hasta que necesiten un refresh). Esto es muy distinto de tener un `/introspect` por cada call.
- **Defensa en profundidad.** Bearer pass-through significa que cada servicio re-valida; un compromiso del gateway no compromete a los servicios.
- **Algoritmos modernos.** ES256 + Argon2id son las recomendaciones actuales de OWASP/NIST. La decisión envejece bien.
- **Refresh token rotation con detección de reuse.** No es solo "refresh tokens expiran", es activamente detectar token theft. Una práctica que aparece en sistemas serios y que el proyecto va a poder explicar de la pizarra.
- **Coherente con resto de ADRs.** Outbox para `UserRegistered`/`VendorRegistered`. Hexagonal: el use case no conoce JWT. gRPC: la propagación cabe limpia en metadata.

### Negativas

- **Spring Authorization Server es una dependencia pesada.** Trae spring-security-oauth2-authorization-server + spring-security-oauth2-resource-server + spring-security-oauth2-jose + nimbus-jose-jwt + Spring Security core completo. ~80 clases entre JARs. El servicio `notifications` (Quarkus) **no** lo carga porque no es Authorization Server — solo consumer.
- **Configurar Spring Authorization Server tiene curva.** Hay tres lugares donde se mete config (`RegisteredClientRepository`, `AuthorizationServerSettings`, `SecurityFilterChain` con `OAuth2AuthorizationServerConfigurer`). La primera vez es confuso; la documentación oficial es buena pero asume familiaridad con Spring Security.
- **Bearer pass-through obliga a cada servicio a ser OAuth2 Resource Server.** Cada servicio carga `spring-security-oauth2-resource-server` y configura JWKS URI. Es boilerplate replicado, mitigable con un convention plugin (`melown.auth-resource-server` en `build-logic`, ADR `0004`).
- **JWTs son grandes.** Con los claims que definimos, un JWT ronda 700-900 bytes. En metadata gRPC se replica en cada call. Para fan-out de 5 servicios, son ~4KB de header en total. No es dramático pero es real.
- **Revocación de access token diferida en el tiempo.** Hasta 10 minutos de ventana entre "revocar refresh token" y "todos los access tokens expirados". Para casos comunes (logout, cambio de password) esto es aceptable. Para incidentes (token comprometido) hay que activar kill switch global, que es disruptivo.
- **JWKS caching introduce eventual consistency.** Si una clave nueva entra a JWKS y un servicio aún tiene caché viejo, el JWT firmado por la nueva clave se rechaza hasta el próximo refresh del caché. Mitigación: refresh proactivo cada 15 min + retry del consumer si encuentra `kid` desconocido.
- **Rotación de claves es operativamente compleja.** Si el job de rotación falla en silencio, las claves no rotan y al expirar la activa, el sistema deja de poder emitir tokens. Monitoring de "última rotación exitosa" es no-negociable desde Phase 4.
- **Argon2id consume RAM por verificación.** Con `memoryCost=64MB`, cada login concurrente reserva 64MB transitorios. 100 logins simultáneos = 6.4GB de RAM picos. En containers con memory limits hay que tenerlo en cuenta — `identity` no es un servicio que escale a centenares de logins/s sin pensarlo, pero el proyecto no llega a esas cargas.
- **El servicio `identity` ahora hace dos cosas distintas:** dominio de usuarios + Authorization Server. Si el dominio crece (compliance, audit log, MFA, recovery flows), el servicio engorda. Una opción a futuro es separar el Authorization Server como servicio aparte (`auth-server` distinto de `identity`); ese refactor no es trivial — lo postergamos hasta que el dolor sea real.

### Riesgos a vigilar

- **Configuración incorrecta de `aud`.** Si un servicio acepta tokens sin verificar `aud`, un token emitido para otro servicio sirve también. La validación de `aud` tiene que estar en la config del Resource Server, no en código de aplicación. Convention plugin debe imponerlo.
- **Caché del JWKS sobre-agresivo.** Si los servicios no refrescan el JWKS cuando ven un `kid` desconocido, una rotación rompe el sistema durante el TTL del caché. Hay que validar este flow con un test de integración cuando lleguemos a tenerlo.
- **Algorithm confusion attack.** Si un Resource Server no fija `alg=ES256` explícitamente, un atacante con la clave pública puede emitir un JWT firmado con HS256 usando la clave pública como secret simétrico y el Resource Server lo valida. Spring Security mitiga por default, pero hay que verificar la config explícita por servicio.
- **Refresh token grandfathered tras un breach.** Si la DB de refresh tokens se compromete, los atacantes pueden seguir emitiendo access tokens hasta que cada refresh token expire (14 días). Mitigación: rotar todas las claves de firma (invalida los access tokens en circulación) + invalidar masivamente los refresh tokens (`UPDATE refresh_tokens SET revoked_at = now()`).
- **Drift del schema de claims.** Si `identity` agrega un claim que el consumer espera (ej. `vendor_id`) sin que los consumers se actualicen, los flows de autorización empiezan a fallar silenciosamente. Los claims son parte del contrato; cambiarlos requiere coordinación.
- **Falsa sensación de "está hecho porque corre Spring AS".** Spring Authorization Server cubre los happy paths; las superficies de error (consent revocation, refresh reuse detection, redirect URI validation) requieren pruebas explícitas. No confundir "tengo OAuth2" con "tengo OAuth2 seguro".
- **Endpoint `/oauth2/token` recibe tráfico no autenticado.** Es el endpoint de login, por diseño. Rate limiting + monitoring de intentos fallidos por IP/user son no-negociables. En Fase 1, rate limit genérico del gateway; en Phase 4, rules específicas.

## Alternativas consideradas

### Alternativa A: JWT custom con Nimbus JOSE+JWT + endpoints OAuth2 escritos a mano

Usar Nimbus directo para todo: firmar/verificar JWTs, exponer `/.well-known/jwks.json`, escribir manualmente `/oauth2/authorize`, `/oauth2/token`, `/oauth2/revoke`, `/userinfo`, `/.well-known/openid-configuration`.

**Por qué perdió:**

- **El valor educativo está en entender los flujos, no en re-implementar el RFC.** Nimbus enseña JWS/JWE/JWK, que se aprende igual configurando Spring AS (el `JWKSource` bean es código nuestro). Escribir `/oauth2/authorize` a mano no enseña OAuth2 — enseña a leer un RFC denso y a cometer errores que ya cometieron todos los que escribieron OAuth2 sin librería antes.
- **Superficie de bug de seguridad enorme.** PKCE state matching, redirect URI exact match, error response shape, consent flow, response_type validation — cada uno es una oportunidad de introducir un CVE. La historia de la industria está llena de implementaciones caseras de OAuth2 que terminaron con vulnerabilidades reportadas (CVE-2022-29217, CVE-2018-1000520, etc.).
- **Tiempo de desarrollo desproporcionado.** Escribir bien todos los endpoints + tests adecuados es ~3-4 semanas de trabajo. Configurar Spring AS para el mismo resultado funcional es ~1-2 días.
- **Sr/Staff defendible es "elegir la pieza canónica donde el riesgo es alto".** No es "demostrar que puedo escribir un AS"; eso lo demuestra la elección de configurar uno con criterio.

Donde Nimbus solo *sí* tendría sentido: si solo necesitaras firmar y verificar JWTs sin exponer flujos OAuth2 (ej. machine-to-machine con pre-shared key derivation). No es nuestro caso.

### Alternativa B: Híbrido — Nimbus para JWS/JWK + handlers manuales solo para los endpoints usados

Usar Nimbus para el plumbing de JWT/JWK + Spring MVC plano para los pocos endpoints que el cliente real usa (login, refresh).

**Por qué perdió:**

- **Es la trampa del 80/20.** "Solo voy a implementar los endpoints que uso" se convierte en "ahora necesito introspection para X" → "ahora necesito revocation porque..." → terminás con Spring AS escrito a medias y peor.
- **OIDC discovery es contagioso.** El día que conectás cualquier herramienta OAuth2 estándar (Postman, oauth2-proxy, una lib cliente de cualquier lenguaje), espera `/.well-known/openid-configuration`. Si tu AS no lo expone, la herramienta no funciona. Implementarlo desde cero no es trivial — son 30 campos que tienen que estar bien.
- **No ahorra trabajo significativo.** El 90% del trabajo de un AS está en `/oauth2/token` y `/oauth2/authorize`, no en los que se omitirían.
- Solo válido como puente temporal si por algún motivo Spring AS no fuera viable. No es el caso.

### Alternativa C: Token opaco + introspection endpoint en lugar de JWT

`identity` genera un token aleatorio (string random de 256 bits). Persiste en `identity_db.access_tokens`. Cada servicio downstream que valida llama a `identity/introspect` para verificar.

**Por qué perdió:**

- **`identity` se vuelve SPOF del path caliente.** Cada gRPC call entre servicios = un round-trip más a `identity`. La latencia se acumula.
- **No escala horizontalmente sin caché.** Y caché reintroduce el problema de invalidación que motivó elegir el opaco token en primer lugar.
- **Acopla a todos los servicios con `identity` operativamente.** Si `identity` está degradado, todo el sistema lo está.
- **Solo gana en revocación inmediata.** Y eso lo resolvemos con TTL corto + revocación de refresh + kill switch.
- Donde sí tiene sentido: sistemas donde la revocación inmediata es requisito regulatorio (banca, salud) y el costo del round-trip es aceptable. No es nuestro caso.

### Alternativa D: Sesiones server-side con cookie (sin JWT)

Cookie `JSESSIONID` con sesión persistida en Redis. El gateway valida la sesión consultando Redis. Sin tokens, sin JWKS, sin firma asimétrica.

**Por qué perdió:**

- **No es OAuth2/OIDC.** El proyecto declara explícitamente que quiere ejercitar OAuth2/OIDC como objetivo de aprendizaje. Una sesión-cookie cubre auth pero no enseña el modelo de delegación de OAuth2 (clients que actúan en nombre de users, scopes, refresh tokens).
- **Cookie + microservicios + gRPC interno no encaja limpio.** Las cookies son del mundo browser ↔ servidor HTTP. Propagar identidad a 5 servicios internos vía cookie obliga a inventar headers de identidad propios cada vez que se cruza el gateway, que es exactamente lo que JWT bearer resuelve.
- **Redis se convierte en SPOF del path caliente.** Cada request gRPC interna que necesita validar identidad → otro round-trip a Redis. Caché local en cada servicio reintroduce coherencia.
- **No escala a clientes no-browser.** Mobile native, jobs batch, CLIs, partners B2B — todos esos son ciudadanos de primera en OAuth2 y de segunda en sesión-cookie.
- Donde tiene sentido: monolitos tradicionales con un solo frontend web. No es nuestra arquitectura.

### Alternativa E: Delegar el rol de Authorization Server a un IdP-as-a-service externo

Mencionada por completitud. **Descartada por política del proyecto** — el ejercicio es construir las piezas críticas de identidad dentro del propio sistema. Soluciones tipo Keycloak, Authentik, Auth0, Okta y similares quedan fuera del espacio de soluciones aceptable para este ADR.

### Alternativa F: Macaroons en lugar de JWT

Macaroons son tokens con caveats restringibles por el caller (third-party caveats, location restrictions). Más expresivos que JWT.

**Por qué perdió:**

- **Ecosistema marginal en Java.** No hay una librería mainstream con la solidez de Nimbus.
- **Modelo mental denso para un dev Java promedio.** Macaroons son geniales en investigación y en sistemas específicos (Hyperledger, Cloudflare) pero la curva de aprendizaje no se justifica para un proyecto educativo cuyo objetivo es enseñar lo que el mercado pide.
- **No es OAuth2/OIDC compatible.** Cualquier herramienta estándar (Postman, libs cliente) habla JWT, no macaroons.

### Alternativa G: PASETO en lugar de JWT

PASETO (Platform-Agnostic Security Tokens) es la respuesta moderna a las críticas históricas de JWT (`alg=none`, algorithm confusion, JOSE header complejidad). Versionado, sin alg confusion, defaults seguros.

**Por qué perdió:**

- **Ecosistema mucho más chico.** PASETO existe pero está lejos de la ubicuidad de JWT. Spring Authorization Server no lo soporta nativamente. Las libs Java existen pero son hobbyistas.
- **Para Sr/Staff vale la pena conocerlo y mencionarlo en una entrevista**, no construirse encima cuando el resto del ecosistema habla JWT. El proyecto perdería interoperabilidad con cualquier tool estándar.
- Si en Phase 4-5 PASETO gana tracción en Java enterprise, se evalúa migrar. Hoy sería arquear contra el viento.

## Referencias

- ADR `0001` — Avro para Kafka: eventos `UserRegistered`/`VendorRegistered` viajan por outbox como cualquier otro evento de dominio.
- ADR `0002` — gRPC interno: el JWT se propaga vía metadata gRPC `authorization`.
- ADR `0003` — K8s diferido: una réplica de `identity`; rotación de claves diseñada compatible con multi-réplica futura.
- ADR `0004` — Monorepo Gradle: `melown.auth-resource-server` como convention plugin para que cada servicio configure Resource Server consistente.
- ADR `0005` — Hexagonal: use cases `Authenticate`, `RegisterUser`, `RegisterVendor` en application; Spring Authorization Server vive en infrastructure adapters.
- ADR `0006` — Observability: el JWT no se loguea completo (política "qué NO loguear"); solo `sub` y los primeros 8 chars del token.
- ADR `0007` — Outbox + Debezium: emisión de eventos de `identity` (`UserRegistered`, etc.) usa el mismo patrón.
- [Spring Authorization Server (reference)](https://docs.spring.io/spring-authorization-server/reference/).
- [Spring Security OAuth2 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html).
- [Nimbus JOSE+JWT](https://connect2id.com/products/nimbus-jose-jwt).
- [RFC 6749 — OAuth 2.0](https://datatracker.ietf.org/doc/html/rfc6749).
- [RFC 7636 — PKCE](https://datatracker.ietf.org/doc/html/rfc7636).
- [RFC 7519 — JWT](https://datatracker.ietf.org/doc/html/rfc7519).
- [RFC 7515 — JWS](https://datatracker.ietf.org/doc/html/rfc7515).
- [RFC 7517 — JWK](https://datatracker.ietf.org/doc/html/rfc7517).
- [RFC 7518 — JWA](https://datatracker.ietf.org/doc/html/rfc7518).
- [RFC 8628 — Device Authorization Grant](https://datatracker.ietf.org/doc/html/rfc8628) (mencionado y descartado).
- [OAuth 2.1 draft (deprecates Implicit, ROPC)](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1).
- [OWASP Password Storage Cheat Sheet (Argon2id)](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).
- [NIST SP 800-63B — Digital Identity Guidelines](https://pages.nist.gov/800-63-3/sp800-63b.html).
- ADRs pendientes que tocan esta área: política de "data classification / PII" (la columna `email` y los `refresh_tokens` requieren encryption-at-rest), MFA / WebAuthn (Phase 4), separación de `auth-server` como servicio independiente de `identity` (eventual, no urgente).
