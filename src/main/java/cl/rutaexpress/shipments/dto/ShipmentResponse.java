package cl.rutaexpress.shipments.dto;

import cl.rutaexpress.shipments.entity.Shipment;
import cl.rutaexpress.shipments.entity.ShipmentStatus;

import java.time.LocalDateTime;

public record ShipmentResponse(
        Long id,
        String trackingNumber,
        String clienteId,
        String destinatarioNombre,
        String destinatarioEmail,
        String direccionOrigen,
        String direccionDestino,
        Long servicioId,
        ShipmentStatus status,
        LocalDateTime fechaCreacion,
        LocalDateTime fechaAceptacion,
        LocalDateTime fechaEntrega,
        String creadoPor
) {
    public static ShipmentResponse from(Shipment shipment) {
        return new ShipmentResponse(shipment.getId(), shipment.getTrackingNumber(), shipment.getClienteId(),
                shipment.getDestinatarioNombre(), shipment.getDestinatarioEmail(), shipment.getDireccionOrigen(),
                shipment.getDireccionDestino(), shipment.getServicioId(), shipment.getEstado(),
                shipment.getFechaCreacion(), shipment.getFechaAceptacion(), shipment.getFechaEntrega(),
                shipment.getCreadoPor());
    }
}
