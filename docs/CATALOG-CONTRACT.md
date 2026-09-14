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

El cliente no crea datos locales ni accede a tablas de Catalog. El JWT de la petición actual se propaga mediante el header `Authorization`.
