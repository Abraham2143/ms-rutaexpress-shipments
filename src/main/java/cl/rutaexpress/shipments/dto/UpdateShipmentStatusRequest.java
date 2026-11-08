package cl.rutaexpress.shipments.dto;

import cl.rutaexpress.shipments.entity.ShipmentStatus;

public record UpdateShipmentStatusRequest(
        ShipmentStatus status,
        String comment
) {}