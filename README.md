# ms-rutaexpress-shipments

Microservicio Java 21 / Spring Boot 4.1.1, Oracle Autonomous Database,
OAuth2 Resource Server Microsoft Entra ID, puerto 8081.

- [Despliegue en EC2, variables, Wallet, Docker y Compose](docs/DEPLOYMENT.md)
- [Contrato HTTP requerido de Catalog y límites transaccionales](docs/CATALOG-CONTRACT.md)

## API y permisos

| Método y path | Roles permitidos |
| --- | --- |
| `POST /api/shipments` | `ADMIN`, `CLIENT` |
| `GET /api/shipments/{id}` | `ADMIN`, `DISPATCHER`, `CLIENT`, `AUDITOR` |
| `PUT /api/shipments/{id}/status` | `ADMIN`, `DISPATCHER` |
| `GET /api/shipments?status=...&from=...&to=...` | `ADMIN`, `DISPATCHER`, `CLIENT`, `AUDITOR` |
| `GET /actuator/health` | Público; solo estado, sin detalles |

El rol de solo lectura es `AUDITOR` (en mayúsculas); no se exige
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


## Notificaciones RabbitMQ

Shipments emite una notificación cuando se confirma una transición a ACEPTADO,
EN_RUTA o ENTREGADO. El servicio publica una instantánea inmutable como evento
interno de Spring; ShipmentNotificationPublisher la envía con
@TransactionalEventListener(AFTER_COMMIT). No se usa RabbitTemplate en controllers.
Creación, EN_BODEGA, CANCELADO, transiciones inválidas, errores de Catalog y
rollback/fallos de commit no envían notificación.

Ruta oficial: exchange cmd.direct, routing key email.send, cola q.cmd.email.
RabbitMQ Admin administra exchanges, queues, bindings y DLQ. Shipments no los
declara y tiene spring.rabbitmq.dynamic=false. No requiere cambios en Notify.

Variables de entorno:

| Variable | Uso |
| --- | --- |
| RABBITMQ_HOST | localhost por defecto; usar dirección accesible del broker |
| RABBITMQ_PORT | 5672 por defecto |
| RABBITMQ_USERNAME | Obligatoria, usuario del broker |
| RABBITMQ_PASSWORD | Obligatoria, secreto del broker |
| RABBITMQ_VHOST | Opcional; / por defecto, igual al de Notify/Admin |

No se incluyen contraseñas de desarrollo ni secretos reales. Las variables DB_*,
AZURE_* y CATALOG_BASE_URL existentes siguen siendo necesarias según el entorno.
El timeout de conexión RabbitMQ es 5 segundos. Configura estas variables también
en la ejecución de IntelliJ; no se heredan de otra terminal.

JSON publicado (ejemplo; id, tracking, destinatario, estado y timestamp varían):

```json
{
  "shipmentId": 42,
  "trackingNumber": "RUTA-ABC123",
  "status": "ACEPTADO",
  "destinatarioNombre": "Ana Pérez",
  "destinatarioEmail": "ana@example.test",
  "timestamp": "2026-10-16T02:00:00Z"
}
```

timestamp es un instante ISO-8601 UTC generado al registrar el evento interno
después de guardar la entidad y antes del commit; no es la hora de entrega.
No hay envelope ni serialización Java: content_type=application/json,
content_encoding=UTF-8 y delivery_mode=2 (persistente).

### Prueba local con Notify

1. Mantener un solo RabbitMQ accesible en 127.0.0.1:5672. No iniciar otro contenedor
   que intente ocupar los mismos puertos. RabbitMQ Admin debe haber aprovisionado
   cmd.direct, email.send -> q.cmd.email y su DLQ.
2. Ejecutar Notify con su listener tolerante de q.cmd.email y el mismo broker/vhost.
   Este listener solo imprime el JSON y confirma; no envía correo.
3. Configurar Shipments con sus variables Oracle, Azure, Catalog y las RABBITMQ_*
   anteriores. Desde esta carpeta ejecutar .\mvnw.cmd spring-boot:run con Java 21.
4. Crear un envío con POST /api/shipments y token autorizado. Con un token ADMIN
   o DISPATCHER, llamar PUT /api/shipments/{id}/status con {"status":"ACEPTADO"}.
   La aceptación exige que Catalog confirme el decremento con HTTP 204; consultar
   docs/CATALOG-CONTRACT.md. No se omite esa regla para probar RabbitMQ.
5. Comprobar en Notify: Mensaje recibido de q.cmd.email: seguido del JSON anterior.
   Avanzar EN_BODEGA (sin mensaje), EN_RUTA (mensaje) y ENTREGADO (mensaje).
6. Intentar una transición inválida: debe conservar la respuesta REST de error
   y no aparecer una notificación nueva. CREADO y CANCELADO tampoco notifican.

Ejemplo PowerShell para un envío real existente y un token guardado solo en entorno:

```powershell
$shipmentId = 42 # Sustituir por el ID real creado en esta base.
$headers = @{ Authorization = "Bearer $env:ACCESS_TOKEN" }
Invoke-RestMethod -Method Put -Uri "http://localhost:8081/api/shipments/$shipmentId/status" `
  -Headers $headers -ContentType 'application/json' -Body '{"status":"ACEPTADO"}'
```

Las colas locales antiguas pueden tener *.dead y Admin usar *.dlq como routing
key de dead-letter: resolver esa diferencia operativamente sin redeclarar colas
incompatibles ni borrar mensajes. Shipments no administra esa configuración.

### Límites de esta etapa

No hay outbox, reintentos automáticos ni garantía exactly-once. Un proceso que
cae entre el commit y el envío puede perder la notificación. Un error de envío
se registra como notification_publish_failed con shipmentId/estado/clase de error,
sin destinatarios ni credenciales. No deshace Oracle ni transforma en error REST
un cambio ya confirmado; no se marca el envío como notificado.
El log notification_submitted significa que RabbitTemplate retornó, no prueba
de entrega al consumidor. No se implementan publisher confirms/returns en esta
etapa; una routing key sin binding puede perderse sin error de envío. Por eso la
prueba de recepción en Notify es necesaria. Una DLQ no recupera fallos anteriores
al enrutamiento/publicación. El contrato REST permanece igual.

Pruebas: .\mvnw.cmd -B -ntp clean verify. Usan H2 para transacciones reales,
Catalog simulado en tests y RabbitTemplate mockeado. Comprueban los tres estados,
ausencia de envío antes del commit/tras rollback/fallo de commit, formato JSON,
ruta y persistencia AMQP, errores de envío y regresiones HTTP/JWT existentes.
No acreditan Oracle real ni el recorrido RabbitMQ -> Notify desplegado.

Verificación ejecutada con Java 21: `.\mvnw.cmd -B -ntp clean verify` terminó
con BUILD SUCCESS: 79 pruebas, 0 fallos, 0 errores, 0 omitidas, y JAR generado.
