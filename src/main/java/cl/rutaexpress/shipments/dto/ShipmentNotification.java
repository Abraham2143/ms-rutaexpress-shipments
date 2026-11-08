package cl.rutaexpress.shipments.dto;

import cl.rutaexpress.shipments.entity.Shipment;
import cl.rutaexpress.shipments.entity.ShipmentStatus;

public record ShipmentNotification(
        Long shipmentId,
        String trackingNumber,
        String destinatarioEmail,
        ShipmentStatus status,
        String comment
) {
    public static ShipmentNotification from(Shipment shipment) {
        return new ShipmentNotification(
                shipment.getId(),
                shipment.getTrackingNumber(),
                shipment.getDestinatarioEmail(),
                shipment.getEstado(),
                shipment.getUltimoComentario()
        );
    }
}