package cl.rutaexpress.shipments.messaging;

import cl.rutaexpress.shipments.entity.Shipment;
import cl.rutaexpress.shipments.entity.ShipmentStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

public record ShipmentNotification(
        Long shipmentId,
        String trackingNumber,
        ShipmentStatus status,
        String destinatarioNombre,
        String destinatarioEmail,
        String comment,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp) {

    public static ShipmentNotification from(Shipment shipment) {
        return from(shipment, null);
    }

    public static ShipmentNotification from(Shipment shipment, String comment) {
        return new ShipmentNotification(
                shipment.getId(),
                shipment.getTrackingNumber(),
                shipment.getEstado(),
                shipment.getDestinatarioNombre(),
                shipment.getDestinatarioEmail(),
                comment,
                Instant.now());
    }
}