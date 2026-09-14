# Contrato requerido para Catalog

Shipments necesita que Catalog exponga:

```http
POST {CATALOG_BASE_URL}/api/catalog/services/{serviceId}/capacity/decrement
Authorization: Bearer <JWT recibido por Shipments>
Content-Type: application/json
```

Request:

```json
{
  "shipmentTrackingNumber": "RUTA-ABC123",
  "quantity": 1
}
```

Responses esperadas:

- `204 No Content`: capacidad descontada correctamente.
- `409 Conflict`: capacidad insuficiente; Shipments devuelve `409`.
- `401/403`: token inválido o sin permisos; debe propagarse como error de integración.
- `404 Not Found`: servicio inexistente.

Shipments exige `204` como confirmación de una operación terminada. Un `200`,
`202`, redirect, fallo de conexión/timeout o cualquier otro código distinto de
los anteriores produce `502 Bad Gateway` sin modificar el estado local.
`401/403` de Catalog también se traducen a `502`: el JWT ya fue autenticado
por Shipments y el rechazo es del servicio externo. No se devuelven el body
ni las credenciales del upstream. Un `404` se devuelve como `404`.

El path puede configurarse mediante `CATALOG_CAPACITY_PATH`; debe conservar
el parámetro `{serviceId}`. La URL base usa `CATALOG_BASE_URL`.

## Atomicidad e idempotencia requeridas

Catalog debe decrementar atómicamente (sin permitir capacidad negativa) y
usar `shipmentTrackingNumber` como clave idempotente persistida junto con el
descuento. Repetir el mismo tracking, servicio y cantidad debe devolver `204`
sin descontar de nuevo; reutilizarlo con otros datos debe rechazarse con `409`.
El usuario autenticado debe tener permiso de aceptación (`ADMIN` o `DISPATCHER`).

Shipments bloquea la fila mientras cambia el estado, lo que evita dos
descuentos por aceptaciones concurrentes normales. No hay transacción
distribuida entre HTTP y Oracle: Catalog puede confirmar el descuento y luego
fallar la conexión, la respuesta o el commit de Shipments. Ante ese caso,
consultar el estado local y reconciliar o reintentar con el mismo tracking,
únicamente una vez que la idempotencia de Catalog esté confirmada. No se
implementan reintentos automáticos ni una falsa reserva local.

La cancelación posterior a la aceptación conserva el comportamiento existente:
no repone capacidad. No se ha definido ni implementado un endpoint de devolución.

## Estado de integración

Contrato requerido, **no verificado contra el Catalog desplegado**. Las pruebas
usan un servidor HTTP de test, no una implementación local de Catalog para
runtime. Confirmar método, path, códigos, permisos e idempotencia antes de
habilitar aceptaciones en EC2. Shipments no modifica el repositorio de Catalog.

El cliente no crea datos locales ni accede a tablas de Catalog. El JWT de la petición actual se propaga mediante el header `Authorization`.
