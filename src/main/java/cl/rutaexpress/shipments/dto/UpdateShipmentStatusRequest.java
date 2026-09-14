package cl.rutaexpress.shipments.dto;

import cl.rutaexpress.shipments.entity.ShipmentStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateShipmentStatusRequest(@NotNull ShipmentStatus status) {
}
