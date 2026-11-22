package cl.rutaexpress.shipments.messaging;

import cl.rutaexpress.shipments.entity.ShipmentStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento Kafka de cambio de estado de un envío, contrato v1 de kafka-analytics
 * (ms-rutaexpress-report, docs/EVENT_CONTRACTS.md).
 * No incluye datos personales del destinatario: solo identificadores y estado.
 */
public record ShipmentEventV1(
        String schemaVersion,
        String eventId,
        String eventType,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
        String shipmentId,
        String clienteId,
        String trackingNumber,
        String status,
        String correlationId) {

    public static final String SCHEMA_VERSION = "1.0";
    public static final String SHIPMENT_CREATED = "SHIPMENT_CREATED";
    public static final String SHIPMENT_STATUS_CHANGED = "SHIPMENT_STATUS_CHANGED";

    public ShipmentEventV1(
            String schemaVersion,
            String eventId,
            String eventType,
            Instant occurredAt,
            String shipmentId,
            String status,
            String correlationId) {
        this(schemaVersion, eventId, eventType, occurredAt, shipmentId, null, null, status, correlationId);
    }

    public static ShipmentEventV1 from(ShipmentNotification notification) {
        String shipmentId = String.valueOf(notification.shipmentId());
        String status = notification.status().name();
        return new ShipmentEventV1(
                SCHEMA_VERSION,
                eventId(shipmentId, status, notification.timestamp()),
                notification.status() == ShipmentStatus.CREADO ? SHIPMENT_CREATED : SHIPMENT_STATUS_CHANGED,
                notification.timestamp(),
                shipmentId,
                notification.clienteId(),
                notification.trackingNumber(),
                status,
                null);
    }

    /** Mismo envío, estado e instante: mismo eventId; así kafka-analytics descarta duplicados. */
    private static String eventId(String shipmentId, String status, Instant occurredAt) {
        String source = shipmentId + ":" + status + ":" + occurredAt;
        return "evt-" + UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }
}