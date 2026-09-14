# README.AGENT.md — ms-rutaexpress-shipments

## Contexto importante

Este repositorio contiene SOLO `ms-rutaexpress-shipments`.

Los otros componentes (`ms-rutaexpress-catalog`, `ms-rutaexpress-bff` y Angular) están en repositorios/proyectos separados y pueden estar abiertos en otras ventanas de IntelliJ.

Por lo tanto:

- No asumir acceso al código de otros repositorios.
- No intentar modificar Catalog, BFF o Angular.
- Toda integración externa debe hacerse por HTTP y mediante contratos documentados.
- Las URLs externas deben configurarse por variables de entorno.
- Si un endpoint externo necesario aún no existe, informar el contrato requerido y dejar la integración preparada, sin inventar implementaciones en otros repositorios.

## Proyecto

```text
Microservicio: ms-rutaexpress-shipments
Group: cl.rutaexpress
Package base: cl.rutaexpress.shipments
Java: 21
Maven
Puerto: 8081
```

## Alcance de la primera entrega

Implementar:

- Spring Web
- Spring Data JPA
- Oracle Driver
- HTTP Client
- Spring Security
- OAuth2 Resource Server
- Validation
- Lombok
- Persistencia Oracle
- CRUD/consulta de envíos
- Cambio de estado
- Filtros
- JWT Azure AD
- Roles
- Cliente HTTP a Catalog
- Pruebas básicas

NO implementar todavía:

- RabbitMQ
- Kafka
- Zookeeper
- Docker
- AWS
- Notificaciones asíncronas
- Analítica streaming

## Dominio

Estados:

```text
CREADO
ACEPTADO
EN_BODEGA
EN_RUTA
ENTREGADO
CANCELADO
```

Usar `enum ShipmentStatus`.

Reglas mínimas:

- Nuevo envío -> `CREADO`.
- No permitir `CREADO -> EN_RUTA`.
- `EN_RUTA` requiere aceptación previa.
- `ENTREGADO` y `CANCELADO` son estados terminales para el flujo normal.
- Al aceptar un envío debe coordinarse la disminución de capacidad con Catalog.
- Las reglas deben validarse en backend.

## Entidad inicial sugerida

`Shipment`:

```text
id: Long
trackingNumber: String
clienteId: String
destinatarioNombre: String
destinatarioEmail: String
direccionOrigen: String
direccionDestino: String
servicioId: Long
estado: ShipmentStatus
fechaCreacion: LocalDateTime
fechaAceptacion: LocalDateTime
fechaEntrega: LocalDateTime
creadoPor: String
```

Recomendaciones:

- `trackingNumber` único.
- `@Enumerated(EnumType.STRING)` para estado.
- `fechaCreacion` y estado inicial asignados en backend.
- No devolver Entity directamente desde Controller.

## Endpoints requeridos

```http
POST /api/shipments
GET /api/shipments/{id}
PUT /api/shipments/{id}/status
GET /api/shipments?status=...&from=...&to=...
```

Ejemplo POST:

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

Ejemplo status:

```json
{
  "status": "ACEPTADO"
}
```

## Estructura sugerida

```text
cl.rutaexpress.shipments
├── controller
├── service
├── repository
├── entity
├── dto
├── client
├── security
├── config
├── exception
└── ShipmentsApplication
```

Separación:

```text
Controller -> Service -> Repository -> Oracle
                  |
                  +-> CatalogClient -> HTTP -> Catalog
```

## Integración con Catalog

Catalog está en OTRO repositorio y aplicación.

Configurar:

```properties
rutaexpress.catalog.base-url=${CATALOG_BASE_URL:http://localhost:8082}
```

No acceder a tablas de Catalog.

El cliente HTTP debe estar aislado en `client/CatalogClient`.

Si Catalog aún no tiene un endpoint para consultar/decrementar capacidad:

- no modificar Catalog;
- no inventar datos;
- dejar el cliente preparado;
- documentar exactamente qué endpoint/contrato necesita Shipments.

## Oracle

Usar variables de entorno:

```properties
spring.datasource.url=${DB_URL}
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}
spring.datasource.driver-class-name=oracle.jdbc.OracleDriver
spring.jpa.hibernate.ddl-auto=update
```

Si se utiliza Wallet de Oracle Autonomous, respetar la configuración definida por el usuario.

Nunca subir credenciales o Wallet a Git.

## Seguridad

Configurar Resource Server:

```properties
spring.security.oauth2.resourceserver.jwt.issuer-uri=${AZURE_ISSUER_URI}
```

Roles:

```text
Admin
Operador
Cliente
Auditor
```

Azure puede enviar roles en claim `roles`; mapear a `ROLE_Admin`, `ROLE_Operador`, etc.

Sugerencia:

- POST shipment: Cliente/Admin.
- PUT status: Operador/Admin.
- GET: según rol definido.
- Auditor: solo lectura.

No dejar `permitAll()` global como solución final.

## JWT entre servicios

El BFF está en otro repositorio y enviará el JWT al llamar a este servicio.

Shipments debe validar normalmente:

```http
Authorization: Bearer <JWT>
```

Si Shipments llama a Catalog y Catalog está protegido, propagar el token de la petición actual.

## Manejo de errores

Usar `@RestControllerAdvice`.

Mínimos:

```text
400 -> validación/transición inválida
401 -> token ausente/inválido
403 -> rol insuficiente
404 -> envío/recurso no encontrado
409 -> capacidad insuficiente o conflicto de estado
```

## Pruebas

Como mínimo:

- creación;
- estado inicial CREADO;
- consulta existente;
- 404;
- transición válida;
- transición inválida;
- capacidad disponible;
- capacidad insuficiente;
- DTO inválido.

Mockear `CatalogClient` en pruebas unitarias.

## Reglas para el agente

1. Trabajar SOLO en este repositorio.
2. No buscar ni editar los otros repositorios.
3. Revisar primero `pom.xml` y `application.properties`.
4. No implementar RabbitMQ/Kafka.
5. No agregar dependencias innecesarias.
6. No hardcodear credenciales ni URLs productivas.
7. No acceder a tablas de Catalog.
8. No duplicar lógica en Controller.
9. Usar DTOs.
10. No cambiar Java 21, Maven, artifact o package base.
11. Si falta un contrato externo, documentarlo y detener esa parte.
12. Mantener el proyecto compilable.

## Orden de trabajo

1. Revisar proyecto y dependencias.
2. Crear enum/entity/repository/DTOs.
3. Configurar Oracle.
4. Implementar Service.
5. Implementar Controller.
6. Implementar reglas de estado.
7. Crear CatalogClient.
8. Configurar JWT y roles.
9. Agregar pruebas.
10. Ejecutar:

```bash
mvn clean test
mvn clean package
```

## Al finalizar

Informar:

- archivos creados/modificados;
- endpoints;
- variables de entorno;
- contrato esperado de Catalog;
- ejemplos JSON;
- resultado de tests/build;
- pendientes de integración con otros repositorios.
