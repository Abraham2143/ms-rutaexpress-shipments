package cl.rutaexpress.shipments.messaging;

import cl.rutaexpress.shipments.entity.Shipment;
import cl.rutaexpress.shipments.entity.ShipmentStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/** Immutable snapshot: no entity or authentication token crosses the AMQP boundary. */
public record ShipmentNotification(
        Long shipmentId,
        String trackingNumber,
        ShipmentStatus status,
        String destinatarioNombre,
        String destinatarioEmail,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp) {

    public static ShipmentNotification from(Shipment shipment) {
        return new ShipmentNotification(shipment.getId(), shipment.getTrackingNumber(),
                shipment.getEstado(), shipment.getDestinatarioNombre(),
                shipment.getDestinatarioEmail(), Instant.now());
    }
}
