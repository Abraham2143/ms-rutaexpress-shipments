# ms-rutaexpress-shipments

Microservicio Java 21 / Spring Boot 4.1.1, Oracle Autonomous Database,
OAuth2 Resource Server Microsoft Entra ID, puerto 8081.

- [Despliegue en EC2, variables, Wallet, Docker y Compose](docs/DEPLOYMENT.md)
- [Contrato HTTP requerido de Catalog y límites transaccionales](docs/CATALOG-CONTRACT.md)

## API y permisos

| Método y path | Roles permitidos |
| --- | --- |
| `POST /api/shipments` | `ADMIN`, `CLIENT` |
| `GET /api/shipments/{id}` | `ADMIN`, `DISPATCHER`, `CLIENT`, `Auditor` |
| `PUT /api/shipments/{id}/status` | `ADMIN`, `DISPATCHER` |
| `GET /api/shipments?status=...&from=...&to=...` | `ADMIN`, `DISPATCHER`, `CLIENT`, `Auditor` |
| `GET /actuator/health` | Público; solo estado, sin detalles |

Se conserva `Auditor` como lectura heredada del README.AGENT.md; no se exige
crearlo en Entra ni se implementa un servicio Audit. Los nombres operativos
anteriores Admin/Operador/Cliente se sustituyen por los roles reales indicados
para el despliegue: ADMIN/DISPATCHER/CLIENT. No se añaden reglas de propiedad
por cliente: las consultas conservan el alcance existente para esos roles.

El claim `roles` se convierte a authorities `ROLE_<valor>` respetando mayúsculas.
Todas las peticiones de la API requieren Bearer JWT válido; se validan firma
RS256, issuer exacto, vigencia y pertenencia de `AZURE_AUDIENCE` a `aud`.
El servicio es stateless. Las claves se descubren desde el issuer al recibir
el primer JWT; `AZURE_JWK_SET_URI` es un override opcional, sin fallback local.
[Referencia Spring Security](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).

Crear envío:

```json
{
  "clienteId": "cliente-001",
  "destinatarioNombre": "Ana Pérez",
  "destinatarioEmail": "ana@example.com",
  "direccionOrigen": "Bodega Central",
  "direccionDestino": "Av. Ejemplo 123",
  "servicioId": 1
}
```

Respuesta `201` con DTO: id, trackingNumber, datos anteriores, `status: CREADO`,
fechaCreacion, fechaAceptacion/fechaEntrega inicialmente nulas y creadoPor
obtenido del JWT. El tracking es único y se genera en backend.

Cambiar estado:

```json
{"status": "ACEPTADO"}
```

Filtros de fecha ISO `yyyy-MM-dd` sobre fechaCreacion, inclusivos por día:
`GET /api/shipments?status=CREADO&from=2026-09-01&to=2026-09-14`.
Cada filtro es opcional; un rango invertido, fecha o estado inválido produce
400. Se ordena por creación descendente. Se conservan fechas sin zona del
modelo original; usar una misma zona operativa en los despliegues.

## Estados

Flujo normal: `CREADO -> ACEPTADO -> EN_BODEGA -> EN_RUTA -> ENTREGADO`.
Cancelación desde `CREADO`, `ACEPTADO` o `EN_BODEGA` hacia `CANCELADO`.
No se permiten saltos, retrocesos ni repetición de estados. `ENTREGADO` y
`CANCELADO` son terminales. `EN_RUTA` exige fecha de aceptación registrada.

Al aceptar se llama sincrónicamente a Catalog con el mismo JWT recibido.
Solo un HTTP 204 confirma el descuento antes de guardar ACEPTADO. Los cambios
se serializan mediante bloqueo pesimista de la fila. Consultar el contrato
para idempotencia, fallos entre servicios y cancelaciones posteriores.

Errores: 400 validación/transición; 401 JWT ausente/inválido; 403 permisos;
404 envío/servicio inexistente; 409 capacidad/conflicto de persistencia;
502 integración Catalog. No se exponen respuestas internas de Catalog.

## Build

```sh
./mvnw clean test
./mvnw clean package
docker build -t rutaexpress-shipments .
```

En Windows usar `./mvnw.cmd`. Docker es multi-stage Maven/Java 21 y JRE 21,
usuario 10001:10001 y puerto 8081. Wallet y secretos se montan/proporcionan
solo al ejecutar, nunca al construir. Ver la guía EC2 para comandos completos.

Las pruebas ejecutan lógica de estados, repositorios en H2 (solo tests),
seguridad con JWT firmados temporalmente y un servidor HTTP local de prueba
para discovery/JWKS y Catalog, incluidos errores, timeout y concurrencia.
No necesitan Oracle ni Entra reales. No constituyen una validación contra
los servicios desplegados.
